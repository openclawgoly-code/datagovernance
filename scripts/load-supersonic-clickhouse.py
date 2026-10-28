#!/usr/bin/env python3
"""把合成 Parquet 湖装进 ClickHouse,供 SuperSonic 语义层使用。

DuckDB 在 SuperSonic 里只有半成品连接器(元数据浏览/目录接口不可用),
所以 POC 用 ClickHouse 作为“Parquet 服务层”:
  - ClickHouse 的 file() 直接读 Parquet,不经过额外 ETL;
  - 每张表落成 MergeTree 小表,语义层查询稳定、快;
  - 生产再把同一批 Parquet 放到 S3/HDFS,用 StarRocks/Doris 外表替代。

用法(ClickHouse 容器已启动):
  python3 scripts/load-supersonic-clickhouse.py \
    --lake data/synthetic-warehouse \
    --clickhouse-url http://localhost:18123 \
    --user default --password clickhouse \
    --database synthetic
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lake", default="data/synthetic-warehouse")
    parser.add_argument("--clickhouse-url", default="http://localhost:18123")
    parser.add_argument("--user", default="default")
    parser.add_argument("--password", default="clickhouse")
    parser.add_argument("--database", default="synthetic")
    # ClickHouse 容器里 user_files_path 下的相对目录
    parser.add_argument("--user-files-prefix", default="data/synthetic-warehouse")
    parser.add_argument("--limit", type=int, default=0, help="只加载前 N 张表(调试用)")
    return parser.parse_args()


def query(base_url, user, password, sql):
    # Parquet 的字符串列常被推断成 LowCardinality;由它派生 Date 需要显式放行。
    sep = "&" if "?" in base_url else "?"
    url = f"{base_url}{sep}allow_suspicious_low_cardinality_types=1"
    req = urllib.request.Request(
        url,
        data=sql.encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "text/plain; charset=utf-8",
            "Authorization": "Basic "
            + base64.b64encode(f"{user}:{password}".encode()).decode(),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"ClickHouse HTTP {e.code}: {body[:800]}") from e


def quote_ident(name):
    return "`" + name.replace("`", "``") + "`"


def clickhouse_type(type_name):
    """把 manifest 的逻辑类型映射成 ClickHouse 非 Nullable 类型。"""
    t = type_name.upper().strip()
    if t.startswith("DECIMAL"):
        return type_name
    mapping = {
        "BIGINT": "Int64",
        "INTEGER": "Int32",
        "INT": "Int32",
        "SMALLINT": "Int16",
        "TINYINT": "Int8",
        "DOUBLE": "Float64",
        "FLOAT": "Float32",
        "REAL": "Float32",
        "BOOLEAN": "Bool",
        "DATE": "Date",
        "TIMESTAMP": "DateTime64(6)",
        "VARCHAR": "String",
        "STRING": "String",
        "TEXT": "String",
    }
    return mapping.get(t, "String")


def main():
    args = parse_args()
    lake = Path(args.lake).resolve()
    manifest_path = lake / "manifest.json"
    if not manifest_path.exists():
        sys.exit(f"找不到 {manifest_path};请先运行 scripts/seed-synthetic-warehouse.py")
    datasets = json.loads(manifest_path.read_text(encoding="utf-8")).get("datasets", [])
    if not datasets:
        sys.exit("manifest.json 没有 datasets")
    if args.limit:
        datasets = datasets[: args.limit]

    query(args.clickhouse_url, args.user, args.password,
          f"CREATE DATABASE IF NOT EXISTS {quote_ident(args.database)}")

    started = time.time()
    loaded = 0
    for i, dataset in enumerate(datasets, 1):
        name = dataset["name"]
        relative = dataset["path"]
        pattern = f"{args.user_files_prefix.rstrip('/')}/{relative}/*/*.parquet"
        table = f"{quote_ident(args.database)}.{quote_ident(name)}"
        columns = [c for c in dataset.get("columns", []) if c["name"] != "dt"]
        if not columns:
            print(f"跳过 {name}: manifest 没有列定义")
            continue

        defs = ", ".join(
            f"{quote_ident(c['name'])} {clickhouse_type(c['type'])}" for c in columns
        )
        defs += ", `dt` Date"
        selects = ", ".join(f"assumeNotNull({quote_ident(c['name'])})" for c in columns)
        selects += ", toDate(extract(_path, 'dt=([0-9-]+)'))"
        column_list = ", ".join(quote_ident(c["name"]) for c in columns) + ", `dt`"

        query(args.clickhouse_url, args.user, args.password, f"DROP TABLE IF EXISTS {table}")
        query(
            args.clickhouse_url,
            args.user,
            args.password,
            f"CREATE TABLE {table} ({defs}) ENGINE = MergeTree ORDER BY tuple()",
        )
        query(
            args.clickhouse_url,
            args.user,
            args.password,
            f"INSERT INTO {table} ({column_list}) "
            f"SELECT {selects} FROM file('{pattern}', Parquet)",
        )
        loaded += 1
        if i % 25 == 0 or i == len(datasets):
            elapsed = time.time() - started
            print(f"[{i}/{len(datasets)}] {name} ({elapsed:.1f}s)")

    total_rows = query(
        args.clickhouse_url,
        args.user,
        args.password,
        f"SELECT sum(rows) FROM system.parts WHERE database = '{args.database}' AND active",
    ).strip()
    print(f"完成: {loaded} 张表, 总行数 {total_rows}, 耗时 {time.time() - started:.1f}s")
    print(f"SuperSonic 数据源: clickhouse, jdbc:clickhouse://<host>:8123/{args.database}")


if __name__ == "__main__":
    main()
