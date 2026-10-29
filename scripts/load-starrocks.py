#!/usr/bin/env python3
"""把 MinIO 上的 Parquet 合成湖装进 StarRocks(本地 POC)。

这不是“外部 Catalog”本身,而是先用 StarRocks 的 FILES() 把 Parquet 落到
StarRocks 内表,验证 SuperSonic → StarRocks 的整条链路。真正的 External
Catalog 需要 HMS/Glue 或对象存储文件外表,等有可复用元数据服务后再切。

用法:
  python3 scripts/load-starrocks.py \
    --host 127.0.0.1 --port 19030 --user root \
    --database synthetic \
    --minio-endpoint http://dg-minio:9000 \
    --bucket lake --prefix synthetic-warehouse
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lake", default="data/synthetic-warehouse")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default="19030")
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="")
    parser.add_argument("--database", default="synthetic")
    parser.add_argument("--minio-endpoint", default="http://dg-minio:9000")
    parser.add_argument("--bucket", default="lake")
    parser.add_argument("--prefix", default="synthetic-warehouse")
    parser.add_argument("--access-key", default="minioadmin")
    parser.add_argument("--secret-key", default="minioadmin")
    return parser.parse_args()


def clickhouse_type(type_name):
    t = type_name.upper().strip()
    if t.startswith("DECIMAL"):
        return "DECIMAL(18,2)"
    return {
        "BIGINT": "BIGINT",
        "INTEGER": "INT",
        "INT": "INT",
        "SMALLINT": "SMALLINT",
        "TINYINT": "TINYINT",
        "DOUBLE": "DOUBLE",
        "FLOAT": "FLOAT",
        "BOOLEAN": "BOOLEAN",
        "DATE": "DATE",
        "TIMESTAMP": "DATETIME",
        "VARCHAR": "VARCHAR(2000)",
        "STRING": "VARCHAR(2000)",
        "TEXT": "VARCHAR(2000)",
    }.get(t, "VARCHAR(2000)")


def quote(name):
    return "`" + name.replace("`", "``") + "`"


def build_sql(dataset, args):
    name = dataset["name"]
    columns = [c for c in dataset["columns"] if c["name"] != "dt"]
    defs = ",\n  ".join(
        f"{quote(c['name'])} {clickhouse_type(c['type'])}" for c in columns
    ) + ",\n  `dt` DATE"
    cols = ", ".join(quote(c["name"]) for c in columns)
    key = quote(columns[0]["name"])
    path = (f"s3://{args.bucket}/{args.prefix.rstrip('/')}/"
            f"{dataset['path']}/*/data_0.parquet")
    return f"""
DROP TABLE IF EXISTS {quote(args.database)}.{quote(name)};
CREATE TABLE {quote(args.database)}.{quote(name)} (
  {defs}
) ENGINE=OLAP
DUPLICATE KEY({key})
DISTRIBUTED BY HASH({key}) BUCKETS 1
PROPERTIES("replication_num"="1");

INSERT INTO {quote(args.database)}.{quote(name)} ({cols}, `dt`)
SELECT {cols}, dt
FROM FILES(
  "path" = "{path}",
  "format" = "parquet",
  "columns_from_path" = "dt",
  "aws.s3.endpoint" = "{args.minio_endpoint}",
  "aws.s3.access_key" = "{args.access_key}",
  "aws.s3.secret_key" = "{args.secret_key}",
  "aws.s3.enable_ssl" = "false",
  "aws.s3.enable_path_style_access" = "true"
);
"""


def main():
    args = parse_args()
    manifest = json.loads(
        (Path(args.lake) / "manifest.json").read_text(encoding="utf-8")
    )
    datasets = manifest.get("datasets", [])
    if not datasets:
        sys.exit("manifest.json 没有 datasets")

    sql = [
        f"CREATE DATABASE IF NOT EXISTS {quote(args.database)};",
        f"USE {quote(args.database)};",
    ]
    sql.extend(build_sql(dataset, args) for dataset in datasets)
    command = [
        "mysql", "-h", args.host, "-P", str(args.port), "-u", args.user,
        "--default-character-set=utf8mb4",
    ]
    if args.password:
        command.insert(4, f"-p{args.password}")
    result = subprocess.run(command, input="".join(sql), text=True,
                            capture_output=True)
    if result.returncode != 0:
        sys.exit("StarRocks 装载失败:\n" + result.stderr[-4000:])
    print(f"已装载 {len(datasets)} 张表到 StarRocks {args.database}")
    print("验证:")
    print(f"  mysql -h {args.host} -P {args.port} -u {args.user} -e "
          f"\"SELECT count() FROM {args.database}.dws_sales_master_0000\"")


if __name__ == "__main__":
    main()
