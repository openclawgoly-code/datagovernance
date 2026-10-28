#!/usr/bin/env python3
"""实验路径:把合成 Parquet 湖映射成一个 DuckDB 文件。

注意:当前 SuperSonic 的 DuckDB 元数据适配不完整(库表浏览不可用),
POC 主路径是 scripts/load-supersonic-clickhouse.py。本脚本仅用于:
  - 本地用 DuckDB 校验 Parquet/视图;
  - 未来 SuperSonic 修好 DuckDB 适配后的快速接入。

生成后默认:
  deploy/supersonic/supersonic_db/supersonic.duckdb
  deploy/supersonic/supersonic_db/supersonic-views.sql

DuckDB 存储格式不向后兼容;生成版本必须与 SuperSonic 镜像内置 JDBC 一致。
"""
import argparse
import json
import sys
from pathlib import Path

try:
    import duckdb
except ImportError:
    sys.exit("缺少 duckdb。请先执行: python3 -m pip install duckdb")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lake", default="data/synthetic-warehouse",
                        help="seed-synthetic-warehouse.py 的输出目录")
    parser.add_argument("--output", default="deploy/supersonic/supersonic_db/supersonic.duckdb",
                        help="DuckDB 文件路径(需可写),默认 deploy/supersonic/supersonic_db/supersonic.duckdb")
    parser.add_argument("--container-output", default="/var/lib/supersonic-db/supersonic.duckdb",
                        help="SuperSonic 容器内的 DuckDB 文件路径")
    parser.add_argument("--view-root", "--container-root", dest="view_root", default=None,
                        help="写进视图的 lake 绝对路径;默认取 --lake 的绝对路径,容器需挂载到同一路径")
    parser.add_argument("--duckdb-version-prefix", default="0.10.",
                        help="SuperSonic 内置 DuckDB JDBC 的版本前缀;默认 0.10.")
    parser.add_argument("--allow-version-mismatch", action="store_true",
                        help="允许用不匹配的 DuckDB 版本生成(可能无法被 SuperSonic 读取)")
    parser.add_argument("--force", action="store_true", help="覆盖已有 DuckDB 文件")
    return parser.parse_args()


def main():
    args = parse_args()
    lake = Path(args.lake).resolve()
    manifest_path = lake / "manifest.json"
    if not manifest_path.exists():
        sys.exit(f"找不到 {manifest_path};请先运行 scripts/seed-synthetic-warehouse.py")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    datasets = manifest.get("datasets", [])
    if not datasets:
        sys.exit("manifest.json 没有 datasets")

    version = duckdb.__version__
    if not version.startswith(args.duckdb_version_prefix) and not args.allow_version_mismatch:
        sys.exit(
            f"当前 duckdb=={version},但 SuperSonic 内置 JDBC 需要 "
            f"duckdb {args.duckdb_version_prefix}x。\n"
            "DuckDB 存储格式不向后兼容;版本不匹配时 SuperSonic 可能打不开文件。\n"
            "建议用 Python 3.11 建独立环境:\n"
            "  python3.11 -m venv .venv-supersonic\n"
            "  .venv-supersonic/bin/pip install 'duckdb==0.10.0'\n"
            "  .venv-supersonic/bin/python scripts/build-supersonic-duckdb.py ...\n"
            "或者改用 StarRocks/Doris External Catalog。\n"
            "确认要生成不兼容文件时加 --allow-version-mismatch。"
        )

    view_root = Path(args.view_root).resolve() if args.view_root else lake
    if not view_root.exists():
        sys.exit(f"view root 不存在: {view_root}")

    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() and not args.force:
        sys.exit(f"{output} 已存在;确认覆盖后加 --force")
    if output.exists():
        output.unlink()

    con = duckdb.connect(str(output))
    statements = []
    for dataset in datasets:
        layer = dataset["layer"]
        name = dataset["name"]
        relative = dataset["path"]
        pattern = f"{str(view_root).rstrip('/')}/{relative}/**/*.parquet"
        view = f'"{layer}"."{name}"'
        con.execute(f'CREATE SCHEMA IF NOT EXISTS "{layer}"')
        statement = (
            f"CREATE OR REPLACE VIEW {view} AS "
            f"SELECT * FROM read_parquet('{sql_literal(pattern)}', hive_partitioning=true);"
        )
        con.execute(statement)
        statements.append(statement)

    # duckdb_views() 在 0.10/1.x 都存在;information_schema.views 在 0.10 不存在。
    view_count = con.execute(
        "SELECT count(*) FROM duckdb_views() WHERE schema_name IN ('ods','dwd','dws')"
    ).fetchone()[0]
    con.close()

    sql_path = output.with_name("supersonic-views.sql")
    sql_path.write_text("\n".join(statements) + "\n", encoding="utf-8")
    print(f"已生成 {output} (duckdb {version})")
    print(f"视图数: {view_count} (应接近 {len(datasets)})")
    print(f"SQL: {sql_path}")
    print("SuperSonic 中数据源请使用 DuckDB,数据库路径填容器内路径:")
    print(f"  {args.container_output}")
    print("compose 会把 DG_DATA_DIR 挂载到容器内同一绝对路径,视图内绝对路径才可解析。")


def sql_literal(value):
    return value.replace("'", "''")


if __name__ == "__main__":
    main()
