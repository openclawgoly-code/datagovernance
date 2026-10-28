# AnalysisReport 实施计划

> 状态：已执行（2026-10-26） · 日期：2026-10-26 · 依据：`docs/analysis-report.md`（契约定稿）
> 范围：后端（迁移 + 领域 + 服务 + API + 权限 + 测试） · 不含前端页面与 Kafka 桥接
> 规模：约 3 人日（单人串行，含测试）

---

## 0. 完成定义（DoD）

- [x] 空库启动时 Flyway 依次执行到 `V13` 成功，`gv_analysis_report*` 两表存在
- [x] `mvn -pl backend/dg-app -am test` 全绿
- [x] 5 个端点 curl 通过：发布、发版本、详情、列表、撤回
- [x] 幂等、不可变、`truncated + CONFIRMED` 拒绝、跨空间 404 四条负向用例有测试
- [x] `python3 docs/space-contracts/verify.py` 仍 PASSED
- [x] `docs/analysis-report.md` §9 勾选，状态改为「已实现」

---

## 1. 已确认的约束（读代码得出，不是假设）

| # | 事实 | 对计划的影响 |
|---|---|---|
| 1 | `V12__intelligence_contract_and_masking.sql` 已存在（registry + relation edge + 权限） | 新迁移必须是 **V13**，不能写 V12 |
| 2 | 全仓 **没有 Kafka 依赖**；Runtime / Metadata 的事件走 Spring `ApplicationEventPublisher`（`ExecutionEvents`、`DataSourceEvents`） | 本期事件同构：进程内 `GovernanceEvents`；Kafka 桥接留给基础设施任务 |
| 3 | `ErrorCode` 里 **没有 `GOV_`**（类注释说 P4 加，但实际未加） | 新增 `GOV_REPORT_NOT_FOUND`；其余复用 `SYS_VALIDATION_FAILED` / `SYS_ILLEGAL_STATE_TRANSITION` |
| 4 | `AuditInterceptor.RESOURCE_MAP`（`backend/dg-app/.../web/AuditInterceptor.java:40-55`）是白名单 | 不加 `/governance/analysis-reports`，创建与撤回**不会进审计** |
| 5 | `CreatedResourceAdvice` 从响应体反射 `id` 字段 | 创建接口的响应 DTO 必须含 `id`，否则审计只能记「有人建了个报告」 |
| 6 | 权限种子在迁移里，模板是 `V11:163-179` | 沿用 `pf_permission` + `pf_role_permission` 两段式 |
| 7 | 内置角色只有 `ws_admin` / `ws_developer` / `ws_viewer`，**没有 `hq_admin`** | `docs/analysis-report.md` §6 的种子清单需同步修正 |
| 8 | 测试栈是 JUnit 5 + Mockito + AssertJ（`DataSourceServiceTest`），没有 MockMvc 集成测试先例 | 领域规则 + 服务编排用单测；HTTP 层用验收脚本覆盖 |
| 9 | 主键用 `Ids.of(prefix)`（ULID） | 报告 `arpt_`，版本 `arptv_` |
| 10 | `WorkspaceContext.require()` 提供 `userId()` / `workspaceId()`；`requireWorkspaceId()` 用于过滤 | 所有查询强制 `workspace_id`，`created_by` 取 token 主体 |

---

## 2. 实现决策（契约里没写死的部分）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **版本状态不落列**：`CURRENT / SUPERSEDED` 由 `version = latest_version` 派生 | 双写必然漂移；列表与详情都只读 `latest_version` |
| 2 | **幂等靠唯一索引 + `ON CONFLICT DO NOTHING`**：`uk_gv_report_corr(workspace_id, correlation_id)`，冲突时把既有行查回 | 先查后插在并发下会双写；且 PostgreSQL 在唯一约束冲突后会让整个事务进入 aborted 状态，不能像 MySQL 那样 catch 后继续查询。`ON CONFLICT DO NOTHING` 是唯一既不中断事务、又只写一次的做法 |
| 3 | **版本并发用条件更新**：`UPDATE ... SET latest_version = ? WHERE id = ? AND latest_version = ?`，0 行则重试一次，仍失败返回 409 | 不用悲观锁，也不引入 `@Version`（报告写入频率低，重试足够） |
| 4 | **不可变靠边界**：Service 不暴露任何内容更新方法；版本 Mapper 只 insert，聚合根只允许更新 `state` / `latest_version` | DB 触发器会让迁移与排障复杂化；先用最小机制 + 测试守住 |
| 5 | **`content_digest` 规范化**：固定字段顺序的 `LinkedHashMap` → Jackson → SHA-256 | `Map.of` 顺序不稳定，digest 会随机变化 |
| 6 | **证据校验在服务端**：`kind` 白名单、`SQL` ref 正则、`evidence ≤ 50`、`truncated=true && CONFIRMED` 拒绝 | 这些是结构事实，能确定性校验；正文数字的语义校验做不到，不假装能做 |
| 7 | **`INCONCLUSIVE` 必填 `insufficientReason`**，DB 加 CHECK 兜底 | 「证据不足」必须是一等终态，不是失败 |
| 8 | **撤回幂等**：已撤回再撤返回当前视图，不报错 | 重试是客户端常态 |
| 9 | **列表默认排除 `RETRACTED`**，`includeRetracted=true` 才显示 | 撤回的结论不应出现在默认视野里，但必须可查 |
| 10 | **本期不做附件**：§7 的「大附件经 Artifact 引用」改为演进项 | 对象模型里没有附件字段，写了就是空头支票 |
| 11 | **事件在写库之后、事务之内发布**，与 `ExecutionService` 一致 | 与全仓现有模式一致；`AFTER_COMMIT` 是后续统一改造的事 |

---

## 3. 迁移 `V13__analysis_report.sql`

### 3.1 表

```sql
-- P6:外部分析 Agent 的结论归口(见 docs/analysis-report.md)
--
-- 为什么不是 rt_artifact:制品是<b>可执行/可部署</b>内容,报告是<b>结论</b>。
-- 混在一起会让清理策略、权限、签名语义打架;更根本的是对象归属错位 ——
-- 报告是"关于事实的解释",与 Provenance/AuditRecord 同层,归 Governance。
--
-- 为什么单独一张版本表:报告不可变,修订必须是新版本。否则"上周的结论
-- 是什么"这个问题无从回答,而它恰恰是复盘时最常被问到的。

CREATE TABLE gv_analysis_report (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40)  NOT NULL,
  subject_type    VARCHAR(24)  NOT NULL,
  subject_id      VARCHAR(64),
  title           VARCHAR(200) NOT NULL,
  state           VARCHAR(24)  NOT NULL DEFAULT 'PUBLISHED',
  latest_version  INTEGER      NOT NULL DEFAULT 1,
  correlation_id  VARCHAR(80)  NOT NULL,
  created_by      VARCHAR(40)  NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  retracted_at    TIMESTAMPTZ,
  retracted_by    VARCHAR(40),

  CONSTRAINT uk_gv_report_corr UNIQUE (workspace_id, correlation_id),
  CONSTRAINT ck_gv_report_state CHECK (state IN ('PUBLISHED', 'RETRACTED')),
  CONSTRAINT ck_gv_report_subject
    CHECK (subject_type IN ('EXECUTION', 'ALERT', 'JOB_DEF', 'DATASET', 'NONE'))
);

CREATE INDEX idx_gv_report_subject
  ON gv_analysis_report (workspace_id, subject_type, subject_id, created_at DESC);
CREATE INDEX idx_gv_report_list
  ON gv_analysis_report (workspace_id, created_at DESC);

CREATE TABLE gv_analysis_report_version (
  id                  VARCHAR(40)  PRIMARY KEY,
  report_id           VARCHAR(40)  NOT NULL REFERENCES gv_analysis_report(id),
  workspace_id        VARCHAR(40)  NOT NULL,
  version             INTEGER      NOT NULL,
  supersedes_version  INTEGER,
  summary             VARCHAR(500) NOT NULL,
  body_md             TEXT         NOT NULL,
  conclusion          VARCHAR(24)  NOT NULL,
  insufficient_reason VARCHAR(500),
  evidence_json       TEXT         NOT NULL,
  model_json          TEXT         NOT NULL,
  session_ref         VARCHAR(128),
  max_sensitivity     VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
  content_digest      CHAR(64)     NOT NULL,
  generated_at        TIMESTAMPTZ,
  created_by          VARCHAR(40)  NOT NULL,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

  CONSTRAINT uk_gv_report_version UNIQUE (report_id, version),
  CONSTRAINT ck_gv_report_conclusion
    CHECK (conclusion IN ('CONFIRMED', 'INCONCLUSIVE')),
  CONSTRAINT ck_gv_report_sensitivity
    CHECK (max_sensitivity IN ('L1', 'L2', 'L3', 'L4', 'UNKNOWN')),
  CONSTRAINT ck_gv_report_inconclusive
    CHECK (conclusion = 'CONFIRMED' OR insufficient_reason IS NOT NULL)
);
```

### 3.2 权限种子

```sql
-- 不新增菜单:分析报告的主要入口是告警/执行详情页的"解释"，
-- 单独开 /ops/analysis 而前端没有对应路由，会变成一个死链。
-- UI 排期时再把这两条 ACTION 挂到新菜单下。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:analysis:read',  '查看分析报告', 'ACTION', 'menu:ops:alert', 3, 'GOVERNANCE'),
  ('governance:analysis:write', '发布分析报告', 'ACTION', 'menu:ops:alert', 4, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'governance:analysis:read'),
  ('role_builtin_ws_admin',     'governance:analysis:write'),
  ('role_builtin_ws_developer', 'governance:analysis:read'),
  ('role_builtin_ws_developer', 'governance:analysis:write'),
  ('role_builtin_ws_viewer',    'governance:analysis:read');
```

> 写权限按「人」授予，**不设服务账号** —— 调用方必须带自己的 token，`created_by` 即 token 主体。

---

## 4. 代码清单

| # | 文件 | 内容 |
|---|---|---|
| 1 | `backend/dg-app/src/main/resources/db/migration/V13__analysis_report.sql` | 上节 DDL + 种子 |
| 2 | `backend/dg-common/.../error/ErrorCode.java` | 追加 `GOV_REPORT_NOT_FOUND("GOV_REPORT_NOT_FOUND", 404, "分析报告不存在")` |
| 3 | `backend/dg-governance/.../domain/ReportState.java` | `PUBLISHED / RETRACTED` |
| 3 | `backend/dg-governance/.../domain/ReportConclusion.java` | `CONFIRMED / INCONCLUSIVE` |
| 3 | `backend/dg-governance/.../domain/ReportSubjectType.java` | 5 个取值 |
| 3 | `backend/dg-governance/.../domain/EvidenceKind.java` | `SQL / EXECUTION / JOB_DEF / CATALOG / EVENT / AUDIT` |
| 4 | `backend/dg-governance/.../domain/AnalysisReportPolicy.java` | 纯函数校验：evidence 非空、SQL ref 正则、truncated 规则、大小上限、INCONCLUSIVE 原因、digest 计算 |
| 5 | `backend/dg-governance/.../entity/GovernanceEntities.java` | 追加嵌套 `AnalysisReport` / `AnalysisReportVersion` |
| 6 | `backend/dg-governance/.../mapper/AnalysisReportMapper.java` | `BaseMapper<AnalysisReport>` |
| 6 | `backend/dg-governance/.../mapper/AnalysisReportVersionMapper.java` | `BaseMapper<AnalysisReportVersion>` |
| 7 | `backend/dg-governance/.../event/GovernanceEvents.java` | `AnalysisReportPublished` / `AnalysisReportRetracted` 两个 record（自足，含 workspaceId / reportId / version / occurredAt） |
| 8 | `backend/dg-governance/.../service/AnalysisReportService.java` | 5 个用例 + 嵌套 DTO（沿用 `AlertService.AlertView` 风格） |
| 9 | `backend/dg-app/.../controller/AnalysisReportController.java` | `@RequestMapping("/api/v1/governance/analysis-reports")`，5 个端点 |
| 10 | `backend/dg-app/.../web/AuditInterceptor.java` | `RESOURCE_MAP` 增加 `/governance/analysis-reports` → `ANALYSIS_REPORT` / `GOVERNANCE` |
| 11 | `backend/dg-governance/src/test/.../domain/AnalysisReportPolicyTest.java` | 规则单测 |
| 11 | `backend/dg-governance/src/test/.../service/AnalysisReportServiceTest.java` | 幂等 / 版本 / 撤回 / 跨空间 |

---

## 5. API 形状

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/governance/analysis-reports` | `governance:analysis:write` | 发布首版；幂等 |
| POST | `/api/v1/governance/analysis-reports/{id}/versions` | `governance:analysis:write` | 发布新版本 |
| GET | `/api/v1/governance/analysis-reports` | `governance:analysis:read` | 分页 + `subjectType/subjectId/from/to/includeRetracted` |
| GET | `/api/v1/governance/analysis-reports/{id}` | `governance:analysis:read` | 含全部版本 |
| POST | `/api/v1/governance/analysis-reports/{id}/retract` | `governance:analysis:write` | 幂等撤回 |

请求（发布）：

```json
{
  "subject": { "type": "EXECUTION", "id": "exec_..." },
  "title": "订单同步失败根因",
  "summary": "源表新增非空列导致写入失败",
  "bodyMd": "...",
  "conclusion": "CONFIRMED",
  "evidence": [
    { "kind": "SQL", "ref": "ds_1#sha256:...", "maxRows": 500, "truncated": false },
    { "kind": "EXECUTION", "ref": "exec_..." }
  ],
  "model": { "provider": "deepseek", "name": "DeepSeek-V4.1-Flash", "version": "2026-10", "endpointKind": "EXTERNAL" },
  "sessionRef": "da-...",
  "correlationId": "uuid",
  "maxSensitivity": "L2",
  "generatedAt": "2026-10-26T09:00:00Z"
}
```

响应 `ReportView` 必须含 `id`（审计用），并在详情的 `versions[]` 里给出每个版本的 `version / conclusion / contentDigest / createdAt`。列表只带 `latest`（当前版本的摘要与结论），详情带全部 `versions[]`。

---

## 6. 服务端校验规则

| 规则 | 违反时 |
|---|---|
| `evidence.length >= 1`，除非 `INCONCLUSIVE` 且有 `insufficientReason` | 400 `SYS_VALIDATION_FAILED` |
| `evidence.length <= 50` | 400 |
| `kind` 必须在白名单 | 400 |
| `SQL` 的 `ref` 匹配 `^[A-Za-z0-9_-]+#sha256:[0-9a-f]{64}$` | 400 |
| `SQL` 必须带 `maxRows` 与 `truncated` | 400 |
| `truncated == true` 且 `conclusion == CONFIRMED` | 400（截断的查询不得支撑肯定性结论） |
| `title <= 200`、`summary <= 500`、`bodyMd <= 512 KB` | 400 |
| `maxSensitivity ∈ {L1..L4, UNKNOWN}` | 400 |
| 报告已 `RETRACTED` 时发新版本 | 409 `SYS_ILLEGAL_STATE_TRANSITION` |
| 跨空间读取 | 404 `GOV_REPORT_NOT_FOUND`（不泄露存在性） |

---

## 7. 测试矩阵

| # | 用例 | 期望 |
|---|---|---|
| 1 | 最小合法发布 | `PUBLISHED`，`latestVersion=1`，digest 为 64 位 hex |
| 2 | 同 `correlationId` 重放 | 返回同一 `id`，版本数不变 |
| 3 | `CONFIRMED` 但 evidence 为空 | 400 |
| 4 | `INCONCLUSIVE` 无 `insufficientReason` | 400 |
| 5 | evidence kind 非法 | 400 |
| 6 | SQL ref 格式错 | 400 |
| 7 | `truncated=true` + `CONFIRMED` | 400 |
| 8 | `bodyMd` 超 512 KB | 400 |
| 9 | 发布新版本 | `latestVersion=2`；旧版本按 `version < latest` 派生为 `SUPERSEDED` |
| 10 | 对已撤回报告发版本 | 409 |
| 11 | 撤回两次 | 第二次 200，状态仍 `RETRACTED` |
| 12 | 列表默认排除撤回 | 撤回项不出现；`includeRetracted=true` 出现 |
| 13 | 跨空间按 id 读取 | 404 |
| 14 | `content_digest` 稳定性 | 同一内容两次计算相等；改一个字段即变化 |

> 权限本身由 `@RequirePermission` 拦截器保证，用验收脚本验证（viewer 写返回 403），不写进单测。

---

## 8. 验收脚本

```bash
LOGIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"..."}')
TOKEN=$(echo "$LOGIN" | jq -r .data.token)
WS=$(echo "$LOGIN" | jq -r .data.currentWorkspaceId)
H=(-H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: $WS" -H 'Content-Type: application/json')

# 发布
curl -s "${H[@]}" -X POST http://localhost:8080/api/v1/governance/analysis-reports \
  -d @report.json | jq

# 重放同一 correlationId → 同 id
curl -s "${H[@]}" -X POST http://localhost:8080/api/v1/governance/analysis-reports \
  -d @report.json | jq '.data.id, .data.latestVersion'

# 列表 / 详情 / 撤回
curl -s "${H[@]}" "http://localhost:8080/api/v1/governance/analysis-reports?subjectType=EXECUTION" | jq
curl -s "${H[@]}" "http://localhost:8080/api/v1/governance/analysis-reports/$ID" | jq
curl -s "${H[@]}" -X POST "http://localhost:8080/api/v1/governance/analysis-reports/$ID/retract" | jq

# viewer 写 → 403
```

---

## 9. 执行顺序与排期

| 天 | 任务 | 产出 |
|---|---|---|
| D1 上午 | T1 迁移 V13 + T2 错误码 + T3 领域枚举 | 空库可启动，`mvn -q install -DskipTests` 通过 |
| D1 下午 | T4 策略类 + T5 实体/Mapper | 领域单测先绿 |
| D2 上午 | T6 事件 + T7 Service（发布/版本/撤回） | 服务单测绿 |
| D2 下午 | T8 Controller + 审计映射 + 权限 | curl 五个端点通 |
| D3 上午 | T9 测试补齐（幂等/跨空间/边界） | `mvn test` 全绿 |
| D3 下午 | T10 验收脚本 + 文档回填 | DoD 全部勾选 |

---

## 10. 风险与回滚

| 风险 | 处理 |
|---|---|
| 幂等竞态 | `ON CONFLICT DO NOTHING` 兜底，0 行时查回既有报告；串行重放与并发插入共用同一条路径 |
| 版本号并发 | 条件更新 + 一次重试；仍失败返回 409，不静默覆盖 |
| 正文过大占内存 | 入口先校验长度，再解析；不依赖 Tomcat 默认限制 |
| 审计噪音 | 只记录创建/撤回两次写操作；GET 不进审计（拦截器本来就跳过 GET） |
| 存量令牌不带新权限 | 权限码在登录时解析进令牌；V13 上线后用户需重新登录（或等旧令牌过期）才会带上 `governance:analysis:*` |
| 报告正文泄密 | 审计只记 `action/target`，不记 body；正文不进日志 |
| 事件无人消费 | 与全仓现状一致（31 个 orphan）；Kafka 桥接是后续基础设施任务 |
| 回滚 | 无 Flyway undo：`DROP TABLE gv_analysis_report_version, gv_analysis_report; DELETE FROM pf_role_permission WHERE permission_code LIKE 'governance:analysis:%'; DELETE FROM pf_permission WHERE code LIKE 'governance:analysis:%';` 先备份 |

---

## 11. 明确不做

- 前端页面 / 新菜单（权限先挂在 `menu:ops:alert` 下）
- Kafka 事件桥接
- 审核态（`sensitivity >= L3` 的二次复核）
- 报告正文数字的自动回溯校验（只做 evidence 结构校验）
- 附件上传 / 外锚（`content_digest` 字段先留）

---

## 12. 交付后回填

1. `docs/analysis-report.md`：§9 全部勾选，状态改「已实现」，§7 去掉附件行（本期不做）。
2. `docs/integration/datagovernance.md`（DeepAnalyze 仓库）：§1.1 报告写入从「契约定稿，实现待排期」改为「可用」。
3. 若接口形状与本文有偏差，以代码为准并回写本文，避免第二份真相。
