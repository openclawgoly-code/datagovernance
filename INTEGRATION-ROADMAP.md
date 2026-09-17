# dataQA × datagovernance 集成路线 · Integration Roadmap

> 输入:`GOVERNANCE-MODEL.md` §G(对接边界)、`SPACE-MODEL.md` §F(datagov 的 Command/Query/Event)、`~/projects/dataQA/architecture.md`(dataQA 的 5 适配器 SPI 与技术栈)
> 产出:Capability Contract(具体接口签名)+ Event Contract(Kafka topic 与 schema)+ 分阶段 PoC(Phase 0–4,每阶段可证伪判据)+ 契约冻结清单
> 状态:初版 · 待评审
> 日期:2026-09-16

---

## 0. 一句话与核心洞察

> **dataQA 架构自述「架在客户既有调度平台与元数据平台之上,对它们只读,唯一写入是创建质检调度任务」—— 那个「客户既有平台」就是 datagovernance。集成不是新建系统,而是为 dataQA 已定义好的 5 个适配器 SPI 写「打到 datagov」的实现,并让 datagov 补上对应的 Query / Command / Event 契约。**

三个事实让这条路线几乎是「填空」:

1. **技术栈同构**:两边都是 Java 17/21 + Spring Boot 3 + PostgreSQL + **Kafka** + APISIX。同步走 REST(经网关),异步走 Kafka —— 无需引入任何新中间件。
2. **插入点现成**:dataQA 的 `dq-capability-spi` 是「纯接口 + 值对象、零外部驱动依赖」的契约层;5 个适配器(`Metadata / Scheduler / Log / Code / Identity`)的实现类本就待写。集成 = 写这 5 个实现类 + datagov 侧接口。
3. **阻塞项即契约**:dataQA POC 未完成的 3 项(`decision.md` 待澄清清单)——① 建质检任务 API ② 数据权限粒度 ③ OA 身份协议 —— **全部由 datagov 侧提供**。冻结它们,dataQA POC 的剩余 5 项一并解锁。

### 诚实前提(避免把设计当已有)

| 侧 | 现状 | 集成需要 |
|---|---|---|
| datagov 的 §F 接口(`GetSchemaTree`、`CreateBatchDevJobDef` 等) | **SPACE-MODEL 的目标设计**,brownfield 尚未全部实现 | 按本文档冻结并落地对应接口 |
| dataQA 的 5 适配器 SPI | **接口已存在**(`dq-capability-spi`),实现类待写 | 写「打到 datagov」的实现类 |
| 两边的 Kafka | 都已选型,各自有 topic | 约定跨系统 topic 前缀与事件 schema |

本文档定义的是**接口契约与落地顺序**,不含具体代码。

---

## A. 对接拓扑

```text
        dataQA(治理 Agent 服务,独立部署)
   Interaction → Agent → Control → Capability
                                      │
              ┌───────────────────────┼───────────────────────┐
              │ REST(同步 Q/C,经 APISIX)     │ Kafka(异步 E)      │
              ▼                       ▼                        ▼
   datagov 同步接口(REST,6 个 Space):            datagov 事件总线(Kafka):
     Platform   GetUser                            SchemaRefreshed / SchemaDrifted
     Data       ExecuteQuery(受控,读码集)          ExecutionSucceeded / ExecutionFailed
     Metadata   GetSchemaTree / CreateBatchDevJobDef   SensitiveDataAccessed
     Control    CompileDefinition
     Runtime    ListExecutions / GetExecutionLog
     Governance EvaluateAccess / GetLineage
```

**两条边的耦合性质**(沿用两边一致的架构原则):

| 边 | 传输 | 失败语义 |
|---|---|---|
| 同步 `Q`/`C` | REST 经 APISIX | 被调方不可用 = 调用方该功能不可用(需超时+熔断) |
| 异步 `E` | Kafka | 晚到不影响正确性,只延迟归因/血缘/告警 |

**同步子图必须无环**。集成后的跨系统同步调用只有一个方向:`dataQA.Capability → datagov.{Platform, Data, Metadata, Control, Runtime, Governance}`(Platform 取身份、Data 走受控查询读码集、其余见 §B)。datagov **不同步调用 dataQA**(datagov 要用质检结果时消费 dataQA 发的事件)。跨系统同步序为 `platform → data → {metadata, runtime} → governance → control`,是严格 DAG,与两边各自「Governance 只发事件不发命令」的原则一致 —— 它也是 dataQA 对 datagov 的**启动 / 集成测试依赖序**。

**部署**:dataQA 作为独立服务部署在 datagov 旁,经 APISIX + Kafka 集成;**跨系统不共享数据库连接**(两边都有这条硬约束),dataQA 需要的 datagov 数据一律经 Query API 或事件物化视图,绝不直连对方库。

---

## B. Capability Contract

沿用 dataQA 的 capability 表达(`id / input / output / permission / adapter / write`),右列给出 datagov 侧承接的 §F 接口。

### B.1 MetadataAdapter(只读)→ datagov Metadata

```yaml
- id: get_schema_tree
  input:  { dataSourceRef, catalogPath? }
  output: { tables: [ { name, cnName, columns:[{name,cnName,type}] } ] }
  adapter: metadata ; write: false
  datagov: Q GetSchemaTree / GetDataSource / ListDataSourcesByCatalog   # F.2

- id: get_code_value_set
  input:  { codesetId }                 # 如 ref_dw_cd_val 的某 dw_cd_id
  output: { values: [ { code, label } ] }
  adapter: metadata ; write: false
  datagov: 【需冻结】码集是业务表,非 Metadata 定义。二选一:
           (a) datagov 新增 Q GetCodeValueSet(Metadata 侧登记码集为 Standard);
           (b) 经 Data Space 受控查询 Q ExecuteQuery(带超时+行数上限+审计)读 ref_dw_cd_val。
           MVP 走 (b);长期落 (a),对应 GOVERNANCE-MODEL 的 Standard(codeset)。
```

`SchemaSnapshot` 的刷新由事件驱动(见 C):dataQA Semantic 消费 `SchemaRefreshed`/`SchemaDrifted` 使快照失效重拉,不轮询。

### B.2 SchedulerAdapter(**唯一写入**)→ datagov Metadata + Control

```yaml
- id: create_quality_task
  input:  { table: TableRef, columns: [ColumnRef], sql: string, schedule: ScheduleSpec,
            createdBy: UserContext }
  output: { jobDefId: string }          # = dataQA 语境里的 taskId
  permission: quality_rule.publish ; adapter: scheduler ; write: true   # 全系统唯一 write:true
  datagov:
    C CreateBatchDevJobDef(sql, dataSourceRef)   # F.2 —— 质检=周期批 SQL,MVP 复用批开发任务
      → C BindSchedule(jobDefId, ScheduleSpec)    # F.2
      → C PublishDefinition(jobDefId)             # F.2,上线
      → Control C CompileDefinition(jobDefId)     # F.3,存在性/语法编译在 datagov 侧再校验一次
    【演进】datagov 落 C+D.6 预留的 QualityRule 类型后,改用专用 CreateQualityJobDef,
           质检结果 QualityResult 归 Governance(而非混在通用执行记录里)。

- id: list_failed_instances
  input:  { date, dataScope: UserContext }
  output: { instances: [ { jobDefId, execId, status, finishedAt } ] }
  adapter: scheduler ; write: false
  datagov: Q ListExecutions / GetExecution / GetExecutionAttempts       # F.4 Runtime
```

**写入落点的纪律**:质检任务是一个**作业定义**,创建入口在 Metadata(datagov 的唯一注册中心),不是 Control/Runtime —— Control 只编译、Runtime 只执行。这与 `GOVERNANCE-MODEL.md` §G 的修正一致。

### B.3 Log / Code Adapter(只读)→ datagov Runtime / Metadata

```yaml
- id: get_task_log
  input:  { execId, userContext }
  output: { log: string, truncated: bool }
  adapter: log ; write: false
  datagov: Q GetExecutionLog        # F.4 —— 归因取日志;truncated=true 触发 INCONCLUSIVE 判定

- id: get_task_code
  input:  { jobDefId, userContext }
  output: { sql: string, params: map }
  adapter: code ; write: false
  datagov: Q GetJobDef / GetDefinitionVersion   # F.2 —— 归因取任务定义/代码
```

### B.4 IdentityAdapter(只读)→ datagov Platform + Governance

```yaml
- id: resolve_user_scope
  input:  { userContext }            # 来自院方统一认证的身份
  output: { userId, dataScope: [ { type: DB|TABLE|ROW, expr } ] }
  adapter: identity ; write: false
  datagov: Q GetUser                                    # F.1 Platform —— 仅取身份
           dataScope 来源【需冻结】:F.1 无「数据作用域」Query,只有菜单权限
           GetEffectiveMenuPermissions —— SPACE-MODEL Platform MND 明文禁止拿菜单权限当数据权限。
           需 datagov 新增 Q GetDataScope(按 Workspace+资源返回 DB/TABLE/ROW),与 §F 冻结项 #2 对齐。
           判定不在这里:访问判定由 datagov Governance Q EvaluateAccess 执行(F.6)
```

**身份不自建、判定不前移**:两边都对接院方统一认证(OIDC/CAS/LDAP,`decision.md` ADR-005 待客户定)。`IdentityAdapter` 只取身份;数据作用域经待冻结的 `GetDataScope`(不得用菜单权限 `GetEffectiveMenuPermissions` 冒充),**访问判定归 Governance 的 `EvaluateAccess`**(与 GOVERNANCE-MODEL §C.5 一致)。

---

## C. Event Contract

Kafka,跨系统 topic 以 `dg.` 前缀标识来源平台。全部为 datagov → dataQA 单向广播(datagov 不消费 dataQA 的命令)。

| topic | 事件(datagov §F) | dataQA 消费方 | 用途 |
|---|---|---|---|
| `dg.metadata.schema` | `SchemaRefreshed` / `SchemaDrifted`(F.2) | Semantic | structure fact 更新 / 快照失效 |
| `dg.runtime.execution` | `ExecutionSucceeded` / `ExecutionFailed` / `ExecutionTimedOut`(F.4) | Agent(归因) | 触发/输入异常归因 |
| `dg.governance.sensitive` | `SensitiveDataAccessed`(F.6) | Governance | 敏感访问留痕汇聚(分级 L3+) |

**血缘不必自己派生**:datagov 侧承接的血缘查询(目标设计)`GetLineage` 与 `GetJobDataSourceRelations`(序号 8,F.2)、Governance 的 `GetLineageProvenance`(F.6),让 dataQA 需要血缘时**直接查 datagov**,而非自建。这与 `GOVERNANCE-MODEL.md` §C.6 一致(血缘来自 JobDef 序号 8 的读写边);需收敛的是该文术语表 / MND 里「从执行事件派生」的旧口径 —— 准确说法是:datagov 从 JobDef 读写边维护血缘并暴露为查询,dataQA 查询即可。

**事件 payload 最小 schema**(以 `ExecutionFailed` 为例):

```json
{ "jobDefId": "…", "execId": "…", "workspaceId": "…",
  "finishedAt": "…", "metric": { "rowsRead": 0, "rowsWritten": 0, "latencyMs": 0 },
  "errorClass": "…", "logRef": "…" }
```

注意 `metric` 只带**行数计数**(datagov 的 `ExecutionMetric`),**不含资产读写清单** —— 资产级读写来自 `GetJobDataSourceRelations`,不在事件里。

---

## D. 身份与安全(贯穿所有阶段)

1. **UserContext 跨系统透传**:dataQA 每个 Capability 调用携带发起用户身份,经网关透传到 datagov;datagov 用**同一身份**做数据权限判定。**无服务账号**——缺 UserContext 在 dataQA 侧类型层面就构造不出适配器。
2. **判定归属**:数据/资源在 datagov,故跨系统访问由 **datagov Governance `EvaluateAccess`** 判定;dataQA 内部动作由 dataQA 自己的 Governance 判定。两者以 UserContext 对齐,不互相代理。
3. **分级脱敏落点**:L3+ 资产的脱敏发生在**持有数据的一侧**——受控查询(`ExecuteQuery` / 数据预览)由 datagov 按分级 `mask`;dataQA 不缓存明文业务数据。
4. **唯一写入点**:跨系统唯一写入是 `create_quality_task`。不得新增第二个跨系统写入通道。

---

## E. 分阶段 PoC

每阶段给**可证伪的完成判据**,判据不达成不进入下一阶段(沿用两边的演进纪律)。风险沿「只读 → 单写入 → 事件 → 策略」递增。

### Phase 0 · 契约冻结(解锁前提)

- **范围**:冻结 3 个阻塞契约(见 [F](#f-契约冻结清单)),产出 datagov 侧 OpenAPI 3 + Kafka 事件 schema + UserContext 透传规范。
- **不写业务代码**,只定契约与桩。
- **判据**:dataQA 的 `MetadataAdapter / SchedulerAdapter / IdentityAdapter` SPI 能与 datagov 的 OpenAPI 生成的客户端**编译期对齐**(方法签名、值对象、错误码一一对应);对三个契约各有一个契约测试(Pact 或等价)跑通桩。

### Phase 1 · 只读打通(Metadata + Identity)

- **范围**:实现 `MetadataAdapter`(`get_schema_tree`/`get_code_value_set`)、`IdentityAdapter`(`resolve_user_scope`);消费 `dg.metadata.schema` 更新快照。
- **依赖顺序**:先 identity/platform,再 metadata(与两边 DAG 一致)。
- **前置**:确认 datagov `GetSchemaTree` 是否返回中文名(`cnName`)(`decision.md` #5);若否,dataQA 侧自建中文名索引,中文召回工作量按 ADR-004 上调。
- **判据**:dataQA 质检 Agent 对 datagov 中**真实登记的表**发起「找表 + 推荐规则」,中文表名可召回、字段清单来自 datagov 真值;全程携带 UserContext,日志可见每次 datagov 调用的发起人;**无任何服务账号**。

### Phase 2 · 唯一写入闭环(质检任务上线)

- **范围**:实现 `SchedulerAdapter.create_quality_task`,走 `CreateBatchDevJobDef → BindSchedule → PublishDefinition`,datagov `CompileDefinition` 二次编译。
- **判据**:dataQA 上「推荐 → 确认 → 上线」后,datagov 里出现一个**真实可调度**的质检 JobDef 并回传 `jobDefId`;dataQA 的存在性闸门与 datagov 的 `CompileDefinition` 对同一 RuleSpec 结论一致;**改规则只产生新 JobDef 版本,不改任何代码**(呼应 SPACE-MODEL R6)。

### Phase 3 · 事件驱动归因 + 血缘

- **范围**:消费 `dg.runtime.execution` 触发异常归因(`get_task_log`/`get_task_code`);血缘经 `GetLineage`/`GetJobDataSourceRelations` 查询。
- **判据**:datagov 中一个任务失败后,dataQA 归因 Agent 能拉到日志/代码/执行历史,给出根因 + 证据引用,或在日志截断/无错误行时正确落 `INCONCLUSIVE`;对一条跨表任务能展示 datagov 提供的上下游血缘。

### Phase 4 · 分级 / Policy + 治理汇聚

- **范围**:`classification` 打标(自动初判 + 人工复核);L3+ 受控查询 `mask`;消费 `dg.governance.sensitive` 汇聚敏感访问留痕。
- **判据**:未显式授权时 L3 资产在预览/质检取样中默认脱敏;一次针对 L3 表的跨系统访问在两边审计里都可查到同一 UserContext 的留痕;分级规则改动即改变默认 Policy,不改代码。

---

## F. 契约冻结清单(Phase 0 交付物)

dataQA `decision.md` 的 3 个阻塞项 → datagov 承接接口 → Owner。这张表冻结即解锁 dataQA POC 剩余工作。

| # | dataQA 阻塞项 | 冻结为 datagov 契约 | Owner | 解锁 |
|---|---|---|---|---|
| 1 | 建质检任务 API(入参、任务 ID 返回、优先级取值域) | `CreateBatchDevJobDef` + `BindSchedule` + `PublishDefinition` 的入参/返回;`jobDefId` 语义 | datagov Metadata + Control | `SchedulerAdapter`、1.2–1.4 上线环节 |
| 2 | 数据权限粒度(库 / 表 / 行列级) | `EvaluateAccess` 的粒度与 `resolve_user_scope` 的 `dataScope.type` 取值域 | datagov Governance + Platform | Capability 契约签名(行列级会改签名) |
| 4 | OA 身份对接协议(OIDC / CAS / LDAP) | 院方统一认证协议 + UserContext 透传格式 | datagov Platform(与客户) | `IdentityAdapter`、验收 3.1/3.2 |

(编号沿用 dataQA `decision.md` 待澄清清单 6 项;#3 与 datagov 无关、#6 属基础设施选型,略。**#5「datagov 元数据服务是否已有中文名索引,还是需 dataQA 自建」是 Phase 1 的数据可用性前置** —— 若无中文名索引,dataQA 需自建,影响中文召回工作量,见 Phase 1。)

---

## G. Must-Not-Do(集成红线)

- ❌ datagov 不得同步调用 dataQA(要用质检结果就消费事件)——否则同步子图成环
- ❌ 跨系统不得共享数据库连接;dataQA 不得直连 datagov 的库,只走 Query API 或事件物化视图
- ❌ 不得新增第二个跨系统写入通道(唯一写入 = `create_quality_task`)
- ❌ 任何跨系统调用不得缺 UserContext(无服务账号),不得用统一服务账号「事后按用户过滤」
- ❌ 跨系统事件不得走进程内总线,必须走真实 Kafka(两边合并部署时最易被省,也最贵)
- ❌ L3+ 明文不得离开持有方;脱敏在 datagov 侧发生,dataQA 不落明文业务数据
- ❌ dataQA 不得绕过 datagov Metadata 直接向 Control/Runtime 建任务定义

---

## H. 与 GOVERNANCE-MODEL 原语的落地对应

| 原语 | 集成里由谁产出 / 承接 |
|---|---|
| `Asset`(结构) | datagov `GetSchemaTree`;dataQA Semantic 存为 structure fact |
| `Fact`(glossary/quality_convention) | dataQA Semantic(人工登记/抽取),datagov 不涉及 |
| `Standard`(codeset) | MVP 经 datagov 受控查询读 `ref_dw_cd_val`;长期 datagov 登记为 Standard |
| `Rule` | dataQA 产 RuleSpec 并编译;经 `create_quality_task` 落 datagov JobDef |
| `Policy` | datagov Governance `EvaluateAccess` 判定;分级驱动默认策略 |
| `Lineage` | datagov `GetLineage`/`GetJobDataSourceRelations`(目标设计,datagov 承接查询,dataQA 查询) |
| `Event` | Kafka `dg.*` topic;datagov 产出,dataQA 消费 |

---

## 附录

- 上游规范:`GOVERNANCE-MODEL.md`(治理元语);其 §G 是本文档的展开对象。
- datagov 接口来源:`SPACE-MODEL.md` §F(Command/Query/Event,目标设计)。
- dataQA 适配器来源:`~/projects/dataQA/architecture.md` §2(5 适配器 SPI)、`decision.md`(3 阻塞项)。
- 本文档定义接口契约与落地顺序,不含代码;Phase 0 的 OpenAPI/事件 schema 是第一个可交付产物。
