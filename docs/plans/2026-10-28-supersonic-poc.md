# SuperSonic AI 问数 POC（Phase 1）

- 日期：2026-10-28
- 状态：ClickHouse 50/50、StarRocks 50/50 PASS；Phase 2 问数网关、前端入口、CSV/XLSX 导出、看板已落地；待真正 External Catalog、用户级映射 UI/SSO 和看板细化
- 关联：`docs/plans/2026-10-26-catalog-lakehouse-lineage.md` §20
- 部署说明：`deploy/supersonic/README.md`

## 1. 决策

### 1.1 第一阶段不部署 PI

SuperSonic 负责“受治理的问数”：

- 语义层：指标、维度、同义词、默认聚合；
- Chat BI：自然语言 → 语义模型 → SQL → 图表；
- BI：图表、看板、导出；
- 企业权限：SuperSonic 自带账号/权限体系。

PI（`git@github.com:earendil-works/pi.git`）是另一层：

- MIT，Node.js ≥ 22.19；
- 极简、可扩展的 **Agent Harness**，不是 BI 产品；
- 提供 Agent 循环、工具、Skills、MCP、SDK/RPC；
- **没有内置权限系统**，默认拥有启动用户的文件/进程/网络权限，生产必须容器化/沙箱化；
- 适合开放式、多步分析，不适合作为“问数 + 图表 + 看板”的现成产品。

结论：**POC 阶段 SuperSonic 单栈；PI 作为 Phase 3 可选的 Agent 编排层，不作为第一阶段依赖。**

### 1.2 两者不是替代关系

| 能力 | SuperSonic | PI |
|---|---|---|
| 自然语言问数 | 核心能力 | 需自己接工具 |
| 语义层/指标口径 | 内置 | 不提供 |
| SQL 生成/校验 | 内置 | 通过工具 |
| 图表/看板 | 内置 BI UI | 需自己实现 |
| 多步 Agent/工具编排 | 有限 | 强项 |
| MCP/Skills/记忆 | 弱 | 强项 |
| 企业权限/审计 | 自带 | 无内置，需自建 |
| 业务人员开箱 UI | 有 | 主要是 TUI/CLI/SDK |

如果未来需要“帮我分析为什么下降”这类开放式多步分析，再引入 PI，并让它调用 SuperSonic 的语义问数工具；不是用 PI 替换 SuperSonic。

## 2. 目标与非目标

### 2.1 目标

1. 用 DeepSeek 驱动 SuperSonic，跑通自然语言问数；
2. 在 Parquet 湖上（ClickHouse 服务层）建 20–30 个核心语义模型；
3. 用 50 个真实业务问题评测，结果正确率 ≥ 80%；
4. 验证图表、看板、CSV/XLSX/PDF 导出；
5. 验证业务分析人员可直接使用 SuperSonic UI，无需平台前端改造；
6. 形成生产化清单：权限、查询网关、脱敏、审计、并发。

### 2.2 非目标（POC 不做）

- 平台原生 `/insight/ask` 页面；
- 平台 JWT/workspace 与 SuperSonic 账号打通；
- PI / `dg-mcp`；
- 1000 张表的语义建模；
- StarRocks/Doris 生产集群；
- 行级权限、列级动态脱敏、导出审批流；
- Kafka/CDC 同步。

## 3. POC 架构

```text
业务分析人员
  → SuperSonic UI (localhost:9080)
      → DeepSeek (OpenAI-compatible API)
      → SuperSonic 语义层
          → ClickHouse datasource (:8123)
              → MergeTree 表（POC 服务层）
                  → file() 读 Parquet
                      → data/synthetic-warehouse/{ods,dwd,dws}/...
```

- **POC 服务层**：ClickHouse 用 `file()` 从 Parquet 装载，查询稳定、元数据浏览完整；
- **为什么不用 DuckDB**：SuperSonic 的 DuckDB 连接器元数据接口不完整（`SHOW CATALOGS` 不兼容、schema/catalog 参数错位），SQL 能连但 UI 选不到表；
- **生产路径**：对象存储/HDFS 上的 Parquet + StarRocks/Doris External Catalog，SuperSonic 语义模型不变。

### 3.1 生产路径（Phase 2）

```text
平台 / SuperSonic
  → 只读查询网关（白名单、maxRows、timeout、脱敏、审计）
      → StarRocks / Doris External Catalog
          → 对象存储 / HDFS 上的 Parquet
```

SuperSonic 通过 MySQL 协议连 StarRocks/Doris；语义模型不变。

## 4. 实施步骤

### M0：部署与连通（0.5 天）

1. 生成 30 张表的合成湖（或使用已有湖）；
2. `docker compose up -d` 启动 SuperSonic + ClickHouse；
3. 运行 `load-supersonic-clickhouse.py`，把 Parquet 装进 ClickHouse；
4. 配置 DeepSeek，测试连通；
5. 配置 ClickHouse 数据源，执行冒烟 SQL。

命令见 `deploy/supersonic/README.md`。

### M1：语义建模（2–3 天）

- 选 20–30 张核心表：优先 `dws_sales_master_*`，搭配少量 `dwd_*` 明细；
- 每张表定义：
  - 指标：`sum(total_amount)`、`count(*)`、`avg(quantity)`；
  - 维度：`status`、`region`、`category`、`dt`；
  - 同义词：销售额/营收/金额、客户数/用户数、最近一周/近 7 天；
- 建数据集与助手，绑定 DeepSeek；
- 用 `manifest.json` 校验字段名、PII 标记、血缘关系。

### M2：50 问评测（2 天）

- 业务方出题，平台/数据团队标注标准 SQL 与标准结果；
- 每题记录：命中的语义模型、生成 SQL、结果、图表、延迟、token；
- 失败分类：语义缺失、字段歧义、LLM 错误、数据源错误、权限问题。

### M3：结论与生产化清单（1 天）

- 给出通过/不通过；
- 若不通过，优先补语义模型/同义词，而不是换 LLM；
- 输出 Phase 2 工作项与工作量。

## 5. 语义模型范围（建议）

| 层级 | 数量 | 用途 |
|---|---:|---|
| DWS 汇总表 | 15–20 | 指标、趋势、排名、看板 |
| DWD 明细表 | 5–10 | 明细查询、下钻 |
| ODS | 0–2 | 仅用于对照，不直接开放 |

核心字段：

- 维度：`dt`、`status`、`region`、`category`、`product_id`、`customer_id`；
- 指标：记录数、`sum(total_amount)`、`avg(total_amount)`、`sum(quantity)`、去重客户数；
- 同义词：营收/销售额/金额；客户/用户；订单数/记录数。

> ClickHouse 返回 `Int64` / `Decimal(18, 2)`，SuperSonic 的 ClickHouse 分类器不会自动识别为度量；建模型时需手动把数值列设为“度量”，把 `DateTime64`/`Date` 设为“时间维度”。

## 6. 50 问评测模板

| 类别 | 题数 | 示例 | 重点 |
|---|---:|---|---|
| 单表聚合 | 10 | 按 status 统计订单数和总金额 | 指标/维度命中 |
| 多条件筛选 | 10 | 最近 3 天 region=区域-1 的销售额 | 时间/枚举条件 |
| 时间趋势 | 10 | 按 dt 看每天记录数 | 日期粒度 |
| TopN/排名 | 10 | 金额最高的 10 个 product_id | 排序/限制 |
| 同义词/歧义 | 10 | “营收”“客户数”“最近一周” | 语义层质量 |

每题记录：

```text
question_id, question, expected_sql, expected_result,
actual_sql, actual_result, correct(y/n),
matched_model, matched_metrics, chart_type,
latency_ms, prompt_tokens, completion_tokens, failure_reason
```

### 通过线（建议）

- 结果正确 ≥ 40/50（80%）；
- 语义模型命中率 ≥ 80%；
- P95 延迟 ≤ 10s；
- 无越权表访问；
- 图表类型合理率 ≥ 80%；
- 单题平均 token 成本可接受（按 DeepSeek 计价核算）。

## 7. 明细、PII 与导出（推荐默认值）

> 用户已确认“明细可见”；以下为推荐默认值，POC 前可调整。

- 业务分析人员可见明细行，但 **PII 字段默认脱敏**；
- POC 阶段由 SuperSonic 数据集/字段权限兜底；生产阶段接平台敏感标签；
- 导出 CSV/XLSX/PDF 需要单独权限，并记录导出人、时间、行数、字段；
- 审计至少记录：用户、问题、生成 SQL、数据源、行数、耗时、是否导出；
- 不在 Prompt 中发送整表数据，只发送 schema、语义模型和聚合结果。

## 8. Phase 2：平台化

### 8.1 已完成：问数网关（V17）+ 前端入口（V18）+ 导出（V19）

- **入口**：
  - 后端 `POST /api/v1/insight/ask`，前端不直连 SuperSonic；
  - 前端 `/insight/ask` 页面：数据集下拉、问题输入、ECharts 图表、结果表格、生成 SQL、脱敏提示、最近问数；
  - 前端 `/insight/dashboards` 页面：看板列表、创建看板、保存问题项、删除、手动刷新、60 秒自动刷新、版本查看；
  - V18 菜单 `menu:insight`，权限 `insight:ask` / `insight:read`。
- **数据集列表**：`GET /api/v1/insight/datasets`，从 SuperSonic 主题域/数据集树拍平。
- **身份**：平台 JWT + `WorkspaceContext`；权限码 `insight:ask` / `insight:read`；
  V23 增加 `ig_supersonic_mapping`，按平台用户映射 SuperSonic 账号（口令托管在 Platform 凭据）；无映射时回退服务账号。
- **网关**：
  - 行数上限 `DG_INSIGHT_MAX_ROWS`（默认 200），超出标记 `truncated`；
  - 上游超时 `DG_INSIGHT_TIMEOUT_SECONDS`（默认 120s）；
  - 结果按 `md_column_sensitivity` 的未拒绝标签做兜底脱敏；
  - SQL 别名（如 `phone AS _手机号_`）也能识别并脱敏；
  - 查询事实写 `ig_query_log`，同时写 `gv_audit_record`（`INSIGHT_QUERY` / `EXECUTE`）。
- **配置**：`DG_SUPERSONIC_URL`、`DG_SUPERSONIC_USER`、`DG_SUPERSONIC_PASSWORD`、`DG_SUPERSONIC_AGENT_ID`、`DG_SUPERSONIC_DATASET_ID`。
- **历史**：`GET /api/v1/insight/queries` 返回当前空间的问数记录。
- **已验证**：
  - 登录、权限种子、`/insight/ask` 返回 SuperSonic SQL/结果；
  - 临时给 `status` 打 L2 标签后，结果中的 `status` 和中文别名均被替换为 `***`；
  - 查询历史与治理审计各新增记录；
  - `POST /api/v1/insight/export` 导出 CSV、`/export/xlsx` 导出 XLSX，需 `insight:export` 权限，导出同样脱敏并写 `INSIGHT_EXPORT` 审计；
  - 前端 `pnpm type-check`、`pnpm build` 通过，页面路由 `/insight/ask` 已注册，页面含“导出 CSV / XLSX”按钮；
  - 前端按结果自动选图：日期维度用折线图，其余维度用柱状图（ECharts 6，按需引入）；
  - 看板 API：保存问题定义；`POST /{id}/run` 逐项重新问数；`GET /{id}/versions` 版本历史；`GET/POST/DELETE /{id}/acl` 细粒度 ACL；`PATCH /{id}/items/{itemId}` 排序/布局。
  - 用户映射 API：`GET/POST/DELETE /api/v1/insight/mappings`（管理员）；前端 `/insight/mappings` 可维护映射。

### 8.2 已完成：StarRocks + MinIO 本地验证

- `deploy/starrocks/docker-compose.yml`：MinIO + StarRocks allin1；
- `scripts/upload-minio.sh`：把合成湖上传到 `s3://lake/synthetic-warehouse`；
- `scripts/load-starrocks.py`：
  - 30 张表装入 StarRocks `synthetic` 库；
  - 用 `FILES()` 读 MinIO Parquet；
  - `columns_from_path="dt"` 还原 `dt` 分区列；
- SuperSonic 数据源 `synthetic_starrocks`（id=4）`testConnect=true`；
- StarRocks 主题域 `销售分析-StarRocks`（id=5），20 个模型/数据集，助手 id=6；
- StarRocks 50 问评测：**50 PASS / 0 FAIL**，见
  `docs/plans/2026-10-29-supersonic-starrocks-50-eval.md`；
- 平台已切到助手 6 / 数据集 36，`/insight/ask` 和 CSV 导出均验证通过。

> 这是 **FILES() 内表装载**，还不是 External Catalog；但它验证了
> SuperSonic → StarRocks 的查询、语义模型和 50 问链路。

### 8.3 External Catalog 尝试（未通过）

尝试过两条更接近生产的路，但当前 StarRocks/MinIO 组合不满足：

1. **File External Table**
   `CREATE EXTERNAL TABLE ... ENGINE=file` 能读取单个 Parquet 文件，但：
   - 不支持 `columns_from_path`，`dt` 分区列取不到；
   - 目录/通配符路径报 `No file exists for FileTable`。

2. **View over FILES()**
   `CREATE VIEW ... AS SELECT ... FROM FILES(...)` 建视图成功，但视图不持久化 S3 凭据；
   查询时报 `InvalidAccessKeyId`。这是 StarRocks 的安全行为，不是配置笔误。

结论：真正的 External Catalog 需要 **HMS/Glue 元数据服务**，或让 StarRocks 通过
Catalog 访问对象存储；当前 POC 保留 `FILES()` 内表装载作为链路验证。

### 8.4 待完成

1. **真正 External Catalog**：HMS/Glue 或 S3 文件外表，替换 `FILES()` 内表；
2. **身份细化**：映射 API/UI 已落地；还缺 SuperSonic 用户/角色自动开通、映射回归；
3. **查询策略**：并发限流、按用户配额、更完整的 SQL 只读校验；
4. **看板**：已支持手动/60 秒刷新、版本快照、ACL、排序/布局字段；拖拽布局 UI、版本回滚待做；
5. **导出**：PDF、审批流；CSV/XLSX 已做；
6. **评测回归**：50 问变成回归集，语义模型变更必须跑；用真实业务问题替换合成题库。

## 9. Phase 3：PI（可选）

只有在出现以下需求时才引入：

- 多步归因分析（“为什么下降”“对比一下”“再按区域拆”）；
- Agent 自动浏览 catalog、血缘、敏感标签并生成报告；
- Slack/飞书/钉钉等对话入口；
- Skills/MCP/长期记忆。

引入方式：

```text
业务人员
  → PI（服务端，容器/沙箱）
      → dg-mcp
          ├─ semantic.ask   → SuperSonic
          ├─ parquet.query
          ├─ catalog.search
          ├─ lineage.*
          ├─ sensitivity.*
          ├─ chart.render
          └─ export.*
```

硬约束：

- 服务端部署，不跑在分析师电脑上；
- 不开放 shell/browser/文件系统工具；
- 工具白名单 + 调用拦截 + 容器沙箱；
- 会话/记忆按 workspace 隔离；
- SuperSonic 仍是唯一语义层，PI 不重复维护指标；
- 业务入口用 Web 聊天页，不用 TUI/CLI。

## 10. 风险与待确认

| 风险 | 影响 | 应对 |
|---|---|---|
| SuperSonic 的 DuckDB 连接器不完整 | 能连但 UI 选不到表 | 已改用 ClickHouse POC；DuckDB 仅保留实验脚本 |
| ClickHouse 直接挂本地 Parquet 需读写/chown | 数据目录被容器改权限 | 仅 POC；生产用对象存储/HDFS + 外表 |
| ClickHouse 是 POC 服务层，不是湖仓外表 | 与生产路径不完全一致 | 语义模型保持稳定，Phase 2 切 StarRocks/Doris |
| ClickHouse 类型名不被 SuperSonic 分类器识别 | 数值列默认成普通字段 | 建模型时手动设为度量；Phase 2 换 StarRocks 后复核 |
| 语义建模工作量被低估 | 准确率上不去 | 先 20–30 表，业务方参与定义同义词 |
| DeepSeek 生成 SQL 不稳定 | 结果错误 | 语义层约束 + few-shot + 回归集 |
| SuperSonic 账号与平台割裂 | 生产权限不一致 | V23 已支持 per-user 映射；还缺 SuperSonic 用户/角色自动开通与 UI |
| 结果脱敏按列名/别名匹配 | 复杂表达式可能漏匹配 | 语义模型不暴露原始 PII 是第一道防线;上线前用敏感列回归集验证 |
| 导出泄露 PII | 合规风险 | 默认脱敏 + 单独授权 + 审计 |
| 看板归属不清 | 后续迁移成本 | POC 放 SuperSonic，Phase 2 决策 |

待确认（不阻塞 POC）：

1. 明细中的 PII 是否允许不脱敏？推荐：默认脱敏，按角色申请。
2. 导出是否需要审批？推荐：需要，至少审计。
3. 看板长期归属平台还是 SuperSonic？推荐：POC 在 SuperSonic，生产统一到平台。

## 11. 验证记录（2026-10-28）

本机 Docker 实测：

- `docker compose up -d`：PostgreSQL healthy、SuperSonic 启动、ClickHouse 启动；
- SuperSonic UI `http://localhost:9080` 返回 302（登录页）；
- 30 张合成表装入 ClickHouse：**24,558 行**（DWS 为聚合表，行数少于 1000/表）；
- SuperSonic 数据源 `testConnect=true`；
- `getDbNames` 返回 `synthetic`；
- `getTables(databaseId=3, db=synthetic)` 返回 **30 张表**；
- DuckDB 对照：`testConnect=true`，但 `getCatalogs` 报 `Table with name CATALOGS does not exist`，`getTables` 为空，确认不适合 UI POC；
- DeepSeek 模型已配置：`testConnection=true`，模型 `deepseek-chat`；
- 已建 POC 语义链路：
  - 主题域 `销售分析`（id=4）
  - **20 个语义模型 / 20 个数据集**：12 张 DWD + 6 张 DWS + 2 张无 PII 的 ODS
  - 助手 `销售分析助手`（id=5，绑定 DeepSeek 模型 id=2，绑定全部 20 个数据集）
- 字段已做中文名/描述映射；`customer_id`/`product_id` 建为维度而不是度量；
- 金额/数量/记录数 metric 统一设为 `SUM`；
- 50 问评测：**50 PASS / 0 PARTIAL / 0 FAIL（100%）**，详见
  `docs/plans/2026-10-28-supersonic-50-eval.md`。

### 评测口径

- 题库覆盖 6 张 DWS 的聚合、趋势、TopN，12 张 DWD 的状态/区域聚合，以及 2 张 ODS 明细聚合；
- 每题用独立 `dataSetId`，避免多模型同名字段歧义；
- 标准结果由 ClickHouse 直接执行等价 SQL 得到，和 SuperSonic 返回结果做数值/键集合比对。

### 关键修复

1. **中文 CTE 别名**：ClickHouse 对未加引号的中文 CTE 别名报语法错误。将 S2SQL prompt 改为“别名/CTE 名只用 ASCII”，并把数据源类型改成 `OTHER`（JDBC URL 仍是 ClickHouse），避免 `ClickHouseAdaptor` 去掉反引号。
2. **指标被 COUNT(* ) 替代**：在 S2SQL prompt 中增加“问题命中已有指标时优先使用该指标，不要用 COUNT(*) 替代”，修复“按状态统计记录数”被解释成统计聚合行数的问题。
3. **TopN 不带金额**：问题明确为“并返回对应总金额”后，SQL 能同时返回区域和金额。

尚未验证：

- 图表/看板/导出（SuperSonic UI 支持，但未逐项验收）；
- 多模型 join、跨数据集问题；
- 1000 张表的 ClickHouse 装载耗时；
- 明细/脱敏/导出审批；
- 真实业务问题（当前是合成 schema + 合成题库）。

### 已知限制

- 50/50 是**合成数据、单模型单数据集、简单聚合**的结果，不等同于真实 1000 表环境的准确率；
- 合成 DWS 只有 2 天数据，时间类问题覆盖不足；
- 题库以“能自动比对”的聚合题为主，未覆盖多轮、归因、同义词歧义等复杂问题；
- ODS 只开放了无 PII 的订单/商品两张表，其余 ODS 未建模。

## 12. 交付物

- `deploy/supersonic/docker-compose.yml`、`.env.example`、`README.md`；
- `scripts/load-supersonic-clickhouse.py`（POC 主路径）；
- `scripts/seed-supersonic-semantic.py`（批量 20 个模型/数据集）；
- `scripts/evaluate-supersonic-50.py`（50 问自动评测）；
- `scripts/build-supersonic-duckdb.py`（DuckDB 实验路径）；
- 20 个语义模型 / 20 个数据集（已建）；
- 50 问评测结果（50 PASS，见 `docs/plans/2026-10-28-supersonic-50-eval.md`）；
- Phase 2 工作项清单与排期；
- 是否引入 PI 的决策记录。
