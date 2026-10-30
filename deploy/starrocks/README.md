# StarRocks + MinIO 本地验证

目标：验证 **MinIO Parquet → StarRocks → SuperSonic** 链路，作为 ClickHouse POC 之后的
生产方向验证。当前采用 StarRocks `FILES()` 读取 Parquet 并落到内表；真正的
External Catalog（HMS/Glue/S3 文件外表）在下一阶段替换。

## 1. 启动

```bash
cd deploy/starrocks
docker compose up -d
# 首次启动 StarRocks 需要 30–60 秒
docker compose ps
```

端口：

- StarRocks MySQL：`localhost:19030`
- StarRocks FE HTTP：`localhost:18030`
- MinIO S3：`localhost:19002`
- MinIO Console：`localhost:19003`

> StarRocks allin1 镜像较大（约 8.6GB），建议 Docker 至少 8GB 内存。

## 2. 上传 Parquet

```bash
./scripts/upload-minio.sh
```

等价于把 `data/synthetic-warehouse` 上传到 `s3://lake/synthetic-warehouse`。

## 3. 装载到 StarRocks

```bash
python3 scripts/load-starrocks.py \
  --host 127.0.0.1 --port 19030 --user root \
  --database synthetic \
  --minio-endpoint http://minio:9000 \
  --bucket lake --prefix synthetic-warehouse
```

脚本会：

- 为 30 张表建 StarRocks 内表；
- 用 `FILES()` 从 MinIO 读 Parquet；
- 用 `columns_from_path="dt"` 还原 `dt` 分区列；
- 数据量很小，通常 1 分钟内完成。

验证：

```bash
mysql -h127.0.0.1 -P19030 -uroot \
  -e "SELECT status, SUM(cnt) FROM synthetic.dws_sales_master_0000 GROUP BY status"
```

## 4. 配置 SuperSonic

先让 SuperSonic 容器能解析 StarRocks：

```bash
docker network connect supersonic_supersonic_network dg-starrocks
```

在 SuperSonic 数据源管理中新增 **StarRocks**：

| 配置项 | 值 |
|---|---|
| 类型 | StarRocks |
| JDBC URL | `jdbc:mysql://dg-starrocks:9030/synthetic` |
| 用户名 | `root` |
| 密码 | 空 |
| 数据库 | `synthetic` |

然后建语义模型/数据集。本地 POC 已建：

- 主题域 `销售分析-StarRocks`（id=5）
- 20 个模型 / 20 个数据集
- 助手 `销售分析助手-StarRocks`（id=6）
- 50 问评测：`docs/plans/2026-10-29-supersonic-starrocks-50-eval.md`（50/50 PASS）

批量建模型：

```bash
SUPERSONIC_PASSWORD=admin123 python3 scripts/seed-supersonic-semantic.py \
  --domain-id 5 --database-id 4 --agent-id 6 \
  --map-output tmp/supersonic-model-map-starrocks.json
```

## 5. 平台切到 StarRocks

```bash
DG_SUPERSONIC_AGENT_ID=6 \
DG_SUPERSONIC_DATASET_ID=36 \
java -jar backend/dg-app/target/dg-app-0.1.0-SNAPSHOT.jar
```

`36` 是 StarRocks 的 `销售汇总数据集`；平台 `/insight/ask` 和导出会走该助手。

## 6. 说明

- 这是 **内表装载**，不是 External Catalog；生产应使用 HMS/Glue 或 S3 文件外表。
- MinIO 仅本地 POC；生产用对象存储/HDFS。
- StarRocks `root` 无密码只用于本地，生产必须独立只读账号。
