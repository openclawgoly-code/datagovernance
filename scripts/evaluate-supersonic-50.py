#!/usr/bin/env python3
"""跑 50 问 SuperSonic 评测，和 ClickHouse 标准 SQL 结果对比。

用法：
  SUPERSONIC_PASSWORD=admin123 python3 scripts/evaluate-supersonic-50.py

输出：
  tmp/supersonic-50-results.csv
  tmp/supersonic-50-results.json
  docs/plans/2026-10-28-supersonic-50-eval.md
"""
import argparse
import base64
import csv
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DWS_TABLES = [
    "dws_sales_master_0000",
    "dws_order_master_0001",
    "dws_customer_master_0002",
    "dws_product_master_0003",
    "dws_inventory_master_0004",
    "dws_finance_master_0005",
]
DWD_TABLES = [
    "dwd_sales_master_0000",
    "dwd_order_master_0001",
    "dwd_customer_master_0002",
    "dwd_product_master_0003",
    "dwd_inventory_master_0004",
    "dwd_finance_master_0005",
    "dwd_hr_master_0006",
    "dwd_supply_master_0007",
    "dwd_marketing_master_0008",
    "dwd_service_master_0009",
    "dwd_risk_master_0010",
    "dwd_compliance_master_0011",
]
ODS_TABLES = ["ods_order_master_0001", "ods_product_master_0003"]


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.environ.get("SUPERSONIC_URL", "http://localhost:9080"))
    parser.add_argument("--user", default=os.environ.get("SUPERSONIC_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("SUPERSONIC_PASSWORD", "admin123"))
    parser.add_argument("--agent-id", type=int, default=5)
    parser.add_argument("--chat-id", type=int, default=0, help="0 表示新建/复用 POC-50 会话")
    parser.add_argument("--map", default="tmp/supersonic-model-map.json")
    parser.add_argument("--expected-engine", choices=["clickhouse", "starrocks"],
                        default="clickhouse")
    parser.add_argument("--clickhouse-url", default="http://localhost:18123")
    parser.add_argument("--clickhouse-user", default="default")
    parser.add_argument("--clickhouse-password", default="clickhouse")
    parser.add_argument("--starrocks-host", default="127.0.0.1")
    parser.add_argument("--starrocks-port", default="19030")
    parser.add_argument("--starrocks-user", default="root")
    parser.add_argument("--starrocks-password", default="")
    parser.add_argument("--out-csv", default="tmp/supersonic-50-results.csv")
    parser.add_argument("--out-json", default="tmp/supersonic-50-results.json")
    parser.add_argument("--out-md", default="docs/plans/2026-10-28-supersonic-50-eval.md")
    parser.add_argument("--sleep", type=float, default=0.3)
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 题(调试用)")
    return parser.parse_args()


class HttpClient:
    def __init__(self, token=None):
        self.token = token

    def json(self, url, method="GET", body=None, timeout=240):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))


def login(base, user, password):
    payload = {"name": user, "password": password}
    result = HttpClient().json(base.rstrip("/") + "/api/auth/user/login", "POST", payload, 60)
    if result.get("code") != 200:
        raise RuntimeError("登录失败: " + str(result.get("msg")))
    return result["data"]


def clickhouse_query(base, user, password, sql):
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    req = urllib.request.Request(
        base.rstrip("/") + "/",
        data=sql.encode("utf-8"),
        method="POST",
        headers={"Authorization": "Basic " + auth, "Content-Type": "text/plain"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        text = resp.read().decode("utf-8")
    return [line.split("\t") for line in text.splitlines() if line.strip()]


def starrocks_query(args, sql):
    command = [
        "mysql", "-h", args.starrocks_host, "-P", str(args.starrocks_port),
        "-u", args.starrocks_user, "-N", "-B", "--default-character-set=utf8mb4",
        "-e", sql,
    ]
    if args.starrocks_password:
        command.insert(5, f"-p{args.starrocks_password}")
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError("StarRocks 标准查询失败: " + result.stderr[-1000:])
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def expected_query(args, sql):
    if args.expected_engine == "starrocks":
        return starrocks_query(args, sql)
    return clickhouse_query(args.clickhouse_url, args.clickhouse_user,
                            args.clickhouse_password, sql)


def question_bank(map_data):
    questions = []

    def add(category, dataset, question, expected_sql, expected_columns):
        questions.append({
            "id": len(questions) + 1,
            "category": category,
            "dataset": dataset,
            "dataSetId": map_data[dataset]["dataset_id"],
            "question": question,
            "expected_sql": expected_sql,
            "expected_columns": expected_columns,
        })

    for table in DWS_TABLES:
        add("DWS 聚合", table, "按状态统计记录数",
            f"SELECT status, sum(cnt) AS cnt FROM synthetic.{table} GROUP BY status ORDER BY status",
            ["status", "cnt"])
        add("DWS 聚合", table, "按区域统计总金额",
            f"SELECT region, sum(total_amount) AS total FROM synthetic.{table} GROUP BY region ORDER BY region",
            ["region", "total"])
        add("DWS 趋势", table, "按日期统计总金额",
            f"SELECT dt, sum(total_amount) AS total FROM synthetic.{table} GROUP BY dt ORDER BY dt",
            ["dt", "total"])
        add("DWS TopN", table, "总金额最高的3个区域，并返回对应总金额",
            f"SELECT region, sum(total_amount) AS total FROM synthetic.{table} GROUP BY region ORDER BY total DESC LIMIT 3",
            ["region", "total"])

    for table in DWD_TABLES:
        add("DWD 聚合", table, "按状态统计金额",
            f"SELECT status, sum(amount) AS total FROM synthetic.{table} GROUP BY status ORDER BY status",
            ["status", "total"])
        add("DWD 聚合", table, "按区域统计数量",
            f"SELECT region, sum(quantity) AS total FROM synthetic.{table} GROUP BY region ORDER BY region",
            ["region", "total"])

    for table in ODS_TABLES:
        add("ODS 明细", table, "按状态统计金额",
            f"SELECT status, sum(amount) AS total FROM synthetic.{table} GROUP BY status ORDER BY status",
            ["status", "total"])
    return questions


def ensure_chat(client, base, agent_id):
    chats = client.json(base + f"/api/chat/manage/getAll?agentId={agent_id}")["data"] or []
    for chat in chats:
        if chat.get("chatName") == "POC-50":
            return chat["chatId"]
    client.json(base + "/api/chat/manage/save?" + urllib.parse.urlencode({"chatName": "POC-50", "agentId": agent_id}), "POST", None)
    chats = client.json(base + f"/api/chat/manage/getAll?agentId={agent_id}")["data"] or []
    for chat in chats:
        if chat.get("chatName") == "POC-50":
            return chat["chatId"]
    raise RuntimeError("无法创建 POC-50 会话")


def to_float(value):
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return None


def same_number(a, b):
    if a is None or b is None:
        return False
    return abs(a - b) <= max(0.01, abs(b) * 1e-6)


def expected_map(rows, columns):
    key_name, value_name = columns[0], columns[1]
    result = {}
    for row in rows:
        if len(row) < 2:
            continue
        result[str(row[0])] = to_float(row[1])
    return result


def actual_map(rows, expected_keys):
    result = {}
    for row in rows:
        key = None
        value = None
        for k, v in row.items():
            if key is None and str(v) in expected_keys:
                key = str(v)
                continue
            if value is None and to_float(v) is not None:
                value = to_float(v)
        if key is not None:
            result[key] = value
    return result


def evaluate(question, response, expected_rows):
    data = response.get("data") or {}
    state = data.get("queryState")
    sql = data.get("querySql")
    actual_rows = data.get("queryResults") or []
    error = data.get("errorMsg")
    expected = expected_map(expected_rows, question["expected_columns"])
    keys = set(expected.keys())

    if state != "SUCCESS":
        return "FAIL", expected, {}, sql, error or f"queryState={state}"

    if len(question["expected_columns"]) == 1:
        actual = None
        for row in actual_rows:
            for value in row.values():
                if to_float(value) is not None:
                    actual = to_float(value)
                    break
            if actual is not None:
                break
        # 单值题当前题库没有使用，保留兼容
        exp = next(iter(expected.values()))
        return ("PASS" if same_number(actual, exp) else "FAIL"), expected, actual, sql, error

    actual = actual_map(actual_rows, keys)
    if not actual:
        return "FAIL", expected, {}, sql, error or "empty result"
    if set(actual.keys()) != keys:
        return "FAIL", expected, actual, sql, error or "key set mismatch"
    if all(same_number(actual.get(k), v) for k, v in expected.items()):
        return "PASS", expected, actual, sql, error
    if all(actual.get(k) is None for k in keys):
        return "PARTIAL", expected, actual, sql, error or "metric missing"
    return "FAIL", expected, actual, sql, error or "value mismatch"


def write_markdown(path, rows, summary):
    lines = [
        "# SuperSonic 50 问评测结果",
        "",
        f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 总数：{summary['total']}，PASS：{summary['pass']}，PARTIAL：{summary['partial']}，FAIL：{summary['fail']}",
        f"- 通过率：{summary['pass'] / max(1, summary['total']):.1%}",
        "",
        "| # | 分类 | 数据集 | 问题 | 结果 | 生成 SQL |",
        "|---:|---|---|---|---|---|",
    ]
    for row in rows:
        sql = (row.get("sql") or "").replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {row['id']} | {row['category']} | {row['dataset']} | {row['question']} | "
            f"{row['status']} | `{sql[:220]}` |"
        )
    lines += ["", "## 失败/部分通过明细", ""]
    bad = [r for r in rows if r["status"] != "PASS"]
    if not bad:
        lines.append("无。")
    else:
        for row in bad:
            lines.append(f"### {row['id']}. {row['question']}（{row['dataset']}）")
            lines.append(f"- 结果：{row['status']}")
            lines.append(f"- 生成 SQL：`{row.get('sql') or ''}`")
            lines.append(f"- 错误：{row.get('error') or '无'}")
            lines.append(f"- 期望：{json.dumps(row.get('expected'), ensure_ascii=False)}")
            lines.append(f"- 实际：{json.dumps(row.get('actual'), ensure_ascii=False)}")
            lines.append("")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    args = parse_args()
    base = args.url.rstrip("/")
    map_data = json.loads(Path(args.map).read_text(encoding="utf-8"))
    questions = question_bank(map_data)
    if len(questions) != 50:
        sys.exit(f"题库应为 50 题，实际 {len(questions)}")
    if args.limit:
        questions = questions[: args.limit]

    token = login(base, args.user, args.password)
    client = HttpClient(token)
    chat_id = args.chat_id or ensure_chat(client, base, args.agent_id)

    results = []
    for q in questions:
        expected_rows = expected_query(args, q["expected_sql"])
        payload = {
            "queryText": q["question"],
            "agentId": args.agent_id,
            "dataSetId": q["dataSetId"],
            "chatId": chat_id,
            "saveAnswer": False,
        }
        error = None
        try:
            response = client.json(base + "/api/chat/query/", "POST", payload, 240)
        except Exception as exc:
            response = {}
            error = str(exc)
        status, expected, actual, sql, eval_error = evaluate(q, response, expected_rows)
        result = {
            "id": q["id"],
            "category": q["category"],
            "dataset": q["dataset"],
            "question": q["question"],
            "status": status,
            "expected": expected,
            "actual": actual,
            "sql": sql,
            "error": eval_error or error,
            "queryState": (response.get("data") or {}).get("queryState"),
        }
        results.append(result)
        print(f"[{q['id']:02d}/50] {status:7s} {q['dataset']:26s} {q['question']}")
        time.sleep(args.sleep)

    summary = {
        "total": len(results),
        "pass": sum(1 for r in results if r["status"] == "PASS"),
        "partial": sum(1 for r in results if r["status"] == "PARTIAL"),
        "fail": sum(1 for r in results if r["status"] == "FAIL"),
    }
    Path(args.out_json).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out_json).write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    with open(args.out_csv, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "category", "dataset", "question", "status", "sql", "error", "expected", "actual"])
        writer.writeheader()
        for row in results:
            writer.writerow({
                **{k: row[k] for k in ("id", "category", "dataset", "question", "status", "sql", "error")},
                "expected": json.dumps(row["expected"], ensure_ascii=False),
                "actual": json.dumps(row["actual"], ensure_ascii=False),
            })
    write_markdown(args.out_md, results, summary)
    print("\n总结:", summary)
    print("结果:", args.out_json)
    print("CSV:", args.out_csv)
    print("Markdown:", args.out_md)


if __name__ == "__main__":
    main()
