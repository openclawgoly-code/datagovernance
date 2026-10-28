# AnalysisReport · 分析结论对象

> 归属：Governance / Trust Space · 状态：已实现（V13，2026-10-26） · 日期：2026-10-26
> 关联：`SPACE-MODEL.md` §C+D.6 / §E.9 / §F.6 / §J · `GOVERNANCE-MODEL.md` §G · `docs/space-contracts/governance.yaml`
> 首个消费者：DeepAnalyze（`~/projects/DeepAnalyze`，见其 `docs/integration/datagovernance.md`）

---

## 0. 一句话

`AnalysisReport` 是**外部分析 Agent 对平台事实的解释结论**，归 Governance 所有：不可变、带证据引用、可版本化、可撤回。

它回答的问题不是「数据是什么」（Metadata），也不是「任务怎么跑」（Control / Runtime），而是「**为什么**」——
为什么这个任务失败、为什么这个指标异常、这份数据能不能用。

---

## 1. 为什么不是 Artifact，也不是 Alert 的备注

| 候选落点 | 为什么不行 |
|---|---|
| Runtime `Artifact` | 制品是**可执行/可部署**的不可变内容（`ArtifactService.TYPES = {JAR, PYTHON, SQL, OTHER}`，`backend/dg-runtime/src/main/java/com/datagov/runtime/service/ArtifactService.java:46`）。报告塞进去会让「制品仓库」同时承载构建产物与结论，后续清理策略、权限、签名语义全部打架；对象归属也会落到 Runtime，Governance 读报告就要反向依赖 Runtime 的业务语义 |
| `Alert.resolutionNote` | 告警是规则触发的**瞬时通知**（`AlertRule → Alert`）。报告可以没有告警（ad-hoc 分析）、可以覆盖多个告警、需要版本与证据链；挂在 Alert 上等于把报告降级成备注 |
| OpenSearch `AuditRecord` | 审计是**行为留痕**（谁在何时做了什么），报告是**结论内容**。两者保留期不同（审计远长于报告），检索形态也不同 |

Governance 已有 `Provenance / LineageRecord / AuditRecord / PolicyDecision`，它们都是「关于事实的记录」；
`AnalysisReport` 是同一层的第五种记录：**关于事实的解释**。

---

## 2. 对象模型

### 2.1 `AnalysisReport`（聚合根）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 与 `AuditRecord` 同形态的 ID |
| `workspace_id` | string | 必填；行级隔离，ORM 强制注入 |
| `subject_type` / `subject_id` | enum / string | `EXECUTION / ALERT / JOB_DEF / DATASET / NONE`；`NONE` = 纯探索 |
| `title` | string(≤200) | 列表页标题 |
| `state` | enum | `PUBLISHED / RETRACTED` |
| `latest_version` | int | 指向 `AnalysisReportVersion.version` |
| `created_by` / `created_at` | string / instant | 来自 `WorkspaceContext`，**不接受请求体指定** |
| `correlation_id` | string | 每个 workspace 内唯一；幂等键，重放不产生新报告 |

### 2.2 `AnalysisReportVersion`（不可变内容）

| 字段 | 类型 | 说明 |
|---|---|---|
| `report_id` / `version` | string / int | `(report_id, version)` 唯一 |
| `supersedes_version` | int? | 上一版；首版为 null |
| `summary` | string(≤500) | 结论摘要，列表页只读它 |
| `body_md` | text(≤512 KB) | 正文；必须自包含，不允许「看附件才知道结论」 |
| `conclusion` | enum | `CONFIRMED / INCONCLUSIVE` |
| `insufficient_reason` | string? | `INCONCLUSIVE` 时必填 |
| `evidence` | json | 证据引用数组，非空（见 §4）；本期以 TEXT 存规范化 JSON |
| `model` | json | `{provider, name, version, endpointKind: SELF_HOSTED/EXTERNAL}`；本期以 TEXT 存 |
| `session_ref` | string? | 上游会话 ID（如 DeepAnalyze 的 `session_id`） |
| `max_sensitivity` | enum | `L1 / L2 / L3 / L4 / UNKNOWN`；写入方声明，服务端只校验取值域 |
| `content_digest` | string | 规范化 JSON 的 SHA-256；用于复现与后续锚定 |
| `generated_at` | instant | 模型产出时间，与 `created_at` 分开 |

---

## 3. 状态机

```text
AnalysisReport:        PUBLISHED ──RetractAnalysisReport──> RETRACTED

AnalysisReportVersion: CURRENT ──PublishAnalysisReportVersion──> SUPERSEDED
```

- 没有 `DRAFT`：草稿属于调用方（例如 DeepAnalyze 的 `sessions` 表），平台只收已发布的结论。
- 没有 `DELETE`：报告是事实，撤回用状态表达。
- `RETRACTED` 之后仍可查；列表默认过滤，`ListAnalysisReports(includeRetracted=true)` 可读。

---

## 4. 六条不变量

1. **不可变**：无 PUT / PATCH / DELETE；修订 = 新 version。
2. **证据非空**：`evidence.length ≥ 1`，除非 `conclusion = INCONCLUSIVE` 且给出 `insufficient_reason`。
3. **证据可寻址**：每条 evidence 必须有 `kind + ref`，`kind ∈ {SQL, EXECUTION, JOB_DEF, CATALOG, EVENT, AUDIT}`。
   `SQL` 的 ref 形如 `<datasourceId>#sha256:<digest>`，并附 `maxRows / truncated` —— **截断的查询不得支撑肯定性结论**。
4. **不承载原始行**：`body_md` 只允许聚合值、字段名、抽样样本（≤5 行且标注抽样方式）。服务端做大小与关键词校验，真正的防线在输入侧（见 §8）。
5. **不替代审计**：创建行为本身由 `AuditInterceptor` 记为 `CREATE`；报告正文不进审计索引。
6. **不产生 Command**：发布只发事件 `AnalysisReportPublished`，不触发任何 Space 的动作 —— 与「Governance 只发事件不发命令」一致。

---

## 5. API

```text
POST   /api/v1/governance/analysis-reports                        发布首版
POST   /api/v1/governance/analysis-reports/{id}/versions          发布新版本
GET    /api/v1/governance/analysis-reports?subjectType=&subjectId=&from=&to=&includeRetracted=
GET    /api/v1/governance/analysis-reports/{id}                   含全部版本
POST   /api/v1/governance/analysis-reports/{id}/retract           撤回
```

- 幂等：`correlation_id` 已存在时返回既有报告（200 + 同 id），不新建。
- 错误码沿用 `ErrorCode`：参数错 `SYS_VALIDATION_FAILED`，越权 `AUTH_FORBIDDEN`，不存在新增 `GOV_REPORT_NOT_FOUND`。
- 单次请求体上限 1 MB（`body_md` 512 KB + evidence 余量）。

---

## 6. 权限与事件

| 项 | 值 |
|---|---|
| 读 | `governance:analysis:read`（种子：`ws_admin` / `ws_developer` / `ws_viewer`） |
| 写 | `governance:analysis:write`（种子：`ws_admin` / `ws_developer`） |
| 事件 | `AnalysisReportPublished` / `AnalysisReportRetracted`（本期为 Spring 进程内事件；Kafka topic `dg.governance.report` 留给基础设施任务） |

写权限只按「人」授予，**不设服务账号**：调用方必须携带自己的 token，`created_by` 即 token 主体 —— 与 `GOVERNANCE-MODEL.md` §G 的「无服务账号」一致。

---

## 7. 存储

| 内容 | 位置 | 策略 |
|---|---|---|
| 报告与版本 | PostgreSQL `gv_analysis_report` / `gv_analysis_report_version` | append-only；按 `workspace_id` 行级隔离；正文 inline |
| 事件 | Spring 进程内事件 | 不落库也可重放；Kafka 桥接为演进项 |

不写 OpenSearch：审计索引的保留期与检索形态不适合承载正文。

---

## 8. 与 DeepAnalyze 的接法（唯一写入序）

```text
DeepAnalyze                                      datagovernance
   │ 1. GET  /datasources/{id}/catalog           元数据，只读
   │ 2. POST /datasources/{id}/query             聚合/样本，只读，≤2000 行
   │ 3. 沙箱内分析（代码/执行记录留在 DeepAnalyze）
   │ 4. POST /governance/analysis-reports        ← 唯一写入
   ▼
PUBLISHED ──AnalysisReportPublished──> UI / 后续 Intelligence
```

**输入侧才是数据闸门**：第 2 步只允许聚合与抽样，第 4 步只做结构校验。
把「不许出现原始行」寄托在报告正文的正则校验上必然失守 —— 这与 `GOVERNANCE-MODEL.md` 三约束里的「编译期边界」是同构推理。

---

## 9. 落地清单

| # | 改动 | 落点 | 状态 |
|---|---|---|---|
| 1 | 迁移 `gv_analysis_report` / `gv_analysis_report_version` | `backend/dg-app/src/main/resources/db/migration/V13__analysis_report.sql` | ✅ 已实现 |
| 2 | 实体 + Mapper | `backend/dg-governance/.../entity`、`.../mapper` | ✅ 已实现 |
| 3 | `AnalysisReportService`（幂等、版本、撤回、摘要计算） | `backend/dg-governance/.../service` | ✅ 已实现 |
| 4 | `AnalysisReportController` + 权限种子 | `backend/dg-app/.../controller`、`V13` | ✅ 已实现 |
| 5 | 事件发布 | `backend/dg-governance/.../event/GovernanceEvents.java` | ✅ 已实现（进程内） |
| 6 | 契约测试（幂等 / 不可变 / 证据非空） | `backend/dg-governance/src/test/.../domain/AnalysisReportPolicyTest.java`、`.../service/AnalysisReportServiceTest.java` | ✅ 已实现 |
| 7 | Space Contract 同步 | `docs/space-contracts/governance.yaml`（本批已改） | ✅ 已同步 |

> 任务分解、迁移草案（含 SQL 与权限种子）、测试矩阵、验收脚本见 `docs/plans/2026-10-26-analysis-report.md`。

---

## 10. 演进位（本期不做）

- **审核态**：`max_sensitivity ≥ L3` 的报告可要求第二人复核后再 `PUBLISHED`。
- **锚定**：`content_digest` 可外锚（TSA / 链），平台当前无此基础设施，先留字段。
- **结构化结论**：当前正文是 Markdown；若 Intelligence 要消费，再补 `findings[]` 数组。
