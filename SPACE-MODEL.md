# 数据治理平台 · Space Model

> 输入:35 项功能清单(数据集成 / 数据开发 / 运维监控 / 数据服务)
> 方法:`space-model-generation-skill.md` 第 4 节 Space 识别算法
> 产出:8 Space 的边界、对象、状态机、Contract、模块/存储/运行时映射、技术选型、演进路线与风险
> 状态:初版 · 待评审


---

## 目录

| 章节 | 内容 | 谁该读 |
|---|---|---|
| [0](#0-术语表先读这一节) | 术语表 —— **Space 与 Workspace 的区别,必读** | 所有人 |
| [A](#a-系统一句话定义) · [B](#b-space-数量与分类) | 系统定义、8 个 Space、为什么不能按菜单拆模块 | 所有人 |
| [C+D](#cd-每个-space-的-purpose-与-core-objects) | 每个 Space 的职责与核心对象 | 领域建模、库表设计 |
| [E](#e-state--lifecycle-model) | 8 个核心状态机 | 领域建模、测试用例设计 |
| [F](#f-command--query--event-model) | Command / Query / Event 清单 | API 设计、消息设计 |
| [G](#g-must-not-do代码评审清单) | **Must-Not-Do —— 可直接当 PR 评审清单** | 所有开发 |
| [H](#h-space-dependency-graph) | 依赖图、8×8 矩阵、同步子图无环 | 架构、部署顺序 |
| [I](#i-space--module-mapping) · [J](#j-space--storage-mapping) · [K](#k-space--runtime-mapping) | 模块 / 存储 / 运行时映射;**I.4 模块边界 ≠ 进程边界** | 技术负责人 |
| [L](#l-technology-selection) | **L.0 语言选型(Java 为主)**、技术选型(开源 + 国产化双列)、连接器矩阵 | 技术负责人、采购 |
| [M](#m-mvp--evolution-path) | 5 阶段演进路线与完成判据 | 项目经理 |
| [N](#n-architecture-risks) | **10 条架构风险与优先级** | 项目经理、甲方沟通 |
| [O](#o-核心元语闭环) · [P](#p-最终判定) | 核心闭环与最终判定 | 架构 |
| [Q](#q-模型自检) | 端到端走查、反例检查、自动化校验 | 评审 |

**只有 15 分钟的话**:读 0 → B.1 → N → M。这四节包含了全部会影响排期与合同的结论。

---

## 0. 术语表(先读这一节)

平台内有一个叫「空间」的功能(序号 28),与本文档的建模单位「Space」同名但**完全不是一回事**。全文严格区分:

| 术语 | 含义 | 出现位置 |
|---|---|---|
| **Space**(空间模型) | 架构边界。系统中围绕一类核心对象、状态、规则与生命周期形成的相对自治的软件世界。本文档共 8 个。 | 仅本文档 |
| **Workspace**(工作空间) | 平台内的**租户**。序号 28「空间管理」所管理的对象,拥有名称/标识/鉴权密钥/授权用户绑定,是平台内每个业务对象的顶层归属键。 | 序号 28,以及所有业务表的 `workspace_id` 字段 |

**代码约定**:类名/表名/字段名一律使用 `Workspace`,禁止使用 `Space` 命名任何运行时对象。`Space` 只作为架构文档与模块划分的词汇存在。

其他缩写:

```text
C  = Command   请系统做某件事(改变状态)
Q  = Query     告诉我当前是什么状态(不改变状态)
E  = Event     某件事已经发生(广播事实)
R/W= Data Read/Write  跨 Space 的实际数据读写
```

---

## A. 系统一句话定义

> **一个面向医疗机构私有化部署场景、以 Workspace 为租户隔离边界、以 Metadata Kernel 为唯一真相来源、以 Workflow Compiler 为控制中枢、以 Flink + Kubernetes 为统一执行底座的数据集成与数据开发平台;向上提供多源异构数据接入与同步、Flink SQL 实时/离线开发与可视化工作流编排、跨模块统一的任务监控告警与操作审计,并为后续的医学高质量数据集制备预留语义与数据集契约。**

这句定义有两处**刻意的措辞**,请评审时确认:

1. 称它为「数据集成与数据开发平台」而非「数据治理平台」。理由见 **风险 R10** —— 35 项功能中不存在数据标准、数据质量规则、主数据、数据资产目录、数据分级分类等经典数据治理能力,当前功能集在工程实质上是 DataOps 平台。
2. 「为后续的医学高质量数据集制备**预留契约**」而非「提供」。序号 34 已确认独立立项,本文档只锁定其与平台之间的边界。

---

## B. Space 数量与分类

### B.1 为什么不能按功能菜单拆模块

功能清单是按**菜单**组织的。直接把菜单当模块,会产生两个可以从需求文本本身证明的错误:

**证明一:「基础配置」菜单横跨三个 Space。**
序号 28-33 同处一个菜单,归属却完全不同:

| 序号 | 功能 | 真实归属 | 为什么 |
|---|---|---|---|
| 28 | 空间管理 | Platform/Tenancy | 租户与授权边界 |
| 29 | 角色管理 | Platform/Tenancy | 身份与菜单权限 |
| 30 | 用户管理 | Platform/Tenancy | 身份 |
| 31 | 执行器管理 | **Runtime** | 「管理 Flink 执行器,执行 Flink 任务时自动分配」——这是执行资源池调度,不是配置项 |
| 32 | 文件管理 | **Runtime** | 「平台级 JAR」——这是作业制品仓库,被 Flink 提交流程消费 |
| 33 | 告警渠道 | **Governance** | 通知通道,服务于告警规则(25)与告警信息(26) |

按菜单建模会得到一个杂物袋模块,并把 Runtime 的资源管理错置进配置层。

**证明二:五处「执行记录」必须收敛成一个模型。**
序号 10、15、19、21、23 是五个模块各自的执行记录。而:

- 序号 24 要求「支持**数据集成、数据开发模块**的任务监控统计」
- 序号 25 要求「支持**数据集成、数据开发**任务告警规则配置」
- 序号 27 要求「支持**数据集成、数据开发**模块的详细操作审计日志」

三条需求各自独立地要求跨子系统聚合。若每个模块各建一张执行记录表,这三项都做不出来。

> **结论:`Execution`、`Alert`、`AuditRecord` 是平台级统一事实模型,不属于任何单个业务模块。** 由 Runtime 产生,由 Governance 消费。这是整个架构中优先级最高的一条约束。

### B.2 Space vs Layer

Layer 回答「请求处于哪个技术层级」(Presentation / Application / Domain / Infrastructure)。
Space 回答「系统正在管理哪一个对象世界」。

一个 Space 跨越多个 Layer,一个 Layer 服务多个 Space。**先定 Space,再映射 Layer**,顺序不可反。

### B.3 八个 Space

**纵向主链路** `UI → Metadata → Control → Runtime → Data`

| Space | 一句话职责 | Owner 的功能项 |
|---|---|---|
| **UI / Experience** | 用户对平台能力的感知、配置与结果呈现;是投影,不是真相来源 | 全部 35 项的界面侧 |
| **Metadata / Semantic Kernel** | 系统里有什么、是什么、如何关联、当前什么状态、哪个版本 | 1-5、7(Schema 侧)、8、9、11-14、16、17(定义侧)、18、20、22 的**定义态** |
| **Control / Workflow Compiler** | 把逻辑定义编译为可执行物理计划,并负责调度、依赖解析、条件求值、资源选择 | 6(周期探测调度)、9/11/14/20/22 的**编译与调度** |
| **Runtime / Execution** | 真正执行已批准下发的物理计划,并回报状态与指标 | 10、15、19、21、23 的**执行态** + 31 执行器 + 32 制品 + 17 的规则解释器 |
| **Data** | 平台连接、读取、写入与承载的数据资源与连接器矩阵 | 1-4 连接器、6 探测执行、7 查询执行、12 文件解析、13 API 解析、35 外部对接 |

**横向能力空间**

| Space | 一句话职责 | Owner 的功能项 |
|---|---|---|
| **Governance / Trust** | 谁可以做、做了什么、为什么失败、告警给谁、数据从哪来 | 24、25、26、27、33 |
| **Platform / Tenancy** | Workspace 租户隔离、身份、角色菜单权限、凭据密钥托管 | 28、29、30(+ 序号 4 的接口认证凭据托管) |
| **Intelligence**(本次仅定 Contract) | 从结构化集成延伸到医学语义、标注、数据集与模型生命周期 | 34(+ 35 的消费侧) |

### B.4 为什么 Intelligence 在本系统是纵向域而非横切关注点

技能文档的示例把 Intelligence 画成横向能力空间。**对本系统不成立**:

- 横切的判据是「约束多个业务空间」。Governance 满足:每一次执行都要被监控、告警、审计;每一次操作都要被授权。
- Intelligence 不满足:数据集成与数据开发的任何一项功能(序号 1-33)都不依赖 Intelligence。它是**单向消费者** —— 消费平台产出的数据与执行能力,反向不成立。

因此本文档把 Intelligence 画为**架在平台之上的纵向业务域**。这个判断直接决定了 MVP 顺序(它可以最后建,且可以独立立项)与部署边界(独立仓库、独立存储)。

### B.5 为什么 Platform/Tenancy 值得独立成 Space

按技能第 5 节判定准则打分:

| 准则 | 是否满足 | 说明 |
|---|---|---|
| A 对象内聚 | ✅ | Workspace / User / Role / Grant / Credential 是明确的一等公民 |
| B 生命周期内聚 | ✅ | 启用/停用/重置/密钥轮换,与业务对象生命周期完全无关 |
| C 规则内聚 | ✅ | 租户隔离规则、菜单权限规则自成体系 |
| D API 内聚 | ✅ | 可定义独立的身份与租户 Contract |
| E 存储内聚 | ✅ | **必须独立** —— 它是授权判定的输入,与业务库同库会被绕过 |
| F 运行时内聚 | ➖ | 无特殊运行时需求 |
| G 变更内聚 | ✅ | 对接甲方统一认证时,变更不应波及业务模块 |

7 条满足 6 条。**决定性理由**:Workspace 是平台内每一个对象的顶层归属键。一个为所有其他 Space 定义数据作用域的概念如果不显式独立,每个 Space 都会各自发明一套租户模型,隔离必然出漏洞。

---

## C+D. 每个 Space 的 Purpose 与 Core Objects

> C(Purpose)与 D(Core Objects)按 Space 合并呈现,避免同一 Space 的信息被拆到两处。
> 每个 Space 的 must-not-do 汇总在 **G 章节**统一列表,便于当作代码评审清单使用。

### C+D.1 Platform / Tenancy Space

**Purpose**:回答「这是谁、他属于哪个 Workspace、他能看见哪些菜单、这条连接的口令在哪里」。它不理解任何业务语义,只提供身份、租户作用域与凭据托管。

**Core Objects**

| 对象 | 关键字段 | 来源 |
|---|---|---|
| `Workspace` | id, name, code(标识), description, status | 序号 28 |
| `WorkspaceSecret` | workspaceId, accessKey, secretKeyRef, rotatedAt | 序号 28「空间鉴权密钥管理」 |
| `WorkspaceGrant` | workspaceId, userId, grantedBy, grantedAt | 序号 28「授权访问用户的绑定和解绑」 |
| `User` | id, account, name, status, passwordRef | 序号 30 |
| `Role` | id, name, workspaceScope | 序号 29 |
| `MenuPermission` | roleId, menuCode, actions | 序号 29「菜单权限」 |
| `RoleAssignment` | userId, roleId, workspaceId | 序号 29/30 |
| `Credential` | id, type(NONE/BASIC/TOKEN/PASSWORD), secretRef, ownerWorkspaceId | 序号 4「无认证、基础认证、Token 认证」+ 序号 1-3 的数据库口令 |

**关键设计**:`Credential` 只在此 Space 存在。Metadata 中的 `DataSourceDef` 持有的是 `credentialRef`(一个不可解密的引用),明文只在 Runtime 提交作业时经注入通道解密一次。这条约束同时满足序号 28 的密钥管理与等保对凭据不落业务库的要求。

---

### C+D.2 Metadata / Semantic Kernel Space

**Purpose**:系统的**唯一真相来源**。回答「系统里有什么、它们是什么、彼此如何关联、当前是什么状态、是哪个版本」。所有定义态对象都在这里注册,没有第二个注册中心。

**Core Objects**

| 对象簇 | 对象 | 来源 |
|---|---|---|
| 数据源 | `DataSourceDef`(type / connectionProfile / credentialRef / status)、`DataSourceCatalogNode` | 1-5 |
| Schema | `Database`、`SchemaObj`、`Table`、`Column`、`View`、`SchemaSnapshot`、`SchemaVersion` | 7 |
| 规则 | `Rule`(CLEANSING / TRANSFORM)、`RuleParam` | 17 |
| 任务目录 | `TaskCatalogNode` | 16 |
| 集成定义 | `MigrationJobDef`(整库/选表、同构/异构、目标表命名、建表规则、扩展字段、DDL 覆写) | 9 |
| | `SyncJobDef`(源端库表或 SQL、过滤、清洗/转换规则引用、抽取策略与分片、写入策略、字段映射、增量策略、字段拼接) | 11 |
| | `FileParseJobDef`(CSV/JSON)、`ApiParseJobDef`(RestAPI) | 12, 13 |
| | `BatchSyncTemplate`(源表→目标表名映射,一键生成目标表) | 14 |
| 开发定义 | `StreamingDevJobDef`(Flink SQL)、`BatchDevJobDef`(Flink SQL + Cron) | 18, 20 |
| 编排定义 | `WorkflowDef`、`WorkflowNode`(FLINK_SQL / SQL / OFFLINE_SYNC / SHELL / PYTHON / HTTP)、`WorkflowEdge`、`ConditionNode` | 22 |
| 调度定义 | `ScheduleDef`(cron, timezone, 生效区间) | 11, 20, 22 |
| 关系 | `RelationEdge`、`LineageNode` | 8 |
| 版本 | `DefinitionVersion`(所有定义对象共用) | 全局 |

**核心关系**

```text
Workspace        ──Owns──────────>  * (所有对象)
DataSourceDef    ──Uses───────────>  Credential            (Platform)
Table            ──BelongsTo──────>  Database ──BelongsTo──> DataSourceDef
SyncJobDef       ──ReadsFrom──────>  DataSourceDef
SyncJobDef       ──WritesTo───────>  DataSourceDef
SyncJobDef       ──Applies────────>  Rule                   (引用,不内嵌)
WorkflowDef      ──Contains───────>  WorkflowNode
WorkflowNode     ──DependsOn──────>  WorkflowNode
WorkflowNode     ──References─────>  SyncJobDef | DevJobDef | ShellSpec | PythonSpec | HttpSpec
Execution        ──InstanceOf─────>  <任意 JobDef>          (Runtime 持有)
Column           ──MapsTo─────────>  MedicalConcept         (Intelligence,Contract 级)
```

序号 8「关联信息」正是 `SyncJobDef ──ReadsFrom/WritesTo──> DataSourceDef` 这两条边的查询视图。有了它,数据源的删除保护、影响分析与血缘起点都自然成立。

---

### C+D.3 Control / Workflow Compiler Space

**Purpose**:把用户定义的逻辑工作流编译成可执行物理计划,并负责触发、依赖解析、条件求值、资源选择与执行生命周期控制。它决定**如何执行**,但不亲自执行。

**Core Objects**

| 对象 | 说明 |
|---|---|
| `CompileRequest` / `CompileResult` | 编译入口与结果(含错误定位,供 UI 精确报错) |
| `LogicalPlan` | 定义经语义解析后的中间表示 |
| `PhysicalPlan` | 绑定了具体 Runtime、连接器、并行度、资源需求的可执行计划 |
| `ExecutionPlan` | 一次触发对应的执行计划(工作流为 DAG,单任务为单节点) |
| `DispatchCommand` | 下发给 Runtime 的指令 |
| `ScheduleTrigger` | Cron 解析与触发实例 |
| `BatchDependencyResolver` | 批任务依赖解析、补数(backfill)语义 |
| `StreamingSupervision` | 流任务保活策略(重启次数、退避、升级为告警的阈值) |
| `ConditionEvaluator` | 序号 22 的 Conditions 节点求值器 |
| `RetryPolicy` / `ResourceRequirement` | 随 DispatchCommand 下发 |
| `ConnectivityProbeSchedule` | 序号 6「开启或关闭周期连通性检查」的调度侧 |

**编译链**

```text
Definition (from Metadata)
      ↓  Schema 校验          源表/目标表字段是否存在、类型是否兼容
      ↓  依赖校验             引用的数据源是否 AVAILABLE、规则是否存在、JAR 是否已上传
      ↓  策略校验             调用 Governance 判定当前用户在该 Workspace 是否可发布/可执行
      ↓  DAG 校验             序号 22:是否有环、条件分支是否可达、是否有孤立节点
      ↓  类型/映射兼容性       序号 9/11 的字段映射与异构类型映射可行性
      ↓  计划优化             并行度、分片(序号 11 的源端分片策略)、写入批次
      ↓  Physical Plan
      ↓  Runtime Dispatch
```

**关键设计**:`ConditionNode` 的求值在 Control 完成,**不下发给 Runtime**。原因是条件分支属于业务语义,Runtime 只应知道「执行这个物理计划」,不应知道「如果失败则走另一条分支」。这条边界一旦破掉,工作流语义会散落进每一种 Runtime 适配器。

**Flink 不属于 Control Space**。Control 只产出「用哪个 Runtime、要多少资源」,提交细节属于 Runtime。

---

### C+D.4 Runtime / Execution Space

**Purpose**:真正执行 Control 批准并下发的物理计划,管理执行资源与作业制品,并把执行事实回报出去。它不理解业务语义,不做权限判定,不改元数据定义。

**Core Objects**

| 对象 | 关键字段 | 来源 |
|---|---|---|
| `Execution` | id, workspaceId, jobRefType, jobRefId, defVersion, triggerType, state, startedAt, endedAt | **统一模型**,覆盖 10/15/19/21/23 |
| `ExecutionAttempt` | executionId, attemptNo, state, errorRef | 重试语义 |
| `ExecutionMetric` | rowsRead, rowsWritten, bytes, latencyMs, lag | 序号 24 的统计口径来源 |
| `ExecutionLog` / `ErrorLog` | 结构化日志与异常日志 | 10/15/19/21/23 的「错误日志」「异常日志」 |
| `Executor` | id, type(FLINK), endpoint, capacity, load, health | 序号 31 |
| `ExecutorPool` / `ExecutorAssignment` | 自动分配的决策记录 | 序号 31「自动分配 Flink 执行器」 |
| `Artifact` | id, name, version, type(JAR), storageRef, scope(PLATFORM) | 序号 32「平台级 JAR」 |
| `FlinkJob` | flinkJobId, savepointRef, checkpointConfig | 序号 18/20 |
| `K8sJob` / `Pod` | Shell / Python 节点的载体 | 序号 22 |
| `RuleInterpreter` | 随作业分发的清洗/转换规则解释器 | 序号 17 的执行侧 |

**Execution 是全平台唯一的执行事实表**。`jobRefType` 取值覆盖 `MIGRATION / OFFLINE_SYNC / FILE_PARSE / API_PARSE / STREAMING_DEV / BATCH_DEV / WORKFLOW / WORKFLOW_NODE / CONNECTIVITY_PROBE`。五个模块的「执行记录」页面,是同一张表按 `jobRefType` 过滤后的不同视图。

---

### C+D.5 Data Space

**Purpose**:平台所连接、读取、写入与承载的数据资源。它只关心「怎么连上、怎么读、怎么写、Schema 长什么样」,不关心「什么时候读、该不该读」。

**Core Objects**

| 对象 | 说明 | 来源 |
|---|---|---|
| `Connector` | 按类型的驱动实现:MySQL / Oracle / SQLServer / PostgreSQL / DM8 / StarRocks / Doris / FTP / SFTP / RestAPI | 1-4 |
| `DataSourceConnection` | 实际连接与连接池 | 1-4 |
| `ConnectivityProbeResult` | 探测结果与耗时 | 6 |
| `SchemaDiscoveryResult` | 库 / 模式 / 表 / 字段 / 视图的逐层发现 | 7 |
| `QueryRequest` / `QueryResult` | 受控的自定义 SQL 查询与结果集 | 7 |
| `FileObject` | FTP/SFTP 上的 CSV / JSON 文件 | 3, 12 |
| `ApiEndpointResponse` | RestAPI 响应 | 4, 13 |
| `TypeMappingSpec` | 异构类型映射规范(源类型 → 目标类型) | 9 |
| `TargetTableDDL` | 生成的建表语句,支持预览与覆写 | 9「预览并修改建表语句」 |
| `ExternalPlatformAdapter` | HIS 平台对接适配器(泛型:后续可扩展 LIS / PACS / EMR) | 35 |

**关键设计**:序号 7 的「数据查询」是交互式直连业务库的能力,风险最高。必须由 Data Space 提供**受控查询服务**,强制三件事:语句超时、返回行数上限、每次查询写入审计。UI 绝不允许自行持有数据库连接。

序号 9 的「一键新建目标表」拆成两步:`GenerateTargetDDL`(Data 根据 TypeMappingSpec 生成)→ 用户预览修改 → `CreateTargetTable`(Data 执行)。DDL 生成规则属于 Data Space,因为它依赖各数据库方言;而「用哪个命名规则」属于 Metadata 的 `MigrationJobDef`。

---

### C+D.6 Governance / Trust Space

**Purpose**:回答「谁可以做、做了什么、为什么失败、该告警给谁、数据从哪里来」。它是唯一有资格横向约束所有其他 Space 的空间。

**Core Objects**

| 对象 | 关键字段 | 来源 |
|---|---|---|
| `MonitorAggregate` | 任务实例执行总数、失败数、今日新增抽取、总计抽取、任务时延 | 序号 24(字段口径直接来自需求原文) |
| `AlertRule` | scope(ALL_TASKS / SPECIFIED_TASKS)、targetJobIds、triggerCondition、channelIds、frequency | 序号 25 |
| `Alert` | ruleId, triggeredAt, content, state | 序号 26 |
| `AlertSuppression` | ruleId, windowStart, windowEnd | 序号 25「告警频率」的实现载体 |
| `AlertChannel` | id, type(EMAIL / ...), config, lastTestResult | 序号 33 |
| `AuditRecord` | actor, action, targetType, targetId, workspaceId, before, after, at, ip | 序号 27 |
| `PolicyDecision` | subject, action, resource, effect, reason | 授权判定 |
| `Provenance` / `LineageRecord` | 可信血缘记录(与 Metadata 的血缘本体分离) | 派生自 8 + 执行事实 |
| `SensitiveDataPolicy` | 脱敏与访问约束 | 序号 35 的合规要求 |

**血缘的归属划分**:血缘的**本体与结构**(哪些边、什么类型)归 Metadata;血缘的**可信记录**(某次执行确实从 A 读到 B、由谁触发、何时)归 Governance。前者是定义,后者是事实。

**功能清单未覆盖但建议预留**:`QualityRule` / `QualityResult`。见风险 R10 —— 35 项中没有任何数据质量检核功能,若甲方按数据治理平台的通常预期验收会出现缺口。此处仅预留对象位置,不在本期范围内。

---

### C+D.7 UI / Experience Space

**Purpose**:用户对平台能力的感知、配置、操作与结果呈现。它是系统状态的**投影**,不是真相来源。

**Core Objects**(前端侧,不落库)

```text
Workspace Switcher     当前租户上下文
Navigation / Menu      由 Platform 的 MenuPermission 驱动渲染
Form / Wizard          数据源创建、同步任务配置、字段映射
SchemaTree             序号 7 的库/模式/表/字段/视图逐层展开
SQL Editor             序号 7/18/20,含 SQL 格式化(纯前端能力)
Workflow Canvas        序号 22 的拖拽画布(节点、边、条件节点)
ExecutionRecordView    五处「执行记录」的统一组件,按 jobRefType 参数化
MonitorDashboard       序号 24
AlertCenter            序号 25/26
InteractionState       草稿、未保存变更、乐观更新
```

**关键设计**:五个模块的「执行记录」页面(10/15/19/21/23)在前端是**同一个组件**的五次参数化实例,不是五套代码。这是 B.1 证明二在 UI 层的直接推论。

---

### C+D.8 Intelligence Space(本期仅定 Contract)

**Purpose**:从结构化数据集成延伸到医学语义理解、知识图谱、标注、数据集制备与模型生命周期。**已确认独立立项**,本节只锁定对象边界与它对平台的依赖契约。

**Core Objects(边界定义,不展开内部实现)**

| 对象簇 | 对象 | 对应序号 34 原文 |
|---|---|---|
| 语义基石 | `Ontology`、`Concept`、`Term`、`ConceptLayer` | 「以 OWL 2 构建覆盖疾病、解剖、影像等七层医学概念体系」 |
| 语义映射 | `SemanticMapping`、`EntityLink`、`KnowledgeGraph` | 「实现知识抽取、融合与自动推理」 |
| 标注 | `PreAnnotationJob`、`AgentEnsemble`(3-5 个 SOTA 模型)、`Annotation`、`Label`、`HumanReview` | 「多智能体预标注工厂…流水线、并行、竞争等模式…人工审核协同」 |
| 数据飞轮 | `SeedAnnotation → TrainingRun → AutoAnnotation → HumanCorrection → DatasetExpansion → ModelIteration` | 「影像标注训练一体化工具…持续增强模型能力」 |
| 质控 | `QualityDimension`(标注/逻辑一致性/数据/模型 四维度)、`ActiveLearningStrategy`(不确定性采样)、`ErrorAnalysis` | 「AI 质量控制与进化引擎…主动学习策略,建立错误归因分析→模型改进→知识更新的反馈闭环」 |
| 产物 | `Dataset`、`DatasetVersion`、`Model`、`ModelVersion`、`Evaluation`、`Feedback` | 「输出数据集具备临床级可靠性」 |

**Intelligence 对平台的 Contract(本期必须锁定的四条)**

1. **取数**:Intelligence 不得直连业务数据源。原始数据必须通过平台的集成任务(序号 11-13)落地,Intelligence 只读消费产物。
2. **算力**:训练与预标注作业通过 Control 提交为 `PYTHON` / `K8S_JOB` 类型执行,复用统一 `Execution` 事实模型,自动获得监控(24)、告警(25)、审计(27)。
3. **注册**:`Dataset` / `Model` 的**标识与版本**注册进 Metadata Registry;**内容**(影像、标注文件、模型权重、本体文件)存对象存储。不建第二注册中心。
4. **语义**:`Column ──MapsTo──> Concept` 这条边写入 Metadata 的 `RelationEdge`,使医学语义可以被平台的血缘与影响分析看见。

---

## E. State / Lifecycle Model

八个核心状态机。每条迁移都标注触发它的 Command 或 Event —— 没有触发源的迁移是设计缺陷。

### E.1 `DataSourceDef`(序号 1-6)

```text
DRAFT ──TestConnectivity──> TESTING ──ConnectivitySucceeded──> AVAILABLE
  ^                            │
  └────ConnectivityFailed──────┘

AVAILABLE ──ProbeFailed(序号6 周期探测)──> UNREACHABLE ──ProbeSucceeded──> AVAILABLE
AVAILABLE | UNREACHABLE ──DisableDataSource──> DISABLED ──Archive──> ARCHIVED
```

- `UNREACHABLE` 进入时发 `DataSourceUnreachable` 事件 → Governance 按 AlertRule 决定是否告警。
- **删除保护**:被任何 JobDef 引用的数据源不允许删除,只允许 `DISABLED`。校验依据是序号 8 的关联信息。

### E.2 `JobDefinition`(定义态统一状态机,序号 9/11-14/18/20/22)

```text
DRAFT ──Submit──> [Control 编译校验]
                      ├──CompileFailed──> DRAFT(携带错误定位)
                      └──CompileSucceeded──> VALIDATED
VALIDATED ──Publish──> PUBLISHED
PUBLISHED ──BindSchedule/Enable──> SCHEDULING ⇄ PAUSED
PUBLISHED | SCHEDULING | PAUSED ──Offline──> OFFLINE ──Archive──> ARCHIVED
```

- 一次性任务(序号 9 整库迁移)不进入 `SCHEDULING`,由 `PUBLISHED ──RunOnce──> Execution`。
- 已发布定义的修改产生**新版本**,`SCHEDULING` 中的任务在下一次触发时才使用新版本;运行中的 Execution 始终绑定它启动时的 `defVersion`。

### E.3 `StreamingJob`(运行态,序号 18 —— 与批任务根本不同)

```text
PUBLISHED ──Start──> STARTING ──FlinkJobRunning──> RUNNING
RUNNING ──FlinkJobFailed──> RESTARTING ──(退避重启)──> RUNNING
RESTARTING ──超过保活阈值──> FAILED (发 AlertTriggered)
RUNNING ──Stop──> STOPPING ──(触发 savepoint)──> STOPPED
STOPPED ──Start(从 savepoint 恢复)──> STARTING
```

序号 18 明确要求「支持启动或停止实时开发任务」。流任务是**常驻**的,它的状态机围绕「保活」;批任务是**周期**的,状态机围绕「触发与完成」。用一套模型硬套两者,是 **风险 R5**。

### E.4 `Execution`(平台级统一执行实例,序号 10/15/19/21/23)

```text
PENDING ──Dispatch──> DISPATCHED ──ExecutionAccepted──> RUNNING
RUNNING ──ExecutionSucceeded──> SUCCEEDED
RUNNING ──ExecutionFailed──> FAILED ──Retry(按 RetryPolicy)──> 新 ExecutionAttempt
RUNNING ──Cancel(序号19/23 明确要求)──> CANCELING ──> CANCELED
RUNNING ──超时──> TIMEOUT
```

- 终态(`SUCCEEDED / FAILED / CANCELED / TIMEOUT`)一律发出 `ExecutionFinished` 事件,携带 `ExecutionMetric`。Governance 消费它同时驱动 **序号 24 监控** 与 **序号 25/26 告警**,两者共用一个事实源。
- `Retry` 产生新的 `ExecutionAttempt`,不新建 `Execution` —— 否则序号 24 的「执行总数」口径会被重试污染。

### E.5 `WorkflowExecution`(序号 22/23)

```text
PENDING ──> RUNNING ──> SUCCEEDED | FAILED | CANCELED
```

- DAG 内每个 `WorkflowNode` 对应一个独立 `Execution`(`jobRefType = WORKFLOW_NODE`),父子通过 `parentExecutionId` 关联。
- `ConditionNode` **不产生 Execution** —— 它在 Control 内求值,只决定下一跳走哪条边。
- 序号 23「取消执行中的任务」= 取消父 `WorkflowExecution` 并级联取消所有 `RUNNING` 子 Execution。

### E.6 `Alert`(序号 25/26)

```text
TRIGGERED ──(检查 AlertSuppression 窗口)──> NOTIFYING ──> NOTIFIED
                    └──在抑制窗口内──> SUPPRESSED
NOTIFYING ──渠道不可达──> NOTIFY_FAILED
NOTIFIED ──Acknowledge──> ACKNOWLEDGED ──> RESOLVED
NOTIFIED ──条件恢复──> RESOLVED
```

序号 25 的「告警频率」在实现上就是 `NOTIFIED` 之后的抑制窗口:窗口内同一 `AlertRule` 的同源告警进入 `SUPPRESSED`,仍记录但不推送。

### E.7 `Workspace`(序号 28)

```text
ACTIVE ⇄ SUSPENDED ──> ARCHIVED
```

**需要产品决策的一个点**:`Workspace` 停用时,该租户下 `SCHEDULING` 的任务应当暂停(明确);但**正在 `RUNNING` 的 Execution 是否强制取消**,需求未说明。建议默认「不强杀、不再触发新实例、界面标注停用中」,并把该行为写入验收口径。

### E.8 `Dataset`(Intelligence,Contract 级,序号 34)

```text
DRAFT ──> ANNOTATING ──> REVIEWING ──> QC_PASSED ──> PUBLISHED ──> FROZEN
```

`PUBLISHED` 后内容不可变,任何修改产生新的 `DatasetVersion`。`Model ──TrainedOn──> DatasetVersion` 必须指向 `FROZEN` 或 `PUBLISHED` 版本,保证训练可复现。

---

## F. Command / Query / Event Model

原则:**Command 改变状态,Query 读取状态,Event 广播事实,Data Contract 规定结构。**

### F.1 Platform / Tenancy

```text
Commands  CreateWorkspace, UpdateWorkspace, EnableWorkspace, SuspendWorkspace,
          RotateWorkspaceSecret, GrantWorkspaceAccess, RevokeWorkspaceAccess,
          CreateUser, UpdateUser, EnableUser, DisableUser, ResetPassword,
          CreateRole, UpdateRoleMenuPermissions, AssignRole,
          StoreCredential, RotateCredential

Queries   GetWorkspace, ListWorkspaces, ListWorkspaceMembers,
          GetUser, ListUsers, GetRole, GetEffectiveMenuPermissions,
          IssueCredentialInjectionHandle   ← 仅 Runtime 可调,返回一次性注入句柄而非明文

Events    WorkspaceCreated, WorkspaceSuspended, WorkspaceSecretRotated,
          UserDisabled, PasswordReset, RoleMenuPermissionsChanged,
          AccessGranted, AccessRevoked
```

### F.2 Metadata

```text
Commands  RegisterDataSource, UpdateDataSource, DisableDataSource,
          CreateDataSourceCatalogNode, MoveDataSourceToCatalog,
          RefreshSchema,
          CreateRule, UpdateRule, DeleteRule,
          CreateTaskCatalogNode,
          CreateMigrationJobDef, OverrideTargetDDL,
          CreateSyncJobDef, UpdateSyncJobDef, BatchCreateSyncJobDefs,
          CreateFileParseJobDef, CreateApiParseJobDef,
          CreateStreamingDevJobDef, CreateBatchDevJobDef,
          CreateWorkflowDef, UpdateWorkflowGraph,
          BindSchedule, PublishDefinition, OfflineDefinition

Queries   GetDataSource, ListDataSourcesByCatalog, GetSchemaTree,
          GetJobDef, ListJobsByCatalog, GetWorkflowGraph,
          GetJobDataSourceRelations   ← 序号 8
          GetLineage, GetDefinitionVersion, DiffDefinitionVersions

Events    DataSourceRegistered, DataSourceDisabled, SchemaRefreshed, SchemaDrifted,
          RuleChanged, JobDefPublished, JobDefOfflined, WorkflowPublished, ScheduleBound
```

`SchemaDrifted` 是一个**清单未要求但强烈建议**的事件:序号 7 支持查看库表结构,一旦源端 Schema 变化而同步任务的字段映射未更新,任务会在运行时失败。把漂移做成事件,可以让 Governance 提前告警而不是等执行失败。

### F.3 Control

```text
接收 Commands  CompileDefinition, TriggerNow, RunOnce, PauseSchedule, ResumeSchedule,
              CancelExecution, RetryExecution, Backfill,
              EnableConnectivityProbe, DisableConnectivityProbe   ← 序号 6

发出 Commands  DispatchExecution, StartStreamingJob, StopStreamingJob,
              CancelExecution, ProbeConnectivity        (目标:Runtime)

Queries       GetCompileResult, GetPhysicalPlan, GetScheduleState,
              ListUpcomingTriggers, GetDependencyGraph

Events        CompileSucceeded, CompileFailed, ScheduleTriggered,
              ExecutionDispatched, DispatchRejected, StreamingSupervisionEscalated
```

`DispatchRejected`(无可用执行器 / 配额不足)必须是一个显式事件。否则任务「没跑」这件事会静默丢失,序号 24 的监控看不见它。

### F.4 Runtime

```text
接收 Commands  DispatchExecution, CancelExecution, StartStreamingJob, StopStreamingJob,
              ProbeConnectivity, RegisterExecutor, DrainExecutor,
              UploadArtifact, DeleteArtifact

Queries       GetExecution, ListExecutions, GetExecutionLog, GetExecutionAttempts,
              ListExecutors, GetExecutorLoad, ListArtifacts

Events        ExecutionAccepted, ExecutionStarted, ExecutionProgressed,
              ExecutionSucceeded, ExecutionFailed, ExecutionCanceled, ExecutionTimedOut,
              StreamingJobRestarted, ExecutorRegistered, ExecutorUnhealthy,
              ArtifactUploaded, ConnectivityProbeCompleted
```

`ExecutionProgressed` 携带 `rowsRead / rowsWritten / latencyMs / lag`,是序号 24「今日新增抽取、总计抽取、任务时延」的唯一数据来源。

### F.5 Data

```text
Commands  OpenConnection, CreateTargetTable, ReadBatch, WriteBatch, ParseFile, FetchApi
          （均由 Runtime 调用;这些是唯一真正改变外部世界状态的操作）

Queries   TestConnectivity, ExecuteQuery, DiscoverSchema, GenerateTargetDDL,
          PreviewData, GetSchemaFromSource, GetTypeMapping, ListFiles
          （不改变任何状态。序号 6 的测试结果、序号 7 的查询结果、序号 9 的 DDL 预览
            都只是"读世界",数据源状态的改变发生在 Metadata 侧）

Events    ConnectivityTested, SchemaDiscovered, TargetTableCreated,
          ExternalPlatformSyncCompleted   ← 序号 35
```

Data Space 以同步 Command/Query 为主,自身几乎没有独立生命周期,因此事件很少 —— 这正是它**不该被升格为业务空间**、只作为能力空间存在的证据。

### F.6 Governance

```text
Commands  CreateAlertRule, UpdateAlertRule, EnableAlertRule, DisableAlertRule,
          CreateAlertChannel, TestAlertChannel,        ← 序号 33 连通性测试
          AcknowledgeAlert, ResolveAlert

Queries   GetMonitorDashboard,                          ← 序号 24
          ListTodayAlerts, ListHistoricalAlerts, GetAlertDetail,   ← 序号 26
          SearchAuditLog,                               ← 序号 27
          EvaluateAccess, GetLineageProvenance

Events    AlertTriggered, AlertNotified, AlertNotifyFailed, AlertSuppressed,
          AlertResolved, AuditRecorded, AccessDenied, SensitiveDataAccessed,
          WorkspaceSuspensionOrdered   ← 由 Control 消费后暂停该租户的调度
```

**Governance 对其他 Space 只发事件,不发命令。** 需要停掉某个租户的任务时,它发出 `WorkspaceSuspensionOrdered`,由 Control 消费后执行暂停 —— 而不是直接命令 Control。

这不是措辞讲究,而是为了**保持依赖图无环**:Control 在编译期同步查询 Governance 做策略校验(`Control → Governance`),如果 Governance 再反向命令 Control,两者就形成循环依赖,部署顺序、故障隔离与测试边界都会失效。改成事件后,Governance 的出向依赖里不再有 Control。

### F.7 UI

UI 只发出其他 Space 的 Command 与 Query,**不定义自己的领域 Command,不产生领域 Event**。它唯一的本地状态是交互状态(草稿、未保存变更、画布布局)。

### F.8 Intelligence(Contract 级)

```text
Commands  ImportOntology, MapColumnToConcept, RunPreAnnotation, SubmitHumanReview,
          PublishDatasetVersion, StartTrainingRun, PublishModelVersion, SubmitFeedback

Queries   SearchConcept, GetSemanticMapping, GetDatasetVersion, GetModelEvaluation

Events    OntologyPublished, SemanticMappingChanged, DatasetVersionPublished,
          ModelVersionPublished, EvaluationCompleted, FeedbackIngested

消费       ExecutionSucceeded(平台)、SchemaRefreshed(平台)
```

---

## G. Must-Not-Do(代码评审清单)

这一节可以直接当作 PR 评审的检查表使用。每条违反都意味着一次职责泄漏。

### UI / Experience

- ❌ 不得直接调用 Flink REST API 或 Kubernetes API
- ❌ 不得直接持有业务数据源连接。序号 7 的数据查询必须经 Data Space 的受控查询服务(超时 + 行数上限 + 审计)
- ❌ 不得绕过 Metadata 直接创建任务
- ❌ 不得在前端实现清洗/转换规则语义(序号 17)。SQL 格式化是纯展示能力,可以在前端;规则语义不行
- ❌ 不得持有或展示任何凭据明文
- ❌ 不得为五处执行记录写五套页面代码

### Metadata / Semantic Kernel

- ❌ 不得执行 Flink Job
- ❌ 不得直接连接业务数据源做数据处理。Schema 发现必须委托 Data Space,Metadata 只存快照
- ❌ 不得存储凭据明文,只存 `credentialRef`
- ❌ 不得承担调度。Cron 表达式是定义,触发是 Control 的事
- ❌ 不得写入 `Execution` 事实
- ❌ 不得替代 Workflow Compiler 做任何编译或校验(除结构性字段校验外)

### Control / Workflow Compiler

- ❌ 不得直接读写业务数据
- ❌ 不得私存定义副本。只能持有由定义重新编译即可得到的 `PhysicalPlan`
- ❌ 不得实现 Flink / K8s 的提交细节
- ❌ 不得做身份认证(Platform 的事)与最终授权判定(Governance 的事)
- ❌ 不得把流任务与批任务塞进同一套调度语义(见 R5)

### Runtime / Execution

- ❌ 不得解释业务级 Workflow 语义。条件节点求值、依赖解析属 Control
- ❌ 不得自行决定权限
- ❌ 不得直接修改 Metadata 定义,只能通过状态/事件 Contract 回写
- ❌ 不得自行决定「该不该重试」,只执行 Control 下发的 `RetryPolicy`
- ❌ 不得直接向用户发告警。它只上报执行事实,告警判定归 Governance
- ❌ 不得让 Flink 的类型、异常、JobID 语义穿透到 Control 之上(见 R9)

### Data

- ❌ 不得负责调度
- ❌ 不得实现用户权限决策
- ❌ 不得持有 UI 状态
- ❌ 不得缓存业务数据(查询结果的短期分页缓存除外)
- ❌ 序号 35 的外部平台适配器不得直接把数据落进业务库,必须经 Runtime 的受控写入并留痕

### Governance / Trust

- ❌ 不得成为业务流程的执行者。只判定、记录、通知,不改业务对象
- ❌ 不得向任何 Space 发出 Command。需要干预时发事件(如 `WorkspaceSuspensionOrdered`),由目标 Space 自行执行 —— 这是保持依赖图无环的必要条件
- ❌ 不得直接读取其他 Space 的内部数据库表。只消费 Event 与公开 Query
- ❌ 不得拥有血缘的本体定义(本体归 Metadata,可信记录归 Governance)

### Platform / Tenancy

- ❌ 不得感知业务对象语义。它只知道对象归属哪个 Workspace,不知道对象是什么
- ❌ 不得把凭据明文交给除 Runtime 注入通道之外的任何调用方
- ❌ 不得把菜单权限(序号 29)当作数据权限。菜单权限决定「看得见哪个页面」,数据权限由 Governance 按 `Workspace + 资源` 判定

### Intelligence

- ❌ 不得替代 Metadata Registry
- ❌ 不得让本体、数据集、模型绕过版本管理
- ❌ 不得让模型训练修改原始 Dataset
- ❌ 不得直接连接业务数据源抽数,必须走平台的集成任务

---

## H. Space Dependency Graph

### H.1 依赖图

```text
                    ┌───────────────────────────────────────┐
                    │          UI / Experience              │
                    │  数据源 / 开发 / 画布 / 监控 / 配置    │
                    └────────────────┬──────────────────────┘
                                     │ C / Q
                    ┌────────────────▼──────────────────────┐
                    │      Metadata / Semantic Kernel       │
                    │  DataSource / Schema / Rule / JobDef  │
                    │  Workflow / Schedule / Relation / Ver │
                    └────────────────┬──────────────────────┘
                                     │ Definition Contract
                    ┌────────────────▼──────────────────────┐
                    │      Control / Workflow Compiler      │
                    │  Compile / Schedule / Condition /     │
                    │  Dispatch / RetryPolicy / Resource    │
                    └────────────────┬──────────────────────┘
                                     │ DispatchCommand
                    ┌────────────────▼──────────────────────┐
                    │        Runtime / Execution            │
                    │  Execution / Executor / Artifact /    │
                    │  FlinkJob / K8sJob / RuleInterpreter  │
                    └────────────────┬──────────────────────┘
                                     │ R/W
                    ┌────────────────▼──────────────────────┐
                    │               Data                    │
                    │ MySQL Oracle SQLServer PG DM8         │
                    │ Doris StarRocks FTP/SFTP RestAPI      │
                    │ HIS 平台适配器 / 对象存储               │
                    └───────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────┐
  │ Governance / Trust      (横切,约束以上全部)                  │
  │ Monitor(24) Alert(25/26) Channel(33) Audit(27) Policy Lineage│
  └──────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────┐
  │ Platform / Tenancy      (横切,为以上全部定义作用域)          │
  │ Workspace(28) Role(29) User(30) Credential Secret            │
  └──────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────┐
  │ Intelligence            (纵向域,架在平台之上,单向消费)      │
  │ Ontology Annotation Dataset Model Evaluation Feedback  [独立立项]│
  └──────────────────────────────────────────────────────────────┘
```

### H.2 8 × 8 依赖矩阵

矩阵单元格表示 **From 对 To 的出向依赖形式**(不是"提供什么"):

```text
C   = From 向 To 发出 Command
Q   = From 向 To 发起 Query
E   = From 消费 To 发布的 Event
R/W = From 对 To 做数据读写
-   = 无依赖
```

| From \ To | UI | Metadata | Control | Runtime | Data | Governance | Platform | Intelligence |
|---|---|---|---|---|---|---|---|---|
| **UI** | - | C/Q | C/Q | Q | Q※ | Q | C/Q | Q |
| **Metadata** | - | - | - | E | Q | - | Q | - |
| **Control** | - | C/Q | - | C | - | Q/E | Q | - |
| **Runtime** | - | - | - | - | R/W | - | Q※※ | - |
| **Data** | - | - | - | - | - | - | Q | - |
| **Governance** | - | Q | E | E/Q | E | - | Q | E |
| **Platform** | - | - | - | - | - | - | - | - |
| **Intelligence** | - | C/Q | C | E/Q | R/Q | Q | Q | - |

- ※ UI → Data 仅限**受控只读查询**(序号 7),经 Data Space 的查询服务,禁止写
- ※※ Runtime → Platform 仅限 `IssueCredentialInjectionHandle`,获取一次性凭据注入句柄
- Governance → Control 为 `E`(纯事件消费:`DispatchRejected`、`StreamingSupervisionEscalated`),**没有同步调用**。治理不向控制面发命令 —— 需要干预时发 `WorkspaceSuspensionOrdered` 事件由 Control 消费(见 F.6)

**读这张矩阵的四个要点:**

1. **Platform 行全空** —— 它不依赖任何 Space。一个为所有 Space 定义作用域的基础空间必须零出向依赖,否则会出现循环。
2. **UI 列全空** —— 没有任何 Space 依赖 UI。这是「UI 是投影,不是真相来源」在依赖图上的形式化表述。
3. **Metadata → Runtime 是 E 而非 Q** —— Metadata 通过消费执行事件回写状态,从不主动查询 Runtime。这条约束防止元数据内核被执行层的可用性拖垮。
4. **同步与异步必须分开看** —— 见下节。

### H.2.1 同步子图必须无环

把矩阵的边分成两类:

| 类型 | 边 | 耦合性质 |
|---|---|---|
| **同步** | `C` / `Q` / `R/W` | 调用方必须等待被调用方。被调用方不可用 = 调用方不可用 |
| **异步** | `E` | 经 Kafka 解耦。生产方不知道谁在消费,消费方晚一点收到也不影响正确性 |

**完整依赖图是有环的**,例如:

```text
Control ──Q(EvaluateAccess)──> Governance ──E(DispatchRejected)──> Control
Control ──C──> ... Intelligence ──C──> Control ... Governance ──E──> Intelligence
```

这些环**全部只经过异步边**。去掉异步边后的**同步子图是严格 DAG**:

```text
platform → data → { metadata, runtime } → governance → control → intelligence → ui
```

(此顺序由 `docs/space-contracts/verify.py` 从 8 份契约自动推导,不是人工填写。)

> **这是事件驱动在本架构中的真实作用**:不是为了"解耦"这个笼统的好处,而是**唯一能让治理横切所有 Space 却不制造循环依赖的机制**。Governance 必须能观察一切,但它一旦对被观察者发起同步调用,环就出现了。所以它只消费事件、只发事件。
>
> 工程后果很具体:同步子图的 DAG 顺序就是**部署顺序、启动顺序与集成测试的依赖顺序**;而异步边意味着 Governance 挂掉不会让任务停跑,只会让监控与告警延迟。这两条性质都可以写进 SLA。

### H.3 禁止边(硬约束)

```text
UI            ↛ Runtime        禁止直接调用 Flink / K8s API
UI            ↛ Data           禁止直接写入;只读也必须经受控查询服务
Metadata      ↛ Runtime        禁止直接执行,只能通过 Control
Data          ↛ Control        禁止反向控制,数据层不驱动调度
Runtime       → Metadata       只能通过状态 / 事件 Contract 更新,禁止直接改定义
Governance    → All            仅以 Policy / Decision / Event 横向约束,不参与业务执行
Platform      → All            仅以身份 / 租户 Contract 约束,不感知业务语义
Intelligence  ↛ Data           禁止直连抽数,必须经平台集成任务
Intelligence  ↛ (自建 Registry) 禁止建立第二元数据注册中心
```

---

## I. Space → Module Mapping

### I.1 一阶段:7 个模块边界

不建立数十个业务微服务。第一阶段按 Space 收敛为 7 个模块边界(**边界不等于进程,部署形态见 I.4**):

| # | 模块 | 对应 Space | 主要职责 |
|---|---|---|---|
| 1 | `platform-ui` | UI | React 单页应用,含工作流画布与统一执行记录组件 |
| 2 | `metadata-kernel` | Metadata | 定义注册、目录、Schema 快照、规则、版本、关系与血缘本体 |
| 3 | `control-plane` | Control | 编译器 + 调度器 + 条件求值 + 下发 |
| 4 | `runtime-gateway` | Runtime | 执行事实、执行器池(31)、制品仓库(32)、Flink/K8s 适配、规则解释器分发 |
| 5 | `data-connector-service` | Data | 连接器矩阵、连通性探测、Schema 发现、受控查询、DDL 生成、文件/API 解析 |
| 6 | `governance-service` | Governance | 监控聚合(24)、告警规则与渠道(25/26/33)、审计(27)、策略判定、可信血缘 |
| 7 | `iam-tenancy-service` | Platform | Workspace(28)、Role(29)、User(30)、Credential 与密钥托管 |

Intelligence → `intelligence-platform`,**独立仓库、独立部署、独立立项**。

### I.2 模块与功能清单的对照

| 子系统(菜单) | 落在哪些模块 |
|---|---|
| 数据集成(1-17) | `metadata-kernel`(定义) + `data-connector-service`(连接与解析) + `control-plane`(编译调度) + `runtime-gateway`(执行) |
| 数据开发(18-23) | `metadata-kernel` + `control-plane` + `runtime-gateway` |
| 运维监控 · 任务监控/告警/审计(24-27, 33) | `governance-service` |
| 运维监控 · 基础配置(28-30) | `iam-tenancy-service` |
| 运维监控 · 基础配置(31-32) | **`runtime-gateway`**(不是配置模块) |
| 数据服务(34) | `intelligence-platform`(独立立项) |
| 数据服务(35) | `data-connector-service` 的外部适配器 + `governance-service` 的合规约束 |

菜单与模块是**多对多**关系。UI 的菜单结构可以保持需求清单的原样,不必与模块一致 —— 菜单是投影。

### I.3 二阶段:什么时候才值得再拆

不满足以下条件**不拆**,避免为微服务而微服务:

| 拆分动作 | 触发条件 |
|---|---|
| `control-plane` 拆出 `scheduler` | 调度任务数 > 约 5000,且编译耗时开始影响触发准点率 |
| `runtime-gateway` 拆出 `executor-manager` | 执行器数 > 约 50,或需要管理多个 Flink 集群 |
| `data-connector-service` 按连接器族拆分 | 某一族(典型是 FTP 大文件)的资源占用开始干扰其他族 |
| `governance-service` 拆出 `audit-search` | 审计日志量级需要独立检索集群 |
| `metadata-kernel` 拆出 `lineage-service` | 血缘查询深度 > 3 跳且关系型递归查询性能不足 |

### I.4 模块边界 ≠ 进程边界

I.1 的 7 个是**模块边界**(包结构、依赖方向、Contract),不是 7 个必须独立启动的进程。这两件事经常被混为一谈,代价不对称:

> **包边界守住了,拆进程随时可做;包边界破了,再多机器也救不回来。**

院内私有化交付常见的硬件规格是 4C8G × 3 台。7 个 Spring Boot 进程各自占用 JVM 堆、连接池与线程池,在这个规格上会明显吃紧,而 Phase 1 的业务量根本用不上这种隔离。

#### 推荐的部署演进

| 阶段 | 进程数 | 组合 | 触发下一步的条件 |
|---|---|---|---|
| **Phase 1** | 2 | `platform-core`(iam + metadata + data)、`platform-ui`(静态资源) | 进入 Phase 2 |
| **Phase 2-3** | 3 | 增加 `control-runtime`(control-plane + runtime-gateway) | Flink 作业量上升,执行层需要独立扩缩容 |
| **Phase 4** | 4 | 拆出 `governance-service` | 审计与监控写入量开始影响业务库 |
| **Phase 5+** | 按 I.3 的触发条件逐个拆 | | 见 I.3 |

#### 合并部署时必须守住的四条

合并进程不等于合并代码。以下四条一旦破掉,后续拆分就要重写:

1. **每个模块一个独立 Maven/Gradle 模块**,依赖方向严格按 H.2.1 的同步子图,用 ArchUnit 或 Maven `banned-dependencies` 在 CI 里强制
2. **跨模块只调对方的 Contract 接口**,禁止直接 `@Autowired` 对方的内部实现类或 Repository
3. **跨模块不共享数据库连接**(J 章节的硬性约束)。同一进程内也不允许 `iam` 的代码去 join `metadata` 的表
4. **事件走真实的 Kafka,不走进程内事件总线**。进程内总线在合并部署时"能跑",拆分时会暴露出所有隐藏的同步假设

第 4 条最容易被省掉,也最贵。Phase 1 就把 Kafka 立起来,哪怕只有几个 topic。


---

## J. Space → Storage Mapping

| 存储 | 承载内容 | 归属 Space | 隔离与策略 |
|---|---|---|---|
| **元数据库** PostgreSQL | 定义、目录、Schema 快照、规则、版本、关系与血缘本体 | Metadata | 单库,全表 `workspace_id` 行级隔离,ORM 层强制注入过滤 |
| **租户身份库** PostgreSQL(独立实例或独立 schema) | Workspace / User / Role / Grant / MenuPermission | Platform | **必须与元数据库隔离** —— 它是授权判定的输入,同库会被业务查询绕过 |
| **密钥存储** Vault / KMS | Credential 密文、Workspace 鉴权密钥 | Platform | 仅 Runtime 可经一次性注入句柄解密,不落任何业务库 |
| **执行事实库** PostgreSQL 按时间分区 | Execution / ExecutionAttempt / ExecutionMetric | Runtime | 与元数据库分离(写多读多);按天/月分区 + 保留策略 |
| **日志检索** OpenSearch | ExecutionLog / ErrorLog / AuditRecord | Runtime(执行日志)/ Governance(审计) | 按天滚动索引;**审计索引保留期独立设置**,通常远长于执行日志 |
| **事件总线** Kafka | 全部跨 Space Event | 共享基础设施 | 按 Space 前缀分 topic;`execution.*` 独立 topic(量最大) |
| **缓存** Redis | Schema 树、菜单权限、调度分布式锁、告警抑制窗口 | 多 Space 共用 | key 按 Space 前缀隔离;**调度锁必须用独立 DB index** |
| **对象存储** MinIO / S3 | 平台级 JAR(32)、文件同步中转、查询结果导出 | Runtime | 按 `workspace/` 前缀分区 |
| **图存储**(可选,暂不引入) | 血缘图 | Metadata | 仅当 J 表最后一行的触发条件满足时启用 |
| **Intelligence 存储**(独立) | 影像、标注文件、模型权重、本体文件 | Intelligence | 独立立项,独立对象存储 + 独立图/RDF 存储 |

### J.1 唯一的硬性存储约束

> **跨 Space 不共享数据库连接。** Governance 不得直接查询 Metadata 的表,只能走 Query API 或消费 Event。

这条约束是 Space Model 能否真正落地的分水岭。一旦允许「反正在同一个库里,join 一下更快」,所有边界在三个月内都会消失。若性能确有问题,正确做法是 Governance 在自己的存储里维护物化视图,由 Event 驱动更新。

---

## K. Space → Runtime Mapping

| 任务类型(功能序号) | Runtime | 调度语义 | 关键点 |
|---|---|---|---|
| 实时开发 Flink SQL(18) | Flink Streaming on K8s(Application 模式) | **常驻,启停式** | checkpoint / savepoint、保活重启、背压与 lag 监控 |
| 离线开发 Flink SQL(20) | Flink Batch on K8s | Cron 周期 | 跑完即退,资源随用随还 |
| 离线同步(11) | Flink Batch(JDBC / CDC connector) | Cron 周期 | 规则解释器随作业分发;源端分片策略在编译期确定 |
| 文件解析入库(12) | Flink Batch(File connector) | Cron / 手动 | CSV / JSON,全量 |
| API 解析入库(13) | Flink Batch(HTTP source) | Cron / 手动 | 全量;认证凭据经注入句柄获取 |
| 批量新增同步(14) | 展开为 N 个离线同步 Execution | 同 11 | 批量创建的是**定义**,执行仍逐个独立 |
| 整库迁移(9) | Flink Batch,一次性 | `RunOnce` | 先 `GenerateTargetDDL` → 预览修改 → `CreateTargetTable` → 再搬数 |
| 工作流节点 FLINK_SQL / SQL(22) | 同上 | DAG 内节点 | |
| 工作流节点 SHELL / PYTHON(22) | Kubernetes Job(镜像内执行) | DAG 内节点 | **必须设资源限额与网络策略**,否则是逃逸面 |
| 工作流节点 HTTP(22) | `runtime-gateway` 内的轻量执行器 | DAG 内节点 | 不起 Pod,但必须有超时与重试 |
| 条件节点 Conditions(22) | **不在 Runtime 执行** | Control 内求值 | 见 G 章节 Runtime 的 must-not-do |
| 周期连通性检查(6) | `runtime-gateway` 轻量探测器 | Cron | 结果发 Event,由 Governance 决定是否告警 |
| 数据查询(7) | `data-connector-service` 同步执行 | 交互式 | 强制超时 + 行数上限 + 逐次审计 |
| 训练 / 预标注(34) | Kubernetes Job(Python) | 由 Intelligence 经 Control 提交 | 复用统一 Execution 模型 |

**执行器分配(序号 31)**:Flink 执行器注册进 `ExecutorPool`;Control 下发时携带 `ResourceRequirement`;Runtime 按负载与亲和性自动分配;执行器不健康时摘除并把待调度实例回队,同时发 `ExecutorUnhealthy` 事件。

---

## L. Technology Selection

两套并列:主选开源栈,并对每一项标注国产化/信创替代。**尚未锁定,需先澄清 L.2 的问题。**

### L.0 语言选型:主语言为 Java,理由是三条硬约束

结论先行:**后端主语言 Java 17 + Spring Boot 3**。这不是团队偏好,是被下面三条约束锁死的。

**约束一:Flink 编译期校验必须发生在 JVM 内。**
Phase 2 的完成判据要求「编译失败时 UI 能定位到具体字段」。做到这点,Control 必须在编译期调用 Flink 的 `TableEnvironment` / Calcite planner 去 parse 并 validate Flink SQL —— 在 JVM 里这是一次方法调用。换成 Node 或 Rust,只有两个选择:起 JVM 子进程(把复杂度搬到进程边界上,还丢了类型),或者放弃编译期校验、把错误推迟到运行时(直接违反判据)。

这一条单独就足以定下主语言,因为 Control 是四大核心技术资产之一(M.1)。

**约束二:连接器矩阵在 JDBC 生态里。**
10 种数据源中,Oracle、SQL Server、StarRocks、Doris 的成熟驱动都在 JVM。**DM8 达梦官方提供 JDBC / ODBC / DPI(C) / .NET / dmPython,Node 与 Rust 没有官方驱动。** DM8 本身已是排期风险(R7),不应再叠一层驱动自研。

**约束三:医疗机构私有化交付场景。**
等保测评、第三方安全扫描、院方信息科运维接手、项目期内人员替换 —— Java + Spring Boot 是这个场景的默认答案。信创环境下毕昇 JDK、龙芯 JDK 均成熟;Rust 在龙芯 LoongArch 上的工具链支持要弱一截。这是交付风险,不是技术优劣。

#### 语言分配

| 位置 | 语言 | 理由 |
|---|---|---|
| `platform-ui` | **TypeScript + React** | 前端,本就是 Node 生态 |
| `metadata-kernel` / `control-plane` / `runtime-gateway` / `data-connector-service` / `governance-service` / `iam-tenancy-service` | **Java 17 + Spring Boot 3** | 上述三条约束 |
| `intelligence-platform`(序号 34) | **Python** | OWL2 / RDF、标注、模型训练、多智能体 —— 生态只在 Python |
| 工作流 Python 节点(序号 22) | **Python 镜像(K8s Job)** | 但**调度它的是 Java 的 runtime-gateway**,语言边界在容器上,不在服务上 |

#### 明确排除的两个选项

| 选项 | 结论 | 理由 |
|---|---|---|
| **Rust** | 本系统当前无合理位置 | 唯一可想象的场景是高吞吐日志/指标采集 agent,但 Flink 自身会上报指标,自建属于过早优化。承担 Rust 的招聘与交付风险换不到对应收益 |
| **Node.js**(前端之外) | 不引入,**特别是不要加 BFF** | 已有 APISIX 网关承担聚合与鉴权前置,再插一层 BFF 只是无谓的一跳,并且会诱使业务逻辑漏进 UI Space(违反 G 章节 UI 的 must-not-do) |

| 位置 | 主选(开源) | 国产化 / 信创替代 | 备注 |
|---|---|---|---|
| 前端 | React + TypeScript + Ant Design | 同左 | 前端无信创约束 |
| 工作流画布(22) | React Flow | AntV X6(蚂蚁开源) | X6 对复杂节点样式与国内文档更友好 |
| API 网关 | APISIX | APISIX(本身即国产开源) | 无需替换 |
| 应用框架 | Java 17 + Spring Boot 3 | 同左,运行于毕昇 JDK / 龙芯 JDK | |
| 元数据库 / 身份库 | PostgreSQL 15+ | 达梦 DM8 / openGauss / KingBase | **换库前必须验证 JSONB、递归 CTE、分区表**(见 L.2) |
| 缓存 | Redis 7 | Redis / 国产兼容版 | |
| 事件总线 | Apache Kafka | Kafka / RocketMQ(国产开源) | RocketMQ 事务消息对审计留痕更友好 |
| 日志检索 | OpenSearch | OpenSearch / 国产云检索服务 | |
| 密钥托管 | HashiCorp Vault | 国密 KMS / 硬件密码机 | 等保三级通常要求硬件密码机 |
| 身份认证 | Keycloak | **对接院方已有统一身份认证**(AD / LDAP / CAS / OIDC) | 医院几乎都已有域账号或统一认证,不允许再建一套,序号 30 需澄清 |
| 调度 | 自研 Control(Cron 解析 + 分布式锁) | 同左 | Temporal 功能强但运维成本高,规模未到不引入 |
| 计算引擎 | Apache Flink 1.18+ | 同左 | 需求已指定 |
| 编排底座 | Kubernetes + Flink K8s Operator | 国产 K8s 发行版 | |
| 对象存储 | MinIO | MinIO / 国产对象存储 | |
| 策略引擎 | 自研判定(权限模型不复杂) | 同左 | OPA 仅在策略复杂度上升后引入 |
| 图存储 | 暂不引入 | Nebula Graph(国产开源) | 见 J 表触发条件 |

### L.1 连接器矩阵(序号 1-4 的真实工作量)

需求要求 10 种数据源类型,但真实工作量是 **类型 × 能力** 的矩阵:

| 类型 | 读 | 写 | Schema 发现 | 建表(9) | CDC | 备注 |
|---|---|---|---|---|---|---|
| MySQL | ✅ | ✅ | ✅ | ✅ | ✅ | 基线 |
| Oracle | ✅ | ✅ | ✅ | ✅ | ⚠️ | LogMiner 授权成本高 |
| SQL Server | ✅ | ✅ | ✅ | ✅ | ⚠️ | |
| PostgreSQL | ✅ | ✅ | ✅ | ✅ | ✅ | |
| **DM8 达梦** | ✅ | ✅ | ✅ | ⚠️ | ❌ | **Flink 无官方 connector,需自研或改 JDBC**;类型映射需专门规范 |
| StarRocks | ✅ | ✅ | ✅ | ✅ | — | 多节点写入(序号 2) |
| Doris | ✅ | ✅ | ✅ | ✅ | — | 多节点写入(序号 2) |
| FTP / SFTP | ✅ | ✅ | ➖ | — | — | Schema 来自文件解析(12) |
| RestAPI | ✅ | — | ➖ | — | — | 三种认证方式(4) |
| HIS 平台(35) | ✅ | — | ⚠️ | — | ❌ | **需 HIS 厂商开放接口或库表授权**;常见形式:只读视图 / 中间库 / WebService / HL7 |

⚠️ 与 ❌ 是**排期风险点**,不是功能缺失。特别是 DM8:序号 1 要求支持创建 DM8 数据源,但 Flink 生态无官方 DM8 connector,需要自研 JDBC 方言与类型映射,应单独排期。

### L.2 需要向甲方澄清的两个技术前提

**(1) 平台自身是否必须运行在信创环境?**
序号 1 只说「支持创建 DM8 类型的**数据源**」。这条证据只能支持「平台需要**接入** DM8」,不能推出「平台自身必须跑在国产数据库上」。两者工作量差异巨大 —— 若平台元数据库改用 DM8,`JSONB`、递归 CTE、分区表的用法都需要重做。**建议在合同/需求确认阶段书面澄清。**

**(2) 用户体系是自建还是对接?**
序号 30 描述了完整的用户增删改查与重置密码,读起来像自建。但既然要与院内 HIS 对接(序号 35),说明是院内部署 —— **医院几乎都已有 AD 域账号或统一认证平台,通常不允许业务系统再建一套账号体系**。若是对接,序号 30 的多数功能应改为「用户同步 + 本地授权」,重置密码一项甚至会被直接砍掉。

---

## M. MVP / Evolution Path

每个阶段给出**可证伪的完成判据** —— 判据没达成就不进入下一阶段。

### Phase 1 · 元数据内核与数据接入

**范围**:`metadata-kernel` + `data-connector-service` + `iam-tenancy-service` + `platform-ui` 骨架
**功能项**:1-8、16、28-30

**完成判据**
- 能创建全部 10 类数据源(DM8 若 connector 未就绪可延后,但必须有明确排期)
- 连通性测试可用,周期检查可开关(6)
- 能逐层浏览 库 / 模式 / 表 / 字段 / 视图,自定义 SQL 查询可用且受控(7)
- 两个 Workspace 之间数据完全不可见 —— **必须有自动化测试证明**,这是唯一不能靠人工验证的判据
- 数据源目录与任务目录的增删改查可用(5、16)

### Phase 2 · 控制面与离线同步打通

**范围**:`control-plane` + `runtime-gateway` + 离线同步
**功能项**:11-15、17

**完成判据**
- 一条离线同步任务可以完整走通:定义 → 编译校验 → 发布 → Cron 触发 → Flink 执行 → 落 Execution 记录 → 查看错误日志
- 清洗规则与转换规则(17)在页面上配置后**确实生效**,且改规则不需要改代码 —— 这是验证 Rule DSL 设计是否成立的唯一方式
- 编译失败时 UI 能定位到具体字段
- 文件解析(12)与 API 解析(13)入库可用

### Phase 3 · 开发与编排

**范围**:实时开发、离线开发、工作流编排、整库迁移、执行器管理
**功能项**:9-10、18-23、31-32

**完成判据**
- 实时任务可启动 / 停止,异常后按保活策略自愈,超阈值转告警
- 工作流画布可编排 Flink SQL / SQL / 离线同步 / Shell / Python / HTTP 六类节点与条件节点,并完整执行
- 执行中的任务可取消(19、21、23 明确要求)
- 执行器自动分配生效,摘除不健康执行器后任务能重新排队
- 整库迁移可预览并修改建表语句后执行(9)

### Phase 4 · 治理与可观测

**范围**:`governance-service`
**功能项**:24-27、33

**完成判据**
- 序号 24 的五个统计口径(执行总数 / 失败数 / 今日新增抽取 / 总计抽取 / 任务时延)**跨数据集成与数据开发两个子系统**正确聚合 —— 这条判据同时验证了 Phase 2/3 的 Execution 模型是否真的统一
- 告警规则支持「全部任务」与「指定任务」两种范围,告警频率(抑制窗口)生效
- 告警渠道可测试连通性,邮件推送可达
- 审计日志覆盖数据集成与数据开发的全部写操作

### Phase 5 · 智能与外部对接

**范围**:`intelligence-platform`(独立立项) + HIS 平台对接
**功能项**:34、35

**独立立项**,不占用 Phase 1-4 的资源与排期。

### M.0 为什么 MVP 顺序与 H.2.1 的拓扑顺序不同

同步子图的拓扑顺序把 **Governance 排在 Control 之前**(因为 Control 在编译期同步调用 `EvaluateAccess`),而 MVP 把 Governance 放在 Phase 4。这不是矛盾,但必须说清楚:

- Control 对 Governance 的**唯一同步依赖**是 `EvaluateAccess` 一个接口。
- Phase 2/3 以**桩实现**满足它:同一 Workspace 内已授权用户一律放行,并把每次判定记进审计流。接口签名与最终版本完全一致。
- Phase 4 用真实策略引擎替换桩实现,Control 侧零改动。

**这是允许的偏离,前提是接口在 Phase 2 就按最终形态定义。** 若 Phase 2 图省事让 Control 直接跳过权限判定(而不是调用一个放行的桩),Phase 4 就变成了侵入式改造 —— 那才是真正的顺序错误。

### M.1 最核心的技术资产

按此顺序形成,四项决定系统未来能否持续扩展:

```text
Metadata Kernel  →  Workflow DSL  →  Workflow Compiler  →  Runtime Contract
```

任何一项做成临时方案,后续每加一种任务类型、每接一种数据源、每换一个执行引擎,成本都会线性甚至超线性上升。

---

## N. Architecture Risks

| # | 风险 | 触发条件 | 缓解手段 |
|---|---|---|---|
| **R1** | 序号 34 是「一行需求 = 一个平台」。它包含 OWL 2 七层医学概念体系、3-5 模型协同的多智能体标注工厂、影像标注训练一体化数据飞轮、四维度 AI 质控引擎,工程量与序号 1-33 之和相当 | 与平台主线同期交付 | **已确认独立立项**。本文档只锁定 C+D.8 的四条 Contract |
| **R2** | 按菜单划分模块,「基础配置」成为杂物袋,Runtime 资源管理被错置进配置层 | 开发排期按菜单分工 | 模块边界按 Space 划(I 章节),菜单只是 UI 投影 |
| **R3** | 「空间」(Workspace)与架构 Space 命名冲突,沟通与代码混乱 | 文档与代码未强制区分 | 术语表置于文档首节;代码禁用 `Space` 命名运行时对象 |
| **R4** | 五处执行记录各建一套表,导致序号 24/25/27 的跨模块统计做不出来 | Phase 2/3 各模块独立建表 | 统一 `Execution` / `Alert` / `AuditRecord` 平台级模型;Phase 4 的第一条判据就是对它的验证 |
| **R5** | 实时(常驻流)与离线(周期批)混用同一套调度模型 | Control 只实现一种调度语义 | Control 显式区分 streaming(保活 / savepoint / 背压)与 batch(依赖解析 / 补数),两套状态机见 E.2 与 E.3 |
| **R6** | 序号 17 的规则被硬编码进同步算子,规则管理页面形同虚设 | 为赶工期把规则写死在 connector 里 | Rule DSL(Metadata 拥有)+ Runtime 规则解释器;同步任务只引用 `ruleId`;Phase 2 判据显式验证「改规则不改代码」 |
| **R7** | 连接器矩阵爆炸:10 种类型 × 读/写/Schema发现/建表/CDC 五种能力。**DM8 无 Flink 官方 connector** | 按「10 个数据源」估算工作量 | 建立 L.1 的兼容性矩阵与准入用例集;DM8 方言与类型映射单独排期;异构类型映射单独定规范 |
| **R8** | 序号 35 HIS 对接是外部依赖,**需 HIS 厂商配合开放接口或库表授权,进度不由己方控制**;健康医疗数据属敏感个人信息 | 排期把它当作普通功能项 | 适配器隔离在 Data Space 边缘;脱敏、授权、留痕由 Governance 强制;**按外部依赖单列里程碑,并在合同中明确 HIS 厂商配合责任与接口交付时间** |
| **R9** | Flink 承载全部负载(实时/离线/整库迁移/离线同步),其类型系统、异常语义、JobID 概念极易穿透到上层 | Control 或 UI 直接处理 Flink 异常 | Runtime Contract 抽象为 `Submit / Start / Stop / Cancel / Retry / Status / Logs` 七个动作;Flink 概念不得出现在 Control 之上的任何接口签名中 |
| **R10** | **名实不符**:平台叫「数据治理平台」,但 35 项功能中不存在数据标准、数据质量规则、主数据、数据资产目录、数据分级分类。当前功能集在工程实质上是数据集成开发平台(DataOps) | 验收方按 DAMA 意义上的数据治理理解范围 | **在需求确认阶段书面澄清范围**。C+D.6 已为 `QualityRule` / `QualityResult` 预留对象位置,但不在本期范围 |

### N.1 风险优先级

- **必须在需求确认阶段解决**:R1、R10、L.2 的两个技术前提
- **必须在架构设计阶段解决**:R4、R5、R6、R9(都是一旦做错就要重构的结构性问题)
- **必须在排期阶段单列**:R7(DM8)、R8(外部平台对接)
- **靠工程纪律持续保证**:R2、R3

---

## O. 核心元语闭环

整个 Space Model 可以压缩成四个动作:

```text
Describe → Metadata Space
Plan     → Control Space
Execute  → Runtime Space
Govern   → Governance Space   (Learn → Intelligence Space,后续阶段)
```

自动化形态:

```text
Data
 ↓
Metadata            (定义:数据源 / 规则 / 任务 / 工作流)
 ↓
Logical Definition
 ↓
Compiler            (校验 / 优化 / 资源选择)
 ↓
Physical Plan
 ↓
Runtime             (Flink / K8s / HTTP)
 ↓
Execution Event     (统一事实)
 ↓
Governance          (监控 / 告警 / 审计 / 血缘)
 ↓
Metadata Update     (状态回写 / Schema 漂移 / 血缘补全)
```

形式化:

```text
M(t+1) = f( M(t), E(t) )                 元数据由事件驱动演进
P(t)   = Compile( D(t), M(t), C(t), R(t) )  物理计划由定义、元数据、治理约束、运行时能力共同决定
E(t+1) = Execute( P(t), Data(t) )        执行产生事件
```

其中 `M`=元数据状态,`D`=定义,`C`=治理约束,`R`=运行时能力,`P`=物理计划,`E`=执行事件。

**这个闭环里 Governance 出现在 `C` 的位置** —— 治理约束是编译的输入,不是执行后的检查。这是「治理横切」在数学上的含义:如果治理只在事后检查,它就退化成了一个报表模块。

---

## P. 最终判定

```text
SpaceModel = { UI, Metadata, Control, Runtime, Data, Governance, Platform, Intelligence }

CoreFlow     : UI → Metadata → Control → Runtime → Data
CrossCutting : Governance(约束全部) + Platform(作用域全部)
VerticalDomain: Intelligence(架于平台之上,单向消费,独立立项)
```

系统的「操作系统级核心」是:

```text
Metadata Kernel  +  Workflow Compiler  +  Runtime Contract  +  统一 Execution 事实模型
```

前三项来自技能文档的通用结论;**第四项是本系统特有的** —— 它由序号 24、25、27 三条需求各自独立地要求跨子系统聚合所证明,是本次分析中优先级最高的架构约束。

---

## Q. 模型自检

架构文档不能靠"看起来合理"验收。以下三项检查已实际执行,结论如实记录。

### Q.1 端到端闭环走查

沿一条最长的真实链路验证:**每一跳都有明确的发起 Space、承接 Space 与 Contract,不存在无主跳转。**

| # | 动作 | 发起 → 承接 | Contract | 状态变化 |
|---|---|---|---|---|
| 1 | 用户配置离线同步任务 | UI → Metadata | `CreateSyncJobDef` (C) | `SyncJobDef: → DRAFT` |
| 2 | 引用清洗/转换规则 | Metadata 内部 | `Rule` 引用(仅存 ruleId) | — |
| 3 | 提交发布 | UI → Control | `CompileDefinition` (C) | — |
| 4 | 读取定义 | Control → Metadata | `GetJobDef` (Q) | — |
| 5 | 校验数据源可用 | Control → Metadata | `GetDataSource` (Q) | — |
| 6 | 校验发布权限 | Control → Governance | `EvaluateAccess` (Q,同步) | — |
| 7 | 编译成功 | Control → Metadata | `PublishDefinition` (C) | `SyncJobDef: DRAFT → VALIDATED → PUBLISHED` |
| 8 | 绑定 Cron | UI → Metadata → Control | `BindSchedule` (C) | `SyncJobDef: → SCHEDULING` |
| 9 | Cron 到点触发 | Control 内部 | `ScheduleTrigger` | 发 `ScheduleTriggered` (E) |
| 10 | 选择执行器 | Control → Runtime | `DispatchExecution` (C,携带 PhysicalPlan + RetryPolicy) | `Execution: → PENDING → DISPATCHED` |
| 11 | 取凭据 | Runtime → Platform | `IssueCredentialInjectionHandle` (Q) | — |
| 12 | 提交 Flink 作业 | Runtime 内部 | — | `Execution: → RUNNING`,发 `ExecutionStarted` (E) |
| 13 | 读源端 / 写目标端 | Runtime → Data | `ReadBatch` / `WriteBatch` (C) | — |
| 14 | 应用清洗转换规则 | Runtime 内部 `RuleInterpreter` | — | — |
| 15 | 上报进度 | Runtime → Kafka | `ExecutionProgressed` (E,含 rowsRead/rowsWritten/latency) | — |
| 16 | 作业失败 | Runtime → Kafka | `ExecutionFailed` (E,含 errorLogRef) | `Execution: → FAILED` |
| 17 | 监控聚合 | Governance ← Kafka | 消费 (E) | `MonitorAggregate` 更新(序号 24 五个口径) |
| 18 | 告警判定 | Governance 内部 | 匹配 `AlertRule`,检查 `AlertSuppression` | `Alert: → TRIGGERED → NOTIFYING` |
| 19 | 推送 | Governance → AlertChannel | 邮件(序号 33) | `Alert: → NOTIFIED` |
| 20 | 状态回写 | Metadata ← Kafka | 消费 `ExecutionFailed` (E) | `SyncJobDef` 最近执行状态更新 |
| 21 | 审计留痕 | Governance ← 全链路 | `AuditRecord`(步骤 1/3/7/8 的写操作) | 只追加 |
| 22 | 用户手动重跑 | UI → Control → Runtime | `RetryExecution` (C) | 新 `ExecutionAttempt`,**不新建 Execution** |

**结论:22 跳全部有主,无孤儿跳转。** 三处曾经容易出错的地方已被这条链路显式钉死:

- 步骤 6 是 Control **同步**调用 Governance —— 治理约束是编译的输入,不是事后检查
- 步骤 17/18 共用步骤 16 的同一个事件 —— 监控与告警不是两条数据链路
- 步骤 22 产生 Attempt 而非新 Execution —— 否则序号 24 的"执行总数"被重试污染

### Q.2 Must-Not-Do 反例检查

把每条禁止边拿回 35 项需求里搜索反例。**发现 4 处需求文本与约束存在张力,均需写入验收标准。**

| 禁止边 | 需求中的反例风险 | 结论与处置 |
|---|---|---|
| `UI ↛ Data 写` | **序号 7**「支持自定义查询类的 SQL 语句」—— 用户可以在输入框里写 `INSERT` / `DROP` | 需求措辞是"查询类",**意图与约束一致**;但需求未规定强制手段。**必须写入验收:SQL 解析白名单,只放行 SELECT/SHOW/DESC/EXPLAIN,并强制超时 + 行数上限 + 逐次审计** |
| `Metadata 不得存凭据明文` | **序号 4** 三种认证方式 + 序号 1-3 数据库口令,朴素实现会把 token 直接塞进数据源配置 JSON | **必须写入验收:数据源的查询/导出/日志接口一律不得返回凭据明文,回归测试需覆盖** |
| `Data ↛ Control 反向控制` | 当前 35 项**无反例** —— 序号 12 文件解析入库是 Cron 全量,不是文件到达触发 | 通过。但**若后续新增"文件到达即触发同步"**,必须实现为 `Data → Event → Control 订阅`,不得由 Data 直接命令 Control |
| `Governance 不得执行业务` | 当前 35 项**无反例** —— 序号 25/26 只做通知 | 通过。但**若后续新增"告警后自动重试/自动停用"**,必须实现为 `Governance → Event → Control 消费`,不得由 Governance 直接命令 |
| `Intelligence ↛ Data 直连` | **序号 35**「获取面向 AI 应用的健康医疗数据」—— "面向 AI"的措辞容易被实现成外部平台直通 Intelligence 的管道 | **必须写入验收:序号 35 落地为 Data Space 适配器 + 平台集成任务,Intelligence 只读消费落地后的数据**,否则脱敏、授权与审计(序号 27)全部旁路 |
| `UI ↛ Runtime` | 序号 19/21/23「取消执行中的任务」 | 通过。取消走 `UI → Control → Runtime`;UI 读取执行日志属 `UI → Runtime (Q)`,矩阵允许 |

### Q.3 自动化校验

`docs/space-contracts/verify.py` 从 8 份 YAML 契约自动校验五件事,**当前状态 PASSED**:

```bash
python3 docs/space-contracts/verify.py
```

| 检查 | 内容 | 结果 |
|---|---|---|
| 1 | 8 份契约语法合法、必填字段齐全 | ✅ 8/8 |
| 2 | `depends_on` / `consumed_by` 双向互为镜像,无单边声明 | ✅ |
| 3 | **同步依赖子图无环**,并自动推导构建顺序 | ✅ `platform → data → {metadata, runtime} → governance → control → intelligence → ui` |
| 4 | 每个被消费的事件都有生产者 | ✅ 55 种事件 |
| 5 | 35 项功能全部有且仅有一个 Owner | ✅ 35/35(序号 17 双 Owner 为刻意设计,已标注) |

> 这个脚本的价值不在初版,而在**后续每次改架构时重跑**。契约是 YAML 而不是散文,就是为了让"边界有没有被破坏"这件事可以被机器回答。

---

## 附录

| 文件 | 内容 | 用途 |
|---|---|---|
| [docs/function-space-matrix.md](docs/function-space-matrix.md) | 35 项功能 → Owner Space / 参与 Space / 核心对象 / 落地模块 | **需求可追溯**。评审时逐行对照招标文件 |
| [docs/space-contracts/](docs/space-contracts/) | 8 份 Space Contract(YAML) | **机器可读**。后续 AI 编码与代码生成可直接引用 `must_not_do` / `depends_on` 作为约束输入 |
| [docs/space-contracts/verify.py](docs/space-contracts/verify.py) | 契约一致性校验器 | 每次改架构后重跑,确认边界未被破坏 |

```bash
python3 docs/space-contracts/verify.py
```

---

## 待办:需求确认阶段必须澄清的四个问题

按影响程度排序。前两个不澄清,报价与排期都无法成立。

1. **序号 34 高质量数据集制备独立立项,需甲方书面确认**(风险 R1)—— 本文档已按独立立项处理并只锁定四条 Contract;它的工程量与序号 1-33 之和相当,若甲方要求同期交付,报价与排期须整体重估
2. **平台叫"数据治理平台",但 35 项中无数据标准、数据质量、主数据、数据资产目录、数据分级分类。验收方按哪个范围理解?**(风险 R10)
3. **平台自身是否必须运行在信创环境**,还是只需支持接入 DM8 等国产数据源?(L.2)—— 证据只支持后者,但两者工作量差异巨大
4. **用户体系是自建还是对接院方 AD / 统一认证?**(L.2)—— 序号 30 读起来像自建,但序号 35 的 HIS 对接说明是院内部署,医院通常已有域账号体系
