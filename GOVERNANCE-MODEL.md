# 数据治理元语 · Governance Model

> 输入:`SPACE-MODEL.md`(本平台 as-practiced 的过程模型)+ `~/projects/dataQA`(已完成 POC 的治理语义层与 Agent)
> 方法:抽出一层以 **Asset** 为中心的治理原语,把两套已存在且同构的元语缝合起来,而不是发明第三套
> 产出:7 个治理原语的定义、状态机、治理动作闭环、与两套现有元语的映射、与 dataQA 的对接边界、分级/Policy、Must-Not-Do、演进位、与 Palantir 的对照
> 状态:初版 · 待评审
> 日期:2026-09-16

---

## 目录

| 章节 | 内容 | 谁该读 |
|---|---|---|
| [0](#0-术语表先读这一节) | 7 原语术语表;与 Space/Workspace 的关系;**与 dataQA 术语映射** | 所有人 |
| [A](#a-定位一句话) | 系统定位、与 Palantir 的取舍 | 所有人 |
| [B](#b-为什么需要一层独立的治理元语) | 为什么过程元语补不出治理 —— R10 的正面回答 | 架构、甲方沟通 |
| [C](#c-七个核心原语) | **七原语的定义、字段、不变量、来源** | 领域建模、库表设计 |
| [D](#d-状态机) | Asset / Standard / Rule / Policy 四个生命周期 | 建模、测试用例 |
| [E](#e-治理动作闭环) | `Describe→Register→Compile→Execute→Observe→Learn` | 架构 |
| [F](#f-与两套现有元语的映射) | 与 datagov Metadata、dataQA Semantic 的对应 | 技术负责人 |
| [G](#g-与-dataqa-的对接边界) | **Contract + Event 清单,谁产出谁消费** | 技术负责人、集成 |
| [H](#h-分级分类与-policy) | 敏感分级→脱敏/访问,合规映射 | 合规、安全、甲方 |
| [I](#i-must-not-do治理红线) | **Must-Not-Do —— 可直接当 PR 评审清单** | 所有开发 |
| [J](#j-演进位本体层与-action) | 本体层与 Action 未来如何接入、与序号 34 的边界 | 架构、项目经理 |
| [K](#k-与-palantir-的对照) | 差异表 + 刻意不做 Action 写回的理由 | 架构、甲方沟通 |

**只有 15 分钟**:读 0 → B → C → G。这四节包含了会影响范围与集成排期的全部结论。

---

## 0. 术语表(先读这一节)

本文档定义的是**内容/对象层**的治理原语。它正交于两个已有的边界概念,三者一起才能定位一条数据:

| 概念 | 回答的问题 | 定义于 |
|---|---|---|
| **Workspace**(租户) | 这条数据归谁、在谁的授权边界内 | `SPACE-MODEL.md` §0 |
| **Space**(架构边界) | 这段逻辑属于哪个对象世界(结构边界) | `SPACE-MODEL.md` |
| **治理原语**(本文档) | 这条数据**是什么、该守什么规矩、谁能碰、从哪来** | 本文档 |

每个 Asset 归属一个 Workspace;治理原语作为**事实与约束**横切所有 Space —— 与 `SPACE-MODEL.md` 认定的「Governance 横切、Platform 作用域全部」一致。

### 七个原语

```text
Asset      被治理的对象。库/表/字段/指标/数据集/码集。有唯一 ref、分级、owner、生命周期。
Fact       关于某个 Asset 的一条断言。带 type / source / version —— 系统对该 Asset 的全部认知。
Standard   可登记、可引用、可校验的约定。码集/命名/口径/分级规则。
Rule       对 Asset 的可执行校验。质量规则 + 标准符合性规则。
Policy     谁能对哪一级 Asset 做什么。分级 → 访问/脱敏,是 EvaluateAccess 的依据。
Lineage    Asset 之间的 produced-by / derived-from。来源是执行事件,不靠手工登记。
Event      已发生的治理事实流。驱动元数据演进 M(t+1)=f(M(t),E(t)),是治理横切进编译输入的载体。
```

缩写沿用两项目:`C`=Command、`Q`=Query、`E`=Event。

### 与 dataQA 术语的映射(复用,不重造)

| 本文档原语 | dataQA 已有对应 | 关系 |
|---|---|---|
| `Asset`(结构侧) | `SchemaSnapshot` / `Fact(structure)` | Asset 的结构事实来自元数据快照,不重复登记 |
| `Fact` | `Fact { object_ref, fact_type, content, source }` | **直接扩展**:新增 `lineage`、`classification` 两个 fact_type |
| `Standard`(码集) | `CodeValueSet`(`ref_dw_cd_val` 快照) | 码集是 Standard 的一个子类 |
| `Rule` | `RuleSpec` DSL + `RuleTemplate` + `CompiledRule` | 原样复用,含三步编译 |
| `Policy`(判定) | `PolicyDecision` / `EvaluateAccess` | 原样复用判定路径 |
| 安全内核 | 三条不可协商约束 | 见本节末,贯穿全文 |

### 贯穿全文的安全内核(继承 dataQA 三约束)

1. **编译期边界**:LLM 只产结构化 `RuleSpec`,绝不产 SQL/DDL;SQL 由模板确定性渲染,编译过程在类型/依赖上接触不到模型输出。
2. **无服务账号**:任何 Capability 调用必须携带 `UserContext` 并透传外部系统;缺上下文在类型层面就写不出来。
3. **证据不足拒绝定论**:`INCONCLUSIVE` 是一等终态,不是失败态。

这三条不是口号 —— dataQA 的 `README` 记录了 DeepSeek V4 的真实案例:未登记质量约定的表被拒绝臆造码集编号,字段幻觉与码集幻觉被存在性闸门逐条拦下。本文档把它们提升为**整套治理元语的底座**,尤其针对医疗私有化场景。

> 命名提示:此处「三条不可协商约束」指编译期边界 / 无服务账号 / 证据不足;dataQA `design.md` §3.1 另有一组同名的「三条不可协商的规则」指规则审批的 confirm 绑版本 / CONFIRMED 不可变 / publish 二次校验 —— 两者所指不同,勿混。

---

## A. 定位(一句话)

> **在本平台已有的 DataOps 底座(Metadata Kernel + Workflow Compiler + Runtime Contract + 统一 Execution 事实)之上,补一层以 Asset 为中心的治理语义层 —— 使平台回答「数据是什么、该守什么规矩、谁能碰、从哪来」,而这一层的能力实现直接收编已完成 POC 的 dataQA,不另起炉灶。**

对 Palantir 的取舍先摆明:

- **借鉴**:语义层与物理层分离、治理内建于血缘、Agent 只能经受控接口行动。
- **刻意不做**:Palantir 式 **Action 写回业务对象**(kinetic ontology)。理由见 [K](#k-与-palantir-的对照) —— 医疗数据写回风险高、私有化运维不可控;dataQA 已用工程纪律验证「唯一写入点 + 无服务账号」的价值,datagov 侧则是「受控写入 + 全程留痕 + Metadata 唯一注册中心」。本期只留演进位,不实现。

---

## B. 为什么需要一层独立的治理元语

`SPACE-MODEL.md` 的**风险 R10** 已经写死了缺口:平台叫「数据治理平台」,但 35 项功能里不存在数据标准、数据质量规则、主数据、数据资产目录、数据分级分类 —— 当前功能集在工程实质上是 DataOps。

这不是补几个功能就能了事,而是**元语性质不同**:

| | 过程元语(已有) | 治理元语(本文档) |
|---|---|---|
| 中心 | `Execution` / `Task`(动词、事件) | `Asset`(名词、对象) |
| 回答 | 数据怎么流动、任务是否跑成 | 数据是什么、可信否、谁能碰、从哪来 |
| 优化目标 | 流动效率、调度正确 | 可信、合规、可追溯 |
| 时间性 | 一次执行是瞬时的 | 一条口径/一个分级是长期事实 |

**结论:过程元语无法通过增强补出治理元语,二者正交。** 你可以把一个任务调度得完美,却完全不知道它写出的那张表是不是敏感数据、口径对不对、下游是谁。治理元语提供的正是这个正交轴。

同时,`SPACE-MODEL.md` 的 `C+D.6` 已为 `QualityRule` / `QualityResult` **预留了对象位**,只是不在本期。本文档把这个预留位正式展开,并接上 dataQA 的现成实现。

---

## C. 七个核心原语

**原语的存储与投影(先定这条,消除下面三处重叠)**:`Fact` 是统一存储层。`Lineage` 与 `classification` 逻辑上是原语,物理上以带附加结构的 Fact 存储(`lineage` fact / `classification` fact),其 schema 即对应 fact 的 `content` 结构。`Asset` 本体上的 `classification` 字段是「最新 classification fact 的只读投影」,不是独立真相。`codeset` 出现在两处且不冲突:物理码表是 `Asset(type=codeset)`(存在这么一张表),其权威取值域约定是 `Standard(type=codeset)`(这个码集该有哪些值)。

### C.1 Asset —— 被治理的对象

```text
Asset {
  ref          : { workspace_id, type, qualified_name }   # 唯一标识
  type         : catalog | database | table | column | metric | dataset | codeset
  classification : L1 | L2 | L3 | L4                       # 最新 classification fact 的只读投影,见 H
  owner        : steward_ref                                # 数据责任人
  lifecycle    : DISCOVERED | REGISTERED | CERTIFIED | DEPRECATED
  〔预留〕business_object : ObjectTypeRef                    # 本体层,本期不实现,见 J
}
```

**不变量**:

- Asset **只登记「是什么」,绝不持有数据内容**。它是目录/元数据,不是数据副本。
- Asset 的**结构**(表/字段/类型)来自 datagov 的 `SchemaSnapshot`,由元数据发现自动产生,**不手工重复登记**。
- 一个 Asset 的全部「认知」以 `Fact` 挂载;Asset 本体只保留标识、owner、生命周期这三类稳定字段。`classification` 是最新 classification fact 的投影(见本节开头),便于按分级快速筛选,不构成独立真相。

### C.2 Fact —— 关于 Asset 的一条断言

```text
Fact {
  asset_ref    : Asset.ref
  fact_type    : structure | glossary | quality_convention | case | lineage | classification
  content      : <结构化内容,随 type>
  source       : metadata_snapshot | human_register | doc_extract | attribution | execution_event
  version      : int
  observed_at  : timestamp
  confidence   : 0..1        # 抽取类事实带置信度;人工登记为 1.0
}
```

这是 dataQA `Fact` 模型的**直接扩展** —— 原有 `structure/glossary/quality_convention/case` 四类之上,新增 `lineage`(血缘也是关于 Asset 的一条事实)与 `classification`(分级也是一条事实)。

**统一事实模型不变量**(继承 dataQA):

- 六类 fact **同一 schema、同一检索入口**。不得为某一类 fact 或某个消费方拆出第二个检索面 —— 拆分的直接代价是多套存储、多次召回、多处不一致。
- 未命中时必须告知「查不到」,**不得返回「最像的那个」而不声明是猜的**。

**来源分层**(谁产生这条 fact 决定它可信到什么程度):

| fact_type | 主要来源 | 产生方式 |
|---|---|---|
| `structure` | 元数据服务快照 | 机器,自动 |
| `glossary` / `quality_convention` | 人工登记 / 文档抽取 | 人 或 抽取(带 confidence) |
| `classification` | 分级规则自动打标 + 人工复核 | 机器初判 + 人确认 |
| `lineage` | Execution 事件 | 机器,自动派生(见 C.6) |
| `case` | 归因结果沉淀 | Learn 环节回写(见 E) |

### C.3 Standard —— 可登记、可引用、可校验的约定

```text
Standard {
  id, type : codeset | naming | glossary_def | classification_rule
  content  : <随 type,如码集取值域 / 命名正则 / 指标口径 / 分级判定规则>
  scope    : 适用范围(workspace / 库 / 主题域)
  state    : DRAFT | REGISTERED | ACTIVE | SUPERSEDED
  version  : int
}
```

Standard 是**数据标准**的落点,dataQA 的 `CodeValueSet` 是它的一个子类。

**核心不变量 ——「登记了口径就查得到,没登记就该被挡下」**(dataQA 已验证的核心断言):

> 当一条 `Rule` 依赖某个 `Standard`(如有效性规则引用某码集)而该 Standard 未登记时,Rule 编译的**存在性闸门必须失败并说明原因**,绝不允许「猜一个码集编号」。dataQA 的存在性闸门在 `design.md` §2.4 定义为校验表名/字段名;其 `README` 的 DeepSeek V4 真实案例进一步显示码集缺失(`crrc_cd` 在 `ref_dw_cd_val` 中无取值)同样被逐条拦下。本文档把「依赖的 Standard 未登记即编译失败」明确为该机制在 Standard 层的强制化。

### C.4 Rule —— 对 Asset 的可执行校验

```text
Rule {
  kind : quality | conformance
  spec : RuleSpec { dimension, table, columns[], period, priority, params, alertCondition }
         # quality: dimension ∈ uniqueness | consistency | validity | accuracy   (复用 dataQA)
         # conformance: 校验某 Asset 是否符合某 Standard
  compiled : CompiledRule { sql, validation }    # 由模板确定性渲染
  version  : int
  approval : ApprovalRef                          # 审批态由独立 Approval 对象承载(复用 dataQA §3.1),见 D
}
```

**三步编译,全过才产出**(原样复用 dataQA Control):

```text
RuleSpec
  ① 存在性闸门   表/字段/所引 Standard 必须在 Asset 与 Standard 库中真实存在 —— 拦幻觉
  ② 模板渲染     按 dimension 选模板填参数 —— 确定性,无生成式自由度
  ③ 语法校验     Parser 校验渲染结果 —— 拦方言错误
→ CompiledRule
```

**编译期边界**:编译过程在依赖上看不见模型模块(dataQA 的 `dq-control` 在 pom 里看不见 `dq-agent`)。LLM 只产 `RuleSpec`,不产 SQL —— 这条不靠自觉,靠编译期依赖强制。

### C.5 Policy —— 谁能对哪一级 Asset 做什么

```text
Policy {
  subject       : user | role
  action        : read | write | export | create_rule | publish | ...
  asset_selector: by_classification(L3+) | by_scope(db/table) | by_tag
  effect        : allow | deny | mask        # mask = 脱敏返回
  condition     : <可选,如时间窗 / 用途>
  state         : DRAFT | ACTIVE | SUSPENDED | RETIRED    # 见 D
}
```

- **判定入口 `EvaluateAccess`**(复用 dataQA):由 Capability 拦截器**同步调用**。
- **以分级为默认输入**:分级决定默认策略(如「L3 敏感个人信息默认脱敏,除非显式授权」),见 [H](#h-分级分类与-policy)。
- **判定与执行分离**:判定归 Governance,拦截执行在 Capability。业务代码不得自行做权限决策。

### C.6 Lineage —— Asset 之间的来龙去脉

```text
Lineage {
  upstream_asset : Asset.ref
  downstream_asset : Asset.ref
  transform_ref  : 工作流/任务定义 ref
  run_ref        : Execution 事件 ref     # 证据:哪一次执行产生了这条边
}
```

**关键不变量:Lineage 不靠手工埋点,由 datagov 派生。** 来源是 JobDef(`SPACE-MODEL.md` 序号 8)的 `ReadsFrom`/`WritesTo` 读写边 + `ExecutionFinished` 执行事实确认(该事件携带 `ExecutionMetric` 行数,不含资产清单);治理层据此补一条 `lineage` fact。这与本原语 schema 中 `transform_ref`(JobDef)与 `run_ref`(执行)的区分一致。

三个用途,都直接服务已有需求:

- **影响分析**:改一个 Standard 或 schema,沿血缘算出影响哪些下游 Asset。
- **根因**:Lineage 原语**将为** dataQA 归因提供血缘上溯能力,定位上游故障(呼应其失败模式库 P1「上游字段缺失/结构变更」);dataQA 现状是靠日志信号 `SemanticException [Error 10004]` 命中 P1,不遍历血缘。
- **合规**:敏感数据(L3+)的流向追踪,满足 R8 的健康医疗数据留痕要求。

### C.7 Event —— 已发生的治理事实流

治理约束**横切进编译输入**,这是 `SPACE-MODEL.md` O 节洞察的延续:

```text
M(t+1) = f( M(t), E(t) )                      元数据/资产状态由事件驱动演进
P(t)   = Compile( D(t), M(t), C(t), R(t) )     C = 治理约束(Policy + Standard),是编译输入
```

> **Policy 与 Standard 出现在编译输入 `C` 的位置,不是执行后的检查。** 如果治理只在事后检查,它就退化成一个报表模块。质量规则编译时校验 Standard(存在性闸门)、任务编译时校验 Policy(能不能写这张表),都发生在编译期。

治理事件清单:

```text
AssetRegistered  FactIngested  StandardRegistered
RuleProposed  RuleConfirmed  RulePublished  RulePublishFailed
PolicyEvaluated  AccessDenied
LineageDerived  CaseLearned
```

生产者 = 各治理动作;消费者 = Governance(监控/告警/审计)、Asset 状态机(状态回写)、dataQA Agent(缓存失效)。

---

## D. 状态机

**Asset 生命周期** —— 从「被发现」到「可信」:

```text
DISCOVERED ──register(定 owner+分级)──> REGISTERED ──口径与质量约定齐备──> CERTIFIED
   (元数据发现)                                                              │
                                                                    ──弃用──> DEPRECATED
```

`CERTIFIED` 的含义:该 Asset 有 owner、有分级、有口径(glossary)、有质量约定 —— 是「可被信任引用」的资产。这是资产目录的价值锚点。

**Standard**:`DRAFT → REGISTERED → ACTIVE → SUPERSEDED`(出新版本时旧版本转 SUPERSEDED,历史引用可追溯)。

**Rule**(原样复用 dataQA 规则审批,防「确认后被改 / 确认人无发布权 / 上线的不是被确认的那条」):

```text
DRAFT ──propose──> PROPOSED ──confirm(绑version)──> CONFIRMED ──publish──> PUBLISHED
                      │  ▲                              │
                 reject│  │modify(新版本,退回)          └──publish_failed──> FAILED(可重试)
                      ▼  │
                   REJECTED
```

**Policy**:`DRAFT → ACTIVE → SUSPENDED → RETIRED`。

---

## E. 治理动作闭环

把 datagov 的过程闭环与 dataQA 的认知闭环缝合,补上两者都缺的 **Register** 与 **Learn**:

```text
Describe/Retrieve  →  Register   →  Compile  →  Execute      →  Observe        →  Learn
   Asset/Fact          Standard      Rule        (datagov         Governance         case → Fact
   统一语义检索面        分级/口径      三步编译     Control+Runtime)   血缘/审计/告警      回沉 Asset
   〔dataQA Semantic〕  〔本文档新增〕 〔dataQA〕   〔datagov〕       〔两边 Governance〕 〔dataQA 雏形〕
```

- **Register 是本文档相对两项目新增的一环** —— 登记标准、口径、分级。没有它,「登记了才查得到」这条不变量无处落地。
- **Learn** 把归因产生的 `case` 写回成 Asset 的 fact,下次同类失败可直接命中规则分类器(dataQA 已有 case fact,是雏形)。
- Compile/Execute/Observe 全部复用现成实现,不重写。

对照两项目原文的核心元语闭环:

| datagov(O 节) | dataQA(§7) | 本文档 |
|---|---|---|
| Describe → Metadata | Retrieve → Semantic | Describe/Retrieve(合并) |
| — | Decide → Agent | (决策归 dataQA Agent,本层不含) |
| — | — | **Register(新增)** |
| Plan → Control | Compile → Control | Compile |
| Execute → Runtime | Execute → Capability | Execute |
| Govern → Governance | Observe → Governance | Observe |
| (Learn → Intelligence,后续) | (case fact) | **Learn(明确成环)** |

---

## F. 与两套现有元语的映射

| 本文档原语 | datagovernance(过程侧) | dataQA(治理侧) |
|---|---|---|
| `Asset`(结构) | Metadata 的数据源/表/字段定义 | `SchemaSnapshot` |
| `Fact(glossary/quality_convention)` | — (缺) | `Fact` |
| `Standard(码集)` | — (缺) | `CodeValueSet` |
| `Rule` | R6 的 Rule DSL(同步规则,狭义) | `RuleSpec` + 三步编译(广义,含质量) |
| `Policy` | Governance 的权限(菜单/数据权限) | `PolicyDecision` / `EvaluateAccess` |
| `Lineage` | JobDef 读写边(序号8)+ 执行事实(**来源**) | 归因**将**沿血缘上溯(现状靠日志信号) |
| `Event` | 统一 Execution 事实模型 | Trace / ToolCall / 各 Space Event |

**读法**:datagov 强在**结构与执行**(Asset 结构、Lineage 来源、Execution 事实),dataQA 强在**语义与质量**(口径、码集、质量规则、归因)。本文档的 Asset 把两者收在同一个对象身上 —— 一张表的「长什么样」来自 datagov,「口径/质量约定/分级/失败案例」来自 dataQA,是**同一 Asset 的不同 Fact**。

---

## G. 与 dataQA 的对接边界

**分工(最干净的切法)**:

> `datagovernance` = DataOps 底座(Metadata / Control / Runtime / 统一 Execution 事实)
> `dataQA` = 治理语义层 + Agent(Semantic / Rule 编译 / 质检 / 归因 / 问答)
> 两者经 **Capability Contract + Event** 对接,不共享数据库。

**这个边界不是硬造的** —— dataQA 的适配器目标本来就是「打到调度/元数据平台」,而那个平台就是 datagov:

| dataQA 适配器 | 目标系统 | 对接到 datagov 的 |
|---|---|---|
| `MetadataAdapter`(只读) | 元数据服务 | **Metadata / SchemaSnapshot** |
| `SchedulerAdapter`(唯一写入) | 调度平台 | 建质检任务定义经 **Metadata**(唯一注册中心)→ **Control** 编译调度 → **Runtime** 执行;执行历史读 **Runtime** |
| `LogAdapter` / `CodeAdapter`(只读) | 任务日志/代码 | **Runtime / Execution** |
| `IdentityAdapter`(只读) | OA 身份 / 数据权限 | **Platform**(承载 `UserContext` 与作用域数据 `DataScopeBinding`);判定由 **Governance** `EvaluateAccess` 执行 |

**接口清单**:

```text
datagov → dataQA   (Query + Event,只读)
  Q  GetSchemaSnapshot(assetRef)           → Asset 的 structure fact 来源
  Q  GetJobReadWrite(jobDefId)             → Lineage 的读写边(序号 8)
  Q  GetExecutionHistory / GetTaskLog      → 归因输入
  E  ExecutionFinished(ExecutionMetric)    → 血缘确认(读写边来自 JobDef,非事件载荷)+ 缓存失效
  E  SchemaDrifted                         → structure fact 更新

dataQA → datagov   (Capability Contract,唯一写入)
  C  CreateQualityTask(ruleSpec, schedule) → 经 datagov Metadata 定义入口创建质检 JobDef,
                                             再由 Control 编译调度、Runtime 执行;携带 UserContext
```

**两条不变量**:

- dataQA 对 datagov 的**唯一写入点**是 `CreateQualityTask`。不得新增第二个跨系统写入通道。
- 所有跨系统调用**必须携带 `UserContext`**;datagov 侧按同一身份做数据权限判定。无服务账号旁路。

---

## H. 分级分类与 Policy

医疗数据属敏感个人信息(`SPACE-MODEL.md` R8),分级分类是合规刚需,也是 DAMA 治理核心。

**分级体系**(对齐《数据安全法》「重要数据」与《个人信息保护法》「敏感个人信息」):

| 级 | 含义 | 默认 Policy |
|---|---|---|
| L1 | 公开 | 可读可导出 |
| L2 | 内部 | 授权可读 |
| L3 | 敏感个人信息(诊疗/身份/生物特征) | **默认脱敏**,导出需显式授权 + 留痕 |
| L4 | 重要数据 | 最小授权,禁止导出,全程留痕 |

**分级如何落到原语**:

- 分级是 Asset 的一条 `classification` fact,由 `classification_rule`(一种 Standard)自动初判 + 人工复核。
- 分级是 Policy 的**默认输入**:`asset_selector: by_classification(L3+)` 直接映射到脱敏/最小授权,不必逐表配。
- 敏感数据流向靠 `Lineage` 追踪(L3+ 数据流到哪个下游 Asset 一目了然)。

**合规能力对照**(R8 已提要求,这里给元语支撑):脱敏 = Policy `effect: mask`;授权 = Policy + 二次校验;留痕 = `AuditRecord`(Observe);流向 = Lineage。

---

## I. Must-Not-Do(治理红线)

每条违反都意味着一次职责泄漏或一个治理漏洞。可直接当 PR 检查表。

**Asset / Fact**
- ❌ Asset 不得持有任何数据内容,它是目录不是副本
- ❌ 不得为某类 fact 或某个消费方拆出第二个检索面
- ❌ 未命中时不得返回「最像的那个」而不声明是猜测

**Standard / Rule**
- ❌ 依赖的 Standard 未登记时,不得放行 Rule 编译(不得猜码集/口径)
- ❌ 编译过程不得调用任何模型;LLM 不得产出 SQL/DDL
- ❌ 存在性闸门/语法校验未过,不得渲染或提交
- ❌ `CONFIRMED` 的 RuleSpec 不得被原地修改(改则新版本退回)

**Policy / 安全内核**
- ❌ 权限判定不得在 Capability/Agent/前端实现(判定归 Governance)
- ❌ 任何 Capability 调用不得缺 `UserContext`(无服务账号)
- ❌ L3+ Asset 不得默认明文返回(默认脱敏)
- ❌ 归因证据不足时不得给出根因结论(`INCONCLUSIVE` 是合法终态)

**Lineage / Governance**
- ❌ Lineage 不得靠业务代码手工埋点(从 Execution 事件派生)
- ❌ Governance 不得向任何业务 Space 发 Command;需干预时发事件,由人决定
- ❌ 跨 datagov/dataQA 不得新增第二个写入点

---

## J. 演进位(本体层与 Action)

本期到 Asset 为止。两个演进位在元语上**预留但不实现**:

**本体层(BusinessObject)** —— Asset 的一个子类型,把跨表业务实体(如「患者」「就诊」「客户」)显式建模:

```text
BusinessObject <: Asset
  Property  ← 由若干 column Asset 的 Fact 聚合
  Link      ← 由 Lineage / 外键关系派生
```

映射关系已经想清楚:Object→Asset、Property→Fact、Link→Lineage。届时不改原语,只加子类型。

**与序号 34(OWL 医学概念体系)的边界**:序号 34 是**医学领域本体**(已独立立项),提供「概念」;本元语的本体位是**数据资产的业务对象视图**,提供「数据映射」。二者若未来合并,34 出概念、本元语出映射,**不重复造本体**。

**Action(写回业务对象)** —— Palantir 式 kinetic ontology 的核心,本期明确不做(理由见 K)。若未来要做,其写入目标是**业务对象**(非 datagov/dataQA 之间的集成写入通道),仍受 Policy + 审批 + `UserContext` 三重约束;届时「唯一写入点」不变量需就业务写回单独界定 ——「受控」与「唯一」是两件事,不可混为一谈。

---

## K. 与 Palantir 的对照

Palantir Foundry/AIP 的中枢是 **Ontology**:物理层(datasets + pipelines + lineage)之上抽象出 `ObjectType / Property / Link / Action / Function`,治理、分析、Agent 行动全部发生在对象层;`Action` 让本体可写回、可驱动现实业务(kinetic ontology),AIP 的 LLM Agent 只能经 Action 与权限行动。

| 维度 | Palantir | 本平台(datagov + dataQA + 本元语) |
|---|---|---|
| 治理中心 | 业务对象(Ontology) | **Asset 目录**(本期);本体层留演进位 |
| 语义/物理分层 | 有,Ontology 覆盖物理层 | 有,Asset/Fact 覆盖 Metadata/Execution |
| 治理位置 | 内建于本体+血缘 | 横切进**编译输入 C**(Policy/Standard 编译期生效) |
| 写回/行动 | **Action 一等公民**,驱动业务 | **刻意不做**,唯一写入=建质检任务 |
| Agent | AIP 挂本体,经 Action 行动 | dataQA 挂 Capability,经 Control 编译,**不产 SQL** |
| LLM 边界 | 相对开放 | **强约束**:LLM 只产 RuleSpec |
| 部署 | 一体化平台 | **私有化 / 信创**,组件解耦 |

**刻意不做 Action 写回的理由**(这是与 Palantir 最大的、也是有意的分歧):

1. **合规**:医疗数据由 Agent 驱动写回业务系统,在个保法/数据安全法下风险不可控。
2. **运维**:私有化交付,客户环境不可控,写回失败的爆炸半径大。
3. **已验证**:dataQA 用工程纪律守住「唯一写入点 + 无服务账号」——真实模型验证了防幻觉(README 的 DeepSeek V4 案例),防越权 / 无服务账号则由模型离线下的验收用例守住;datagov 侧以「受控写入 + 全程留痕 + Metadata 唯一注册中心」守同一目标。放开 Action 等于放弃这条已被证明有效的约束。

**一句话总结**:Palantir 以「对象」组织治理并让对象驱动业务;本平台以「Asset 目录 + 编译期约束」组织治理,并刻意让系统**克制写入**。借鉴其分层与受控 Agent,不采其 kinetic 写回 —— 这是由私有化医疗场景决定的,不是能力缺失。

---

## 附录:与现有文档的关系

- `SPACE-MODEL.md` —— 本平台过程模型(as-practiced)。本文档的 Asset 结构、Lineage 来源、Execution 事实均引用其定义,不重述。
- `~/projects/dataQA/design.md` —— 治理语义层与 Agent 的现成实现。本文档 Rule/Fact/Standard/Policy 的实现全部复用它,对接边界见 [G](#g-与-dataqa-的对接边界)。
- 本文档只定义**元语与边界**,不含库表 DDL 与接口签名 —— 那属于集成路线阶段(见对话中「目标形态」的选项二)。
