# SuperSonic POC（AI 问数 / 语义层）

本目录是 **Phase 1 POC** 的独立部署脚手架：

```text
业务分析人员
  → SuperSonic UI (:9080)
      → DeepSeek（OpenAI 兼容）
      → SuperSonic 语义层
          → ClickHouse（POC 服务层）
              → Parquet 湖（file() 直接读）
```

- **不包含 PI**。先验证“自然语言问数 + 语义层 + 图表/看板/导出”。
- SuperSonic 使用自带 UI，暂不改造平台前端。
- Parquet 是数据源，ClickHouse 只是 POC 的查询服务层；生产切 StarRocks/Doris 外表，见 `docs/plans/2026-10-28-supersonic-poc.md`。

## 0. 为什么不是 DuckDB

SuperSonic 的 DuckDB 连接器目前只完成了一半：

- 能建立 JDBC 连接；
- 但 `getCatalogs` 执行 `SHOW CATALOGS`，DuckDB 0.10 不支持；
- `DuckdbAdaptor` 把 schema 当 catalog 传给 JDBC，表列表为空。

结果是：**SQL 能连，UI 里选不到表，无法正常建语义模型**。因此 POC 改用 SuperSonic 一等支持的 ClickHouse：

- ClickHouse 的 `file()` 直接读 Parquet，不需要先把数据搬到对象存储；
- SuperSonic 的 ClickHouse 数据源、库表浏览、语义模型、查询全链路可用；
- 生产再换 StarRocks/Doris External Catalog，语义模型基本不变。

`scripts/build-supersonic-duckdb.py` 保留作实验/排错，不作为 POC 主路径。

## 1. 前置条件

- Docker Desktop / Docker Engine + Compose v2，建议至少 4 CPU / 8 GB 内存。
- Python 3.9+。
- 合成数据生成脚本需要 DuckDB（任意 1.x）：

```bash
python3 -m pip install duckdb
```

## 2. 生成合成 Parquet 湖

```bash
# 快速 POC：30 张表
python3 scripts/seed-synthetic-warehouse.py \
  --tables 30 --rows 1000 --partitions 3 \
  --output data/synthetic-warehouse

# 接近生产规模：1000 张表（耗时/磁盘更大）
# python3 scripts/seed-synthetic-warehouse.py \
#   --tables 1000 --rows 8000 --partitions 3 --payload-bytes 5120 \
#   --output data/synthetic-warehouse
```

## 3. 启动

```bash
cd deploy/supersonic
cp .env.example .env
# 必须替换 JWT secret
openssl rand -hex 64
# 把输出写入 .env 的 SUPERSONIC_JWT_SECRET
# 把 DG_DATA_DIR 改成仓库 data/ 的绝对路径
# 例：DG_DATA_DIR=/Users/you/datagovernance/data

docker compose up -d
docker compose ps
```

服务：

| 服务 | 地址 | 用途 |
|---|---|---|
| SuperSonic | <http://localhost:9080> | 问数/语义建模 |
| ClickHouse HTTP | <http://localhost:18123> | POC 查询服务层 |
| ClickHouse Native | `localhost:19000` | 可选 |
| PostgreSQL | `localhost:15432` | SuperSonic 元数据库 |

首次启动按页面提示登录管理员账号，并立即修改默认密码。若镜像默认账号不可用，可注册一个 POC 管理员（仅本地）：

```bash
# 1) 注册
curl -s -X POST http://localhost:9080/api/auth/user/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"pocadmin","password":"pocadmin123"}'

# 2) 提升为管理员
docker exec supersonic_postgres psql -U supersonic_user -d postgres \
  -c "update s2_user set is_admin=1 where name='pocadmin';"

# 3) 登录 pocadmin / pocadmin123，并立即改密
```

> `docker-compose.yml` 使用官方 standalone 镜像；生产环境应固定 `SUPERSONIC_VERSION`、移除 `privileged`/公网端口、改为外部 PostgreSQL，并把 ClickHouse 换成 StarRocks/Doris。

## 4. 把 Parquet 装进 ClickHouse

等 ClickHouse healthy 后执行：

```bash
python3 scripts/load-supersonic-clickhouse.py \
  --lake data/synthetic-warehouse \
  --clickhouse-url http://localhost:18123 \
  --user default \
  --password clickhouse \
  --database synthetic
```

脚本会：

1. 为每张 Parquet 表执行 `CREATE TABLE ... ENGINE=MergeTree AS SELECT * FROM file(...)`；
2. 从 `_path` 提取 `dt=` 分区，补成 `dt Date` 列；
3. 输出总行数。

实测 30 张表约 1–2 秒。1000 张表会久一些，但仍是 POC 可接受范围。

验证：

```bash
curl -s -u default:clickhouse \
  'http://localhost:18123/' \
  --data-binary 'SELECT status, count() FROM synthetic.dws_sales_master_0000 GROUP BY status'
```

> ClickHouse 启动时会 chown `user_files`，所以 compose 里 Parquet 目录是读写挂载。生产不要把数据目录直接挂进查询引擎，应使用对象存储/HDFS + 外表。

## 5. 配置 DeepSeek

在 SuperSonic 的 **LLM 管理** 中新增 OpenAI 兼容供应商：

| 配置项 | 值 |
|---|---|
| Provider | OpenAI / OpenAI-Compatible |
| Base URL | `https://api.deepseek.com/v1` |
| Model | `deepseek-chat`（复杂推理可加 `deepseek-reasoner`） |
| API Key | DeepSeek 控制台创建的 Key |

保存后先做连通性测试。Key 只保存在 SuperSonic 元数据库；不要写进仓库或 `.env` 提交。

## 6. 配置数据源

在 **数据源管理** 中新增 ClickHouse：

| 配置项 | 值 |
|---|---|
| 类型 | ClickHouse |
| JDBC URL | `jdbc:clickhouse://clickhouse:8123/synthetic` |
| 用户名 | `default` |
| 密码 | `clickhouse`（与 `.env` 的 `CLICKHOUSE_PASSWORD` 一致） |
| 数据库 | `synthetic` |

> 主机名必须是 `clickhouse`（不要用带下划线的容器名）。ClickHouse JDBC 对下划线主机名解析有兼容问题，会退化成 `localhost`。

连接成功后应能看到 `synthetic` 库和 30（或 1000）张表。可先在查询页执行：

```sql
SELECT status, count() AS cnt, sum(total_amount) AS total
FROM synthetic.dws_sales_master_0000
GROUP BY status
ORDER BY status;
```

## 7. 建语义模型

建议只选 **20–30 张核心 DWS/DWD 表**，不要一上来铺 1000 张：

1. 新建主题域，例如“销售分析”；
2. 为每张核心表建语义模型：
   - 指标：`sum(total_amount)`、`count()`、`avg(quantity)`；
   - 维度：`status`、`region`、`category`、`dt`；
   - 同义词：如“销售额/营收/金额”“客户数/用户数”“最近一周/近 7 天”；
   - 默认聚合与格式化；
3. 建数据集/助手，绑定 DeepSeek；
4. 用 10 个问题做冒烟，再扩到 50 个评测问题。

> **字段类型要手动校正**：SuperSonic 的 ClickHouse 分类器只认 `INT/BIGINT/DECIMAL` 等通用名，ClickHouse 返回的是 `Int64/Decimal(18, 2)`，会被默认标成“普通字段”。建模型时请把数值列改成“度量”，把 `DateTime64`/`Date` 改成“时间维度”。

`data/synthetic-warehouse/manifest.json` 包含表、字段、PII 标记和血缘边，可作为建模与评测的对照答案。

## 8. 50 问评测

评测问题分五类，每类 10 个：

| 类别 | 示例 |
|---|---|
| 单表聚合 | 按 status 统计订单数和总金额 |
| 多条件筛选 | 最近 3 天、region=区域-1 的销售额 |
| 时间趋势 | 按 dt 看每天的记录数变化 |
| TopN/排名 | 金额最高的 10 个 product_id |
| 同义词/歧义 | “营收”“客户数”“最近一周”等口径 |

记录以下指标：

- SQL 正确率 / 结果正确率（人工对照 ClickHouse 查询）；
- 是否命中正确语义模型与指标；
- 图表类型是否合理；
- 端到端延迟、DeepSeek token 成本；
- 失败原因分类：语义缺失、字段歧义、LLM 生成错误、数据源问题。

**POC 通过线（建议）**：50 问中结果正确 ≥ 40 个（80%），P95 延迟 ≤ 10s，无越权表访问。

## 9. 安全与边界

- 只监听本机，不要把 `9080` / `18123` / `15432` 暴露到公网；
- ClickHouse 的 `default` 账号仅用于 POC；生产用独立只读账号；
- 明细可见，但 PII 字段默认脱敏；导出单独授权并审计（POC 阶段由 SuperSonic 账号权限兜底）；
- DeepSeek 只接收问题、语义模型和生成 SQL 所需的 schema，不发送整表数据；
- POC 的 SuperSonic 账号体系与平台 JWT 尚未打通，生产阶段再接 SSO/workspace 隔离。

## 10. 停止与清理

```bash
cd deploy/supersonic
docker compose down
# 连元数据库一起删除（谨慎）
# docker compose down -v
# rm -rf supersonic_pg
```

## 11. 常见问题

| 现象 | 处理 |
|---|---|
| `Cannot connect to the Docker daemon` | 启动 Docker Desktop |
| `SUPERSONIC_JWT_SECRET` 报错 | `.env` 中填入 `openssl rand -hex 64` 的输出 |
| `DG_DATA_DIR` 报错 | 改成 `data/` 的绝对路径 |
| ClickHouse 启动失败，日志有 `chown ... Read-only file system` | 当前 compose 已改为读写挂载；不要把该目录再设 `:ro` |
| SuperSonic 连 ClickHouse 报 `Connection refused` | 确认 URL 主机是 `clickhouse`，不是 `supersonic_clickhouse` 或 `localhost` |
| ClickHouse 里没有表 | 先执行第 4 节加载脚本 |
| 首次查询很慢 | ClickHouse 需要加载 part；POC 数据量小，通常秒级 |

## 12. 附录：DuckDB 实验路径

`scripts/build-supersonic-duckdb.py` 可以生成 DuckDB 视图文件，连接本身可用，但当前 SuperSonic 的 DuckDB 元数据适配不完整，**不要用它做 UI POC**。

如果未来 SuperSonic 修复了 DuckDB 适配，再按脚本顶部说明使用；注意 DuckDB 存储格式不向后兼容，生成版本要与镜像内置 JDBC 版本一致。
