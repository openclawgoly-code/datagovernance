#!/usr/bin/env python3
"""生成一份“约 1000 张表 / 5GB”的合成 Parquet 数据湖,用于 Catalog/血缘/敏感识别 POC。

为什么需要它:Pagila/Chinook 只有十几张表,AdventureWorks 也只有 68 张;
它们能证明“功能通不通”,证明不了“1000 张表时目录采集、血缘、敏感扫描会不会塌”。
这个脚本用 DuckDB 直接生成 Parquet,不依赖 Spark/Hadoop,也不需要 Faker:

  ods_*   原始层:含 PII 字段(姓名/手机号/身份证/邮箱/地址/银行卡/诊断)
  dwd_*   明细层:从 ODS 派生,手机号/身份证/银行卡做了 mask,金额加了税
  dws_*   汇总层:按 status/region 聚合,金额 sum、行数 count

同时输出 manifest.json(数据集/字段/PII 列/行数)与 lineage.sql(ODS→DWD→DWS
的列级映射),后续可直接喂给平台的血缘解析器。

用法:
  pip install duckdb
  python3 scripts/seed-synthetic-warehouse.py --tables 1000 --rows 8000

输出默认在 data/synthetic-warehouse/（已 gitignore）。生成后:
  1. 新建 LOCAL 数据源,根目录 = data/synthetic-warehouse
  2. 新建 PARQUET_DIRECTORY 目录来源,rootPath = data/synthetic-warehouse
  3. 刷新目录,应看到约 1000 个数据集
"""
import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path

try:
    import duckdb
except ImportError:
    sys.exit("缺少 duckdb。请先执行: python3 -m pip install duckdb")

DOMAINS = [
    "sales", "order", "customer", "product", "inventory", "finance", "hr",
    "supply", "marketing", "service", "risk", "compliance", "logistics",
    "manufacturing", "procurement",
]
ENTITIES = [
    "master", "detail", "snapshot", "event", "ledger", "summary", "mapping",
    "history", "plan", "actual", "daily", "monthly", "channel", "region",
    "product", "customer", "supplier", "order", "invoice", "payment",
    "inventory", "shipment", "contract", "campaign", "ticket", "incident",
    "risk", "policy", "claim", "audit",
]
PII_COLUMNS = [
    "customer_name", "phone", "id_card_no", "email", "address",
    "bank_card_no", "diagnosis",
]


def parse_args():
    parser = argparse.ArgumentParser(description="生成合成 Parquet 数据湖")
    parser.add_argument("--output", default="data/synthetic-warehouse",
                        help="输出目录(默认 data/synthetic-warehouse)")
    parser.add_argument("--tables", type=int, default=1000,
                        help="总表数(默认 1000;按 40%% ODS / 40%% DWD / 20%% DWS 拆分)")
    parser.add_argument("--rows", type=int, default=8000,
                        help="每张 ODS/DWD 表的行数(默认 8000)")
    parser.add_argument("--dws-rows", type=int, default=0,
                        help="DWS 行数上限,0 表示按 status/region 聚合结果为准")
    parser.add_argument("--partitions", type=int, default=3,
                        help="每张表按 dt 分几个分区(默认 3)")
    parser.add_argument("--payload-bytes", type=int, default=512,
                        help="每行附加 payload 的近似字节数,用于把数据量撑到 GB 级;0 表示不加")
    parser.add_argument("--seed", type=int, default=42, help="确定性种子")
    parser.add_argument("--force", action="store_true", help="输出目录非空时覆盖")
    parser.add_argument("--dry-run", action="store_true", help="只打印规划,不生成文件")
    args = parser.parse_args()
    if args.tables < 3:
        sys.exit("--tables 至少为 3")
    if args.rows < 1:
        sys.exit("--rows 至少为 1")
    if args.partitions < 1:
        sys.exit("--partitions 至少为 1")
    return args


def main():
    args = parse_args()
    output = Path(args.output).resolve()
    ods_count = args.tables * 40 // 100
    dwd_count = args.tables * 40 // 100
    dws_count = args.tables - ods_count - dwd_count
    if ods_count == 0 or dwd_count == 0:
        sys.exit("--tables 太小,无法拆分三层")

    print(f"规划: ODS={ods_count} DWD={dwd_count} DWS={dws_count}, "
          f"每表 {args.rows} 行, {args.partitions} 个 dt 分区")
    if args.dry_run:
        print("dry-run: 不生成文件")
        return

    if output.exists() and any(output.iterdir()):
        if not args.force:
            sys.exit(f"{output} 非空。确认可覆盖后加 --force")
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    con = duckdb.connect()
    con.execute("PRAGMA threads=4")
    manifest = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "output": str(output),
        "seed": args.seed,
        "tables": args.tables,
        "rowsPerTable": args.rows,
        "partitions": args.partitions,
        "layers": {
            "ods": ods_count,
            "dwd": dwd_count,
            "dws": dws_count,
        },
        "datasets": [],
        "lineage": [],
    }
    lineage_sql = [
        "-- 合成数据湖的列级血缘。ODS -> DWD -> DWS;可直接用于后续 SQL 血缘解析测试。",
        "",
    ]

    started = time.time()
    ods_tables = []
    for index in range(ods_count):
        domain = DOMAINS[index % len(DOMAINS)]
        entity = ENTITIES[(index // len(DOMAINS)) % len(ENTITIES)]
        table = f"ods_{domain}_{entity}_{index:04d}"
        columns = columns_for(index, args.payload_bytes)
        path = output / "ods" / domain / table
        select_sql = ods_select(columns, index, args)
        copy_parquet(con, select_sql, path)
        ods_tables.append((table, domain, columns, path))
        add_dataset(manifest, "ods", domain, table, path, output, columns, args.rows)
        if (index + 1) % 50 == 0:
            print(f"  ODS {index + 1}/{ods_count}")

    dwd_tables = []
    for index, (ods_table, domain, columns, ods_path) in enumerate(ods_tables[:dwd_count]):
        table = ods_table.replace("ods_", "dwd_", 1)
        dwd_columns = dwd_columns_for(columns)
        path = output / "dwd" / domain / table
        source = read_parquet_sql(ods_path)
        select_sql = dwd_select(columns, source)
        copy_parquet(con, select_sql, path)
        dwd_tables.append((table, domain, dwd_columns, path))
        add_dataset(manifest, "dwd", domain, table, path, output, dwd_columns, args.rows)
        add_lineage(manifest, ods_table, table, columns, "ods_to_dwd")
        lineage_sql.append(dwd_lineage_sql(ods_table, table, columns))
        if (index + 1) % 50 == 0:
            print(f"  DWD {index + 1}/{dwd_count}")

    for index, (dwd_table, domain, dwd_columns, dwd_path) in enumerate(dwd_tables[:dws_count]):
        table = dwd_table.replace("dwd_", "dws_", 1)
        path = output / "dws" / domain / table
        source = read_parquet_sql(dwd_path)
        select_sql = dws_select(source, args.dws_rows)
        copy_parquet(con, select_sql, path)
        dws_row_count = con.execute(
            f"SELECT count(*) FROM read_parquet('{sql_literal(str(path / '**' / '*.parquet'))}', hive_partitioning=true)"
        ).fetchone()[0]
        dws_columns = [
            ("status", "VARCHAR"), ("region", "VARCHAR"), ("cnt", "BIGINT"),
            ("total_amount", "DECIMAL(18,2)"), ("last_seen", "TIMESTAMP"),
            ("dt", "DATE"),
        ]
        add_dataset(manifest, "dws", domain, table, path, output, dws_columns, dws_row_count)
        add_dws_lineage(manifest, dwd_table, table)
        lineage_sql.append(dws_lineage_sql(dwd_table, table))
        if (index + 1) % 50 == 0:
            print(f"  DWS {index + 1}/{dws_count}")

    con.close()

    total_bytes = sum(p.stat().st_size for p in output.rglob("*.parquet"))
    manifest["parquetBytes"] = total_bytes
    manifest["parquetGB"] = round(total_bytes / 1024 ** 3, 3)
    manifest["elapsedSeconds"] = round(time.time() - started, 1)
    write_json(output / "manifest.json", manifest)
    (output / "lineage.sql").write_text("\n".join(lineage_sql) + "\n", encoding="utf-8")
    write_readme(output, manifest, args)

    print(f"完成: {args.tables} 张表, {total_bytes / 1024 ** 3:.2f} GB, "
          f"耗时 {manifest['elapsedSeconds']}s")
    print(f"输出: {output}")
    print(f"manifest: {output / 'manifest.json'}")
    print(f"lineage:  {output / 'lineage.sql'}")


def columns_for(index, payload_bytes):
    """每张表都带基础/事实/维度列;约一半表额外带 PII,便于敏感扫描。"""
    columns = [
        ("id", "BIGINT"), ("biz_date", "DATE"),
        ("created_at", "TIMESTAMP"), ("updated_at", "TIMESTAMP"),
        ("amount", "DECIMAL(18,2)"), ("quantity", "INTEGER"),
        ("status", "VARCHAR"), ("region", "VARCHAR"),
        ("customer_id", "BIGINT"), ("product_id", "BIGINT"),
        ("name", "VARCHAR"), ("code", "VARCHAR"), ("category", "VARCHAR"),
        ("description", "VARCHAR"),
    ]
    if index % 2 == 0:
        columns.extend((name, "VARCHAR") for name in PII_COLUMNS)
    if payload_bytes > 0:
        columns.append(("payload", "VARCHAR"))
    return columns


def dwd_columns_for(columns):
    result = []
    for name, type_name in columns:
        if name == "phone":
            result.append(("phone_masked", "VARCHAR"))
        elif name == "id_card_no":
            result.append(("id_card_masked", "VARCHAR"))
        elif name == "bank_card_no":
            result.append(("bank_card_masked", "VARCHAR"))
        elif name == "amount":
            result.append(("amount", "DECIMAL(18,2)"))
            result.append(("amount_with_tax", "DECIMAL(18,2)"))
        else:
            result.append((name, type_name))
    return result


def ods_select(columns, index, args):
    """用 DuckDB SQL 生成确定性数据;不逐行走 Python。"""
    expressions = {
        "id": f"({index} * 1000000 + i)::BIGINT AS id",
        "biz_date": f"DATE '2026-10-26' - ((i + {index}) % {args.partitions})::INTEGER AS biz_date",
        "created_at": f"TIMESTAMP '2026-10-26 00:00:00' + i * INTERVAL 1 SECOND AS created_at",
        "updated_at": f"TIMESTAMP '2026-10-26 01:00:00' + i * INTERVAL 1 SECOND AS updated_at",
        "amount": f"round((hash(i + {args.seed} + 9) % 100000000) / 100.0, 2) AS amount",
        "quantity": f"(hash(i + {args.seed} + 10) % 100)::INTEGER AS quantity",
        "status": f"CASE (hash(i + {args.seed} + 11) % 3) "
                  "WHEN 0 THEN 'ACTIVE' WHEN 1 THEN 'INACTIVE' ELSE 'PENDING' END AS status",
        "region": f"'区域-' || ((hash(i + {args.seed} + 12) % 31) + 1)::VARCHAR AS region",
        "customer_id": f"(hash(i + {args.seed} + 13) % 100000)::BIGINT AS customer_id",
        "product_id": f"(hash(i + {args.seed} + 14) % 10000)::BIGINT AS product_id",
        "name": "('客户-' || i::VARCHAR) AS name",
        "code": f"('C' || lpad((hash(i + {args.seed} + 15) % 100000)::VARCHAR, 5, '0')) AS code",
        "category": f"CASE (hash(i + {args.seed} + 16) % 4) "
                    "WHEN 0 THEN 'A' WHEN 1 THEN 'B' WHEN 2 THEN 'C' ELSE 'D' END AS category",
        "description": "('描述-' || i::VARCHAR) AS description",
        "customer_name": "('客户姓名-' || i::VARCHAR) AS customer_name",
        "phone": f"('138' || lpad((hash(i + {args.seed} + 17) % 100000000)::VARCHAR, 8, '0')) AS phone",
        "id_card_no": f"('11010119900101' || lpad((hash(i + {args.seed} + 18) % 10000)::VARCHAR, 4, '0')) AS id_card_no",
        "email": "('user' || i::VARCHAR || '@example.com') AS email",
        "address": f"('上海市浦东新区' || (hash(i + {args.seed} + 19) % 1000)::VARCHAR || '号') AS address",
        "bank_card_no": f"('6222' || lpad((hash(i + {args.seed} + 20) % 1000000000000)::VARCHAR, 12, '0')) AS bank_card_no",
        "diagnosis": f"('诊断-' || (hash(i + {args.seed} + 21) % 100)::VARCHAR) AS diagnosis",
    }
    if args.payload_bytes > 0:
        # 不要用 repeat(md5(i), N):同一行里重复同一个 32 字节串会被 Parquet
        # 压到几乎为零,数据量永远撑不到 GB 级。这里用 16 个不同的 md5 拼成
        # 一个 512 字节块;需要更长时重复“块”,至少保留一定熵。
        chunks = max(1, args.payload_bytes // 32)
        block = [f"md5((i + {args.seed} + {k})::VARCHAR)" for k in range(min(chunks, 16))]
        block_expr = " || ".join(block)
        repeat = max(1, chunks // len(block))
        expressions["payload"] = f"repeat(({block_expr}), {repeat}) AS payload"
    selected = [expressions[name] for name, _ in columns]
    selected.append(f"DATE '2026-10-26' - ((i + {index}) % {args.partitions})::INTEGER AS dt")
    return f"SELECT {', '.join(selected)} FROM range(0, {args.rows}) t(i)"


def dwd_select(columns, source):
    expressions = []
    for name, _ in columns:
        if name == "phone":
            expressions.append("('***' || right(phone, 4)) AS phone_masked")
        elif name == "id_card_no":
            expressions.append("('***' || right(id_card_no, 4)) AS id_card_masked")
        elif name == "bank_card_no":
            expressions.append("('***' || right(bank_card_no, 4)) AS bank_card_masked")
        elif name == "amount":
            expressions.append("amount")
            expressions.append("round(amount * 1.06, 2) AS amount_with_tax")
        else:
            expressions.append(name)
    expressions.append("dt")
    return f"SELECT {', '.join(expressions)} FROM {source}"


def dws_select(source, limit):
    sql = (
        "SELECT status, region, count(*) AS cnt, "
        "sum(amount_with_tax) AS total_amount, max(updated_at) AS last_seen, "
        "max(dt) AS dt "
        f"FROM {source} GROUP BY status, region"
    )
    if limit and limit > 0:
        sql += f" LIMIT {limit}"
    return sql


def read_parquet_sql(path):
    return f"read_parquet('{sql_literal(str(path / '**' / '*.parquet'))}', hive_partitioning=true)"


def copy_parquet(con, select_sql, path):
    path.mkdir(parents=True, exist_ok=True)
    sql = (
        f"COPY ({select_sql}) TO '{sql_literal(str(path))}' "
        "(FORMAT PARQUET, PARTITION_BY (dt), OVERWRITE_OR_IGNORE)"
    )
    con.execute(sql)


def sql_literal(value):
    return value.replace("'", "''")


def add_dataset(manifest, layer, domain, table, path, output, columns, row_count):
    manifest["datasets"].append({
        "name": table,
        "layer": layer,
        "domain": domain,
        "path": str(path.relative_to(output)),
        "columns": [{"name": name, "type": type_name} for name, type_name in columns],
        "piiColumns": [name for name, _ in columns if name in PII_COLUMNS],
        "rowCount": row_count,
    })


def add_lineage(manifest, ods_table, dwd_table, columns, transform):
    for name, _ in columns:
        if name in ("phone", "id_card_no", "bank_card_no"):
            target = f"{name}_masked"
        elif name == "amount":
            target = "amount_with_tax"
        else:
            target = name
        manifest["lineage"].append({
            "from": f"ods/{ods_table}",
            "fromColumn": name,
            "to": f"dwd/{dwd_table}",
            "toColumn": target,
            "transform": "mask" if name in ("phone", "id_card_no", "bank_card_no")
            else ("amount * 1.06" if name == "amount" else name),
        })


def add_dws_lineage(manifest, dwd_table, dws_table):
    for from_column, to_column, transform in (
            ("status", "status", "group by"),
            ("region", "region", "group by"),
            ("id", "cnt", "count"),
            ("amount_with_tax", "total_amount", "sum"),
            ("updated_at", "last_seen", "max")):
        manifest["lineage"].append({
            "from": f"dwd/{dwd_table}",
            "fromColumn": from_column,
            "to": f"dws/{dws_table}",
            "toColumn": to_column,
            "transform": transform,
        })


def dwd_lineage_sql(ods_table, dwd_table, columns):
    select = []
    for name, _ in columns:
        if name == "phone":
            select.append("('***' || right(phone, 4)) AS phone_masked")
        elif name == "id_card_no":
            select.append("('***' || right(id_card_no, 4)) AS id_card_masked")
        elif name == "bank_card_no":
            select.append("('***' || right(bank_card_no, 4)) AS bank_card_masked")
        elif name == "amount":
            select.append("amount, round(amount * 1.06, 2) AS amount_with_tax")
        else:
            select.append(name)
    return f"INSERT INTO dwd.{dwd_table} SELECT {', '.join(select)} FROM ods.{ods_table};"


def dws_lineage_sql(dwd_table, dws_table):
    return (
        f"INSERT INTO dws.{dws_table} "
        "SELECT status, region, count(*) AS cnt, sum(amount_with_tax) AS total_amount, "
        f"max(updated_at) AS last_seen FROM dwd.{dwd_table} GROUP BY status, region;"
    )


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def write_readme(output, manifest, args):
    lines = [
        "# Synthetic Parquet Warehouse",
        "",
        "由 `scripts/seed-synthetic-warehouse.py` 生成。",
        "",
        f"- 表数: {manifest['tables']} (ODS {manifest['layers']['ods']} / "
        f"DWD {manifest['layers']['dwd']} / DWS {manifest['layers']['dws']})",
        f"- 每表行数: {args.rows}",
        f"- dt 分区数: {args.partitions}",
        f"- Parquet 大小: {manifest.get('parquetGB', 0)} GB",
        f"- 生成耗时: {manifest['elapsedSeconds']}s",
        "",
        "## 平台接入",
        "",
        "1. 新建 `LOCAL` 数据源，根目录填本目录。",
        "2. 新建 `PARQUET_DIRECTORY` 目录来源，rootPath 填本目录。",
        "3. 刷新后应看到约 1000 个 `PARQUET_DATASET`。",
        "",
        "## 文件",
        "",
        "- `manifest.json`: 数据集/字段/PII 列/行数/血缘边",
        "- `lineage.sql`: ODS→DWD→DWS 的列级映射 SQL",
        "",
    ]
    (output / "README.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
