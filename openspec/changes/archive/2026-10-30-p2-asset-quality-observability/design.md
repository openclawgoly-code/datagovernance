## Context

Catalog 当前保存行数、大小、schema hash、更新时间，但这些只是采集事实，不能表达业务可接受阈值，也没有质量历史。

平台已有 `DataAccessGateway.executeQuery` 与只读 SQL 护栏，可以在不新增执行引擎的前提下运行有限的元数据/聚合检查。P2 先做内置规则和手动运行，不把质量做成通用 SQL 平台。

## Goals / Non-Goals

**Goals:**

- 对数据集/字段配置内置质量规则；
- 手动运行单条规则或整个数据集；
- 保存运行结果和质量分数；
- 资产详情可看最新质量与历史；
- 质量总览能定位失败资产；
- 规则与运行可审计。

**Non-Goals:**

- 不做内容采样、PII 识别、数据画像；
- 不做自定义 SQL 规则；
- 不做自动调度（P3 可复用现有调度）；
- 不做异常检测/ML；
- 不把质量结果写入告警（P3）；
- 不做 SLA/工单。

## Decisions

### 1. 两张表

```text
md_quality_rule
  id, workspace_id, dataset_id, column_id nullable,
  rule_type, config_json, severity, enabled,
  created_at, created_by, updated_at, updated_by

md_quality_run
  id, workspace_id, rule_id, dataset_id,
  status RUNNING/SUCCEEDED/FAILED,
  passed boolean, metric_json, violations bigint,
  error_message, started_at, finished_at, duration_ms,
  executed_by
```

规则是配置，运行是不可变事实；不在规则行上覆盖上次结果。

### 2. 内置规则与配置

| 类型 | 粒度 | config | 判定 |
|---|---|---|---|
| `ROW_COUNT_RANGE` | dataset | `{min,max}` | 行数在区间内 |
| `NOT_NULL` | column | `{maxNullRatio}` | null 比例 ≤ 阈值 |
| `UNIQUE` | column | `{maxDuplicateRatio}` | 重复比例 ≤ 阈值 |
| `FRESHNESS` | column | `{maxAgeHours}` | max(column) 距今 ≤ 小时数 |
| `SCHEMA_DRIFT` | dataset | `{baselineSchemaHash}` | 当前 schemaHash 等于基线 |

`NOT_NULL`/`UNIQUE`/`FRESHNESS` 必须指定 column 且字段属于该 dataset；其他类型禁止 column。

### 3. 执行器

`QualityRunner` 按规则生成只读聚合 SQL，复用 `DataAccessGateway.executeQuery`：

- 目标数据源配置从现有 datasource/connection 解析，不在质量表里保存连接信息；
- 标识符按平台已有 quoting 规则包起来，禁止把用户输入拼进 SQL；
- 单次执行有超时和最大耗时；超时/不支持的类型写 `FAILED` 与明确 errorMessage；
- `SCHEMA_DRIFT` 不查数据，只比较 Catalog 当前 schemaHash。

P2 只支持能通过 DataAccessGateway 执行 SQL 的数据源；文件/HTTP 等不支持的类型明确失败，不伪造成功。

### 4. 质量分数

只取每条规则最新一次运行：

```text
weight = CRITICAL 5 / HIGH 3 / MEDIUM 2 / LOW 1
score = round(100 * sum(weight of passed rules) / sum(weight of rules with runs))
```

- 没有规则或没有任何运行时 `score=null`，UI 显示“未评估”；
- 分数只代表配置规则，不代表全量数据质量；
- 详情展示规则明细和最近运行，不只展示一个数字。

### 5. API 与权限

```text
GET/POST/PUT/DELETE /metadata/quality/rules        quality:read/manage
POST /metadata/quality/rules/{id}/run              quality:manage
POST /metadata/quality/datasets/{id}/run           quality:manage
GET  /metadata/quality/datasets/{id}/summary       quality:read
GET  /metadata/quality/runs                        quality:read
```

新增 `metadata:quality:read/manage`；admin/developer manage，viewer read。

### 6. 前端

- 资产详情新增“质量”Tab：分数、规则列表、最新结果、运行按钮；
- `/metadata/quality` 总览：失败资产、最近运行、筛选规则类型/状态；
- 规则编辑抽屉使用表单，按 rule_type 显示不同 config 字段（后端元数据驱动优先）；
- 复用 `FilterBar`、`StatusTag`、`MetadataPanel`；
- 无 manage 权限不显示编辑/运行。

### 7. 审计

`/quality` → `QUALITY/GOVERNANCE`；规则写操作 UPDATE/CREATE/DELETE，run 记 EXECUTE。

## Risks / Trade-offs

- [长查询拖垮数据源] → 超时、最大行数/时间、手动触发，不做全库并发扫描。
- [标识符注入] → 只使用 Catalog 字段名并严格 quoting，不接受自由 SQL。
- [分数误导] → 展示规则明细和“仅覆盖已配置规则”说明。
- [不支持的数据源] → 返回 FAILED 和原因，不把未知当通过。
- [质量结果与告警重复] → P2 不接告警，P3 复用现有 alert/channel。
- [调度缺失] → 手动运行；P3 用现有 job 调度。

## Migration Plan

1. V29 建规则/运行表、权限；
2. 后端发布 CRUD/run/summary；
3. 前端发布质量 Tab 和总览；
4. 回滚隐藏质量菜单/Tab，保留历史运行。

## Open Questions

- P3 是否将质量失败接入告警渠道；
- 是否需要规则模板/批量应用到多个资产；
- 是否需要对 Iceberg/Delta 等表格式使用专门 profiling。
