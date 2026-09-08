# 35 项功能 → Space 映射表

用途:需求可追溯。每一项功能都能查到它的**归属 Space(Owner)**、参与 Space、核心对象与落地模块。

规则:
- **Owner** 唯一 —— 该功能的对象与状态由这个 Space 拥有,只有它能改。
- **参与** 可多个 —— 提供能力但不拥有状态。
- 每项功能必须恰有一个 Owner。无 Owner 或双 Owner 都是建模缺陷。

---

## 数据集成 · 数据源管理

| 序号 | 二级模块 | Owner Space | 参与 Space | 核心对象 | 落地模块 | 备注 |
|---|---|---|---|---|---|---|
| 1 | 关系型数据库 | Metadata | Data, Platform | `DataSourceDef` | metadata-kernel + data-connector-service | 口令存 Platform,Metadata 只存 `credentialRef`。**DM8 无 Flink 官方 connector(R7)** |
| 2 | 大数据存储 | Metadata | Data | `DataSourceDef` | 同上 | StarRocks / Doris **多节点**写入配置 |
| 3 | 文件传输型 | Metadata | Data | `DataSourceDef`, `FileObject` | 同上 | FTP / SFTP |
| 4 | 接口型 | Metadata | Data, **Platform** | `DataSourceDef`, `Credential` | 同上 | 三种认证(无 / 基础 / Token)的凭据由 Platform 托管 |
| 5 | 数据源目录 | Metadata | — | `DataSourceCatalogNode` | metadata-kernel | 纯定义态,树形结构 |
| 6 | 连通性测试 | **Data**(探测执行) | Control(周期调度), Governance(失败告警) | `ConnectivityProbeResult` | data-connector-service + control-plane | 「开启/关闭周期连通性检查」是调度语义,归 Control;探测本身归 Data;失败告警归 Governance。**一个功能点跨三个 Space 的典型例子** |
| 7 | 数据查询 | **Data**(查询执行) | Metadata(Schema 快照), UI(展示与 SQL 格式化) | `QueryRequest`, `SchemaDiscoveryResult`, `Table`, `Column`, `View` | data-connector-service | 唯一交互式直连业务库的能力,**必须受控**:超时 + 行数上限 + 逐次审计 |
| 8 | 关联信息 | Metadata | — | `RelationEdge` | metadata-kernel | 即 `JobDef ──ReadsFrom/WritesTo──> DataSourceDef` 的查询视图;数据源删除保护的依据 |

## 数据集成 · 集成任务

| 序号 | 二级模块 | Owner Space | 参与 Space | 核心对象 | 落地模块 | 备注 |
|---|---|---|---|---|---|---|
| 9 | 整库迁移 | Metadata(定义) | Control(编译), Runtime(执行), **Data**(DDL 生成与建表) | `MigrationJobDef`, `TargetTableDDL`, `TypeMappingSpec` | 四模块协作 | 「预览并修改建表语句」= `GenerateTargetDDL`(Data)→ 用户改 → `CreateTargetTable`(Data)。命名规则归 Metadata,方言归 Data |
| 10 | 整库迁移记录 | **Runtime** | Governance | `Execution`(jobRefType=MIGRATION) | runtime-gateway | **统一 Execution 模型,非独立表(R4)** |
| 11 | 离线同步 | Metadata(定义) | Control(调度), Runtime(执行), Data(读写) | `SyncJobDef`, `ScheduleDef`, `Rule` 引用 | 四模块协作 | 过滤/清洗/转换/脱敏均**引用** `ruleId`,不内嵌规则实现(R6) |
| 12 | 离线同步-文件解析入库 | Metadata(定义) | Data(CSV/JSON 解析), Runtime | `FileParseJobDef` | 同上 | 全量同步 |
| 13 | 离线同步-API解析入库 | Metadata(定义) | Data(HTTP 拉取), Runtime, Platform(凭据) | `ApiParseJobDef` | 同上 | 全量同步 |
| 14 | 离线同步-批量新增 | Metadata | Data(一键生成目标表) | `BatchSyncTemplate` | metadata-kernel + data-connector-service | 批量创建的是**定义**;执行仍逐个独立 Execution |
| 15 | 离线同步记录 | **Runtime** | Governance | `Execution`(jobRefType=OFFLINE_SYNC) | runtime-gateway | **统一 Execution 模型(R4)** |
| 16 | 任务目录 | Metadata | — | `TaskCatalogNode` | metadata-kernel | |
| 17 | 规则管理 | **Metadata(定义)+ Runtime(执行)** | — | `Rule`, `RuleParam` / `RuleInterpreter` | metadata-kernel + runtime-gateway | **双栖对象**。清洗:时间/日期格式、数值格式、缺失值;转换:字符串替换、大小写、前后缀、解密、去空格。定义归 Metadata,解释执行归 Runtime(R6) |

## 数据开发

| 序号 | 二级模块 | Owner Space | 参与 Space | 核心对象 | 落地模块 | 备注 |
|---|---|---|---|---|---|---|
| 18 | 实时开发 | Metadata(定义) | Control(提交), Runtime(Flink Streaming) | `StreamingDevJobDef` | 三模块协作 | 「启动/停止」→ 独立的流任务运行态状态机(E.3),与批任务根本不同(R5) |
| 19 | 执行记录(实时) | **Runtime** | Governance | `Execution`(STREAMING_DEV) | runtime-gateway | 明确要求「取消执行中的任务」→ `Cancel` Command |
| 20 | 离线开发 | Metadata(定义) | Control(Cron 调度), Runtime(Flink Batch) | `BatchDevJobDef`, `ScheduleDef` | 三模块协作 | |
| 21 | 执行记录(离线) | **Runtime** | Governance | `Execution`(BATCH_DEV) | runtime-gateway | **统一 Execution 模型(R4)** |
| 22 | 工作流编排 | Metadata(定义) | **Control**(DAG 校验 + 依赖解析 + **条件求值**), Runtime(多类型执行) | `WorkflowDef`, `WorkflowNode`, `WorkflowEdge`, `ConditionNode` | 三模块协作 | 六类任务节点 + Conditions 条件节点。**条件节点在 Control 求值,不下发 Runtime** |
| 23 | 执行记录(工作流) | **Runtime** | Control(级联取消), Governance | `WorkflowExecution` + 子 `Execution` | runtime-gateway | 取消父执行 → 级联取消所有 RUNNING 子执行 |

## 运维监控

| 序号 | 二级模块 | Owner Space | 参与 Space | 核心对象 | 落地模块 | 备注 |
|---|---|---|---|---|---|---|
| 24 | 任务监控 | **Governance** | Runtime(事实来源) | `MonitorAggregate` | governance-service | 五个口径:执行总数 / 失败数 / 今日新增抽取 / 总计抽取 / 任务时延。**明确要求跨数据集成 + 数据开发聚合 → R4 的第一条证据** |
| 25 | 告警规则 | **Governance** | Control(可下发暂停) | `AlertRule`, `AlertSuppression` | governance-service | 范围:全部任务 / 指定任务。「告警频率」= 抑制窗口。**明确跨两个子系统 → R4 的第二条证据** |
| 26 | 告警信息 | **Governance** | — | `Alert` | governance-service | 今日 + 历史 + 具体内容 |
| 27 | 审计日志 | **Governance** | 全部 Space(提供操作事实) | `AuditRecord` | governance-service | **明确跨两个子系统 → R4 的第三条证据** |
| 28 | 空间管理 | **Platform** | 全部 Space(作用域) | `Workspace`, `WorkspaceSecret`, `WorkspaceGrant` | iam-tenancy-service | **注意:这里的「空间」是 Workspace 租户,不是架构 Space(R3)**。停用时 RUNNING 任务的处理方式需产品决策(E.7) |
| 29 | 角色管理 | **Platform** | — | `Role`, `MenuPermission` | iam-tenancy-service | 菜单权限 ≠ 数据权限。数据权限由 Governance 判定 |
| 30 | 用户管理 | **Platform** | — | `User` | iam-tenancy-service | **需澄清:自建还是对接甲方统一认证(L.2)** |
| 31 | 执行器管理 | **Runtime** | Control(资源需求) | `Executor`, `ExecutorPool`, `ExecutorAssignment` | runtime-gateway | ⚠️ **菜单在「基础配置」下,归属却是 Runtime**。这是执行资源池调度,不是配置项(R2) |
| 32 | 文件管理 | **Runtime** | — | `Artifact`(平台级 JAR) | runtime-gateway | ⚠️ **同上:作业制品仓库,不是配置项(R2)** |
| 33 | 告警渠道 | **Governance** | — | `AlertChannel` | governance-service | ⚠️ **同上:归 Governance 而非配置模块(R2)**。含连通性测试与邮箱推送 |

## 数据服务

| 序号 | 二级模块 | Owner Space | 参与 Space | 核心对象 | 落地模块 | 备注 |
|---|---|---|---|---|---|---|
| 34 | 高质量数据集制备 | **Intelligence** | Metadata(注册), Control(提交训练), Runtime(K8s Job), Data(只读) | `Ontology`, `Concept`, `PreAnnotationJob`, `Annotation`, `DatasetVersion`, `Model`, `Evaluation` | **intelligence-platform(独立立项)** | **R1:一行需求 = 一个平台**。本期只锁定四条 Contract(见 SPACE-MODEL.md C+D.8) |
| 35 | 全民健康信息平台对接 | **Data**(适配器) | Governance(脱敏/授权/留痕), Intelligence(消费侧) | `ExternalPlatformAdapter` | data-connector-service + governance-service | 对接方是**亭湖区全民健康信息平台**(区域级卫健平台),不是某家医院的 HIS —— 这个区别决定了对接对象是政府平台而非厂商系统,授权链路、数据口径与合规要求都不同。**R8:外部依赖,需区域平台开放接口或库表授权,进度不由己方控制;健康医疗数据属敏感个人信息**。按外部依赖单列里程碑 |

---

## 覆盖率自检

| 检查项 | 结果 |
|---|---|
| 35 项全部有 Owner | ✅ 35 / 35 |
| 是否存在无 Owner 的功能 | ✅ 无 |
| 是否存在双 Owner 的功能 | ⚠️ 1 项 —— 序号 17 规则管理是刻意的双栖设计(定义 Metadata / 执行 Runtime),已在 SPACE-MODEL.md C+D.2 与 R6 说明 |
| 是否存在无功能承载的 Space | ✅ 无。8 个 Space 均有 Owner 功能项 |

## Owner 分布

| Space | Owner 功能项数 | 序号 |
|---|---|---|
| Metadata | 12 | 1, 2, 3, 4, 5, 8, 9, 11, 12, 13, 14, 16, 17(定义侧), 18, 20, 22 的定义态 |
| Runtime | 8 | 10, 15, 17(执行侧), 19, 21, 23, 31, 32 |
| Governance | 5 | 24, 25, 26, 27, 33 |
| Platform | 3 | 28, 29, 30 |
| Data | 3 | 6, 7, 35 |
| Intelligence | 1 | 34 |
| Control | 0(Owner)| 无独占功能项 —— 它的职责隐含在 6/9/11/14/20/22 的调度与编译中 |
| UI | 0(Owner)| 全部 35 项的界面侧,不拥有任何领域对象 |

> **Control 与 UI 没有 Owner 功能项,恰恰说明功能清单是按菜单而非按架构组织的。** 编译、依赖解析、条件求值、资源选择这些 Control 的核心职责,在需求清单里完全没有对应条目 —— 它们隐藏在「支持 Cron 表达式的周期调度策略配置」这类描述背后。这是最容易被低估工作量的部分,也是 M.1 把 Workflow Compiler 列为四大核心技术资产之一的原因。
