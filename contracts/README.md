# Phase 0 · 契约冻结(datagov ⇄ dataQA)

> INTEGRATION-ROADMAP Phase 0 的交付物。冻结 datagov 与 dataQA 之间的接口形状,使 dataQA
> 的适配器 SPI 与 datagov 侧接口**编译期对齐**,并列出 3 个待客户方冻结的取值域。
> 状态:初版 · 待评审 · 日期:2026-09-17

## 1. 文件

| 文件 | 内容 |
|---|---|
| [`openapi/datagov-dataqa.yaml`](openapi/datagov-dataqa.yaml) | datagov 暴露给 dataQA 的 REST 契约(同步 Q/C) |
| [`events/datagov-events.yaml`](events/datagov-events.yaml) | datagov → dataQA 的 Kafka 事件契约(异步 E) |
| 本文件 | 对齐表、UserContext 透传规范、冻结清单、契约测试、Phase 0 判据 |

**契约形状以 dataQA 真实 SPI 为准**(`~/projects/dataQA/backend/dq-capability-spi`),不是凭空设计。datagov 侧对应 §F 命令/查询为 SPACE-MODEL 目标设计,落地由 datagov 完成。

## 2. SPI ⇄ 契约对齐表(Phase 0 判据的核对基准)

| dataQA SPI 方法 | OpenAPI operationId | datagov §F | Phase |
|---|---|---|---|
| `MetadataAdapter.listTables()` | `listTables` | GetSchemaTree (F.2) | 1 |
| `MetadataAdapter.getTable(TableRef)` | `getTable` | GetSchemaTree (F.2) | 1 |
| `MetadataAdapter.getCodeValues(codeSetId)` | `getCodeValues` | 【FREEZE-codeset】 | 1 |
| `IdentityAdapter.resolve(oaToken)` | `resolveIdentity` | GetUser (F.1) + GetDataScope | 1 |
| `SchedulerAdapter.createQualityTask(req)` | `createQualityTask` | CreateBatchDevJobDef+BindSchedule+PublishDefinition (F.2) | 2 |
| `SchedulerAdapter.listTasksBySourceTag(tag)` | `listTasksBySourceTag` | (来源标记筛选) | 2 |
| `SchedulerAdapter.listFailedInstances(date)` | `listFailedInstances` | ListExecutions (F.4) | 3 |
| `SchedulerAdapter.getExecutionHistory(id,from,to)` | `getExecutionHistory` | ListExecutions/GetExecution (F.4) | 3 |
| `LogAdapter.getTaskLog(taskId,instanceId)` | `getTaskLog` | GetExecutionLog (F.4) | 3 |
| `CodeAdapter.getTaskCode(taskId)` | `getTaskCode` | GetJobDef (F.2) | 3 |
| (GOVERNANCE-MODEL Lineage 原语) | `getLineage` | GetLineage/GetJobDataSourceRelations 序号8 (F.2) | 3 |

**未映射为 datagov 端点的 SPI 方法**:`IdentityAdapter.authenticate(username,password)` 由院方 OA 做,datagov 不代理(自建账号=违反 ADR-005);`resolveByAccount(oaAccount)` 供后台批量作业,复用 `resolveIdentity` 的按账号变体。POC 无真实 OA 时用本地 stub 顶替,不改 SPI。

## 3. UserContext 透传规范

dataQA 的 SPI 方法**不带 UserContext** —— 适配器实例由 `GuardedAdapterFactory` 创建时绑定用户(无用户在类型层面就构造不出适配器)。跨系统时透传落在**传输层**:

1. dataQA 适配器实现在每个到 datagov 的请求头带 `Authorization: Bearer <credentialToken>`。`credentialToken` = `UserContext.credentialToken`(院方 OA 签发),**适配器只透传不解析**。
2. datagov 从 header 取 token → `resolveIdentity` 得身份 + `dataScopes` → 按 `dataScopes` 过滤元数据(`listTables`/`getTable` 越权表返回 404/不出现)、判定写权限(`createQualityTask` 无 `quality_rule.publish` = 403)。
3. 可选 `X-DataQA-Scope`(= `UserContext.describeScope()`,不含明文凭证)供跨系统 trace 对账。
4. **无 token = 401,绝不回退服务账号**。datagov 侧对每次调用记 `AuditRecord`,dataQA 侧记 `ToolCall` Trace,同一 `oaAccount` 可跨系统对账。

判定归属:数据/资源在 datagov,故**跨系统访问判定由 datagov Governance `EvaluateAccess` 做**(与 GOVERNANCE-MODEL §C.5 一致);dataQA 内部动作由 dataQA 自己的 Governance 判定。

## 4. 待客户方冻结清单(Phase 0 必须闭合)

| 标记 | 冻结什么 | 阻塞 | 建议默认 | 谁定 |
|---|---|---|---|---|
| **FREEZE-rule**(decision #1) | `QualityTaskRequest` 的 `dimension`/`period`/`priority`/`alertCondition` **线上值形统一** —— dataQA 现发中文(唯一性/每日/高),需定目标值域并给「中文→目标」映射 | `createQualityTask`、验收 1.2–1.4、Pact 契约 | dimension 统一为 wireName 小写(uniqueness…);period=cron 或 D/W/M;priority=HIGH/MEDIUM/LOW | 客户调度平台 + dataQA(改 `PublishService` 发 wireName) |
| **FREEZE-scope**(decision #2) | `DataScope.scopeType` 粒度;SPI 现为 DB/TABLE/ROW(行列级并为单个 ROW) | 所有只读端点的过滤、`resolveIdentity` 返回 | TABLE 级(POC);列级需先改 dataQA SPI 再双边同步 | 客户数据权限体系 |
| **FREEZE-oa**(decision #4) | OA 协议(OIDC/CAS/LDAP) | `resolveIdentity`、身份透传 | OIDC(Bearer 透传最自然) | 客户 OA |
| FREEZE-codeset(技术,非客户) | 码集查询由 datagov 登记 Standard 后提供 | `getCodeValues` | MVP 经 Data 受控查询读 `ref_dw_cd_val`;长期登记 Standard | 两边工程 |

## 5. 契约测试方案(不写业务实现)

- **Pact**:消费者 dataQA 按每个 SPI 方法写期望 → 生成 pact;提供者 datagov 验证。两边都 Spring,亦可 Spring Cloud Contract。
- **Phase 0 必过的 3 个契约**:`createQualityTask`(唯一写入)、`listTables`+`getTable`(只读+越权=404)、`resolveIdentity`(身份+dataScopes)。
- **CI**:datagov 侧 provider verification 纳入门禁;契约不一致即 CI 失败(比照 dataQA `space-contracts/verify.py` 的做法)。

## 6. Phase 0 完成判据(可证伪)

1. dataQA 5 个适配器 SPI 能以本 OpenAPI 生成的客户端**编译期对齐**(方法签名、DTO 字段、错误码一一对应),用一个「生成客户端 + 编译」CI 步骤验证。
2. 上述 3 个 Pact 契约用**真实载荷**跑通桩(尤其 `createQualityTask` 的 `dimension`/`period`/`priority` 线上值形须先经 FREEZE-rule 统一,否则枚举映射失败)。
3. 3 个 FREEZE 项有书面结论(客户签字或建议默认被接受),**无遗留 TBD 进入 Phase 1**。

判据未达成不进入 Phase 1(只读打通)。

## 7. 与上游文档

- [`../INTEGRATION-ROADMAP.md`](../INTEGRATION-ROADMAP.md) §E Phase 0 / §F 契约冻结清单 —— 本目录是其落地。
- [`../GOVERNANCE-MODEL.md`](../GOVERNANCE-MODEL.md) —— 原语定义;`EvaluateAccess`/`Lineage`/分级 的归属以其为准。
- [`../SPACE-MODEL.md`](../SPACE-MODEL.md) §F —— datagov 侧 Command/Query/Event 来源(目标设计)。
- dataQA `dq-capability-spi` —— 契约形状的权威依据;`decision.md` 待澄清清单 —— FREEZE 项来源。
