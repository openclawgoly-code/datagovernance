#!/usr/bin/env python3
"""为 SuperSonic POC 批量创建 20 个语义模型、数据集，并绑定到一个助手。

前置：
  - SuperSonic 已启动；
  - ClickHouse 已加载 data/synthetic-warehouse；
  - 已创建主题域（默认 id=4）和 DeepSeek 模型（默认 id=2）。

用法：
  SUPERSONIC_PASSWORD=admin123 python3 scripts/seed-supersonic-semantic.py
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

# 选择 12 张 DWD + 6 张 DWS + 2 张无 PII 的 ODS = 20 张核心表
ODS_TABLES = ["ods_order_master_0001", "ods_product_master_0003"]

EXCLUDE_COLUMNS = {
    "payload", "diagnosis", "phone", "id_card_no", "bank_card_no",
    "email", "address", "customer_name",
}

CHINESE_NAMES = {
    "id": "主键",
    "biz_date": "业务日期",
    "created_at": "创建时间",
    "updated_at": "更新时间",
    "amount": "金额",
    "amount_with_tax": "含税金额",
    "quantity": "数量",
    "status": "状态",
    "region": "区域",
    "customer_id": "客户ID",
    "product_id": "产品ID",
    "name": "名称",
    "code": "编码",
    "category": "分类",
    "description": "描述",
    "customer_name": "客户名称",
    "phone": "手机号",
    "phone_masked": "手机号脱敏",
    "id_card_no": "身份证",
    "id_card_masked": "身份证脱敏",
    "email": "邮箱",
    "address": "地址",
    "bank_card_no": "银行卡",
    "bank_card_masked": "银行卡脱敏",
    "diagnosis": "诊断",
    "payload": "负载",
    "cnt": "记录数",
    "total_amount": "总金额",
    "last_seen": "最近时间",
    "dt": "日期",
}

MODEL_LABELS = {
    "dwd_sales_master_0000": "销售明细",
    "dwd_order_master_0001": "订单明细",
    "dwd_customer_master_0002": "客户明细",
    "dwd_product_master_0003": "商品明细",
    "dwd_inventory_master_0004": "库存明细",
    "dwd_finance_master_0005": "财务明细",
    "dwd_hr_master_0006": "人力明细",
    "dwd_supply_master_0007": "供应明细",
    "dwd_marketing_master_0008": "营销明细",
    "dwd_service_master_0009": "服务明细",
    "dwd_risk_master_0010": "风险明细",
    "dwd_compliance_master_0011": "合规明细",
    "dws_sales_master_0000": "销售汇总",
    "dws_order_master_0001": "订单汇总",
    "dws_customer_master_0002": "客户汇总",
    "dws_product_master_0003": "商品汇总",
    "dws_inventory_master_0004": "库存汇总",
    "dws_finance_master_0005": "财务汇总",
    "ods_order_master_0001": "订单原始明细",
    "ods_product_master_0003": "商品原始明细",
}

MEASURE_NAMES = {"amount", "amount_with_tax", "quantity", "cnt", "total_amount"}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.environ.get("SUPERSONIC_URL", "http://localhost:9080"))
    parser.add_argument("--user", default=os.environ.get("SUPERSONIC_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("SUPERSONIC_PASSWORD", "admin123"))
    parser.add_argument("--domain-id", type=int, default=int(os.environ.get("SUPERSONIC_DOMAIN_ID", "4")))
    parser.add_argument("--database-id", type=int, default=int(os.environ.get("SUPERSONIC_DATABASE_ID", "3")))
    parser.add_argument("--chat-model-id", type=int, default=int(os.environ.get("SUPERSONIC_CHAT_MODEL_ID", "2")))
    parser.add_argument("--agent-id", type=int, default=int(os.environ.get("SUPERSONIC_AGENT_ID", "5")))
    parser.add_argument("--lake", default="data/synthetic-warehouse")
    parser.add_argument("--map-output", default="tmp/supersonic-model-map.json")
    parser.add_argument("--limit", type=int, default=0)
    return parser.parse_args()


class Client:
    def __init__(self, base, token):
        self.base = base.rstrip("/")
        self.token = token

    def request(self, path, method="GET", body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={
                "Authorization": "Bearer " + self.token,
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body_text = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {body_text[:800]}") from exc

    def ok(self, path, method="GET", body=None):
        result = self.request(path, method, body)
        if result.get("code") != 200:
            raise RuntimeError(f"{method} {path} -> {result.get('msg')}")
        return result.get("data")


def login(base, user, password):
    req = urllib.request.Request(
        base.rstrip("/") + "/api/auth/user/login",
        data=json.dumps({"name": user, "password": password}).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if result.get("code") != 200:
        raise RuntimeError("SuperSonic 登录失败: " + str(result.get("msg")))
    return result["data"]


def clickhouse_column_type(type_name):
    t = type_name.upper()
    if t.startswith("DECIMAL"):
        return type_name
    return {
        "BIGINT": "Int64",
        "INTEGER": "Int32",
        "INT": "Int32",
        "SMALLINT": "Int16",
        "TINYINT": "Int8",
        "DOUBLE": "Float64",
        "FLOAT": "Float32",
        "BOOLEAN": "Bool",
        "DATE": "Date",
        "TIMESTAMP": "DateTime64(6)",
        "VARCHAR": "String",
    }.get(t, "String")


def field_type(column):
    name = column["name"]
    t = column["type"].upper()
    if name == "id":
        return "primary_key"
    if name == "dt":
        return "partition_time"
    if name in ("biz_date", "created_at", "updated_at", "last_seen") or t in ("DATE", "TIMESTAMP"):
        return "time"
    # 这些是关联键，不是度量；作为维度才能被过滤/分组。
    if name in ("customer_id", "product_id"):
        return "categorical"
    if name in MEASURE_NAMES or t.startswith("DECIMAL") or t in ("BIGINT", "INTEGER", "INT", "DOUBLE", "FLOAT"):
        return "measure"
    return "categorical"


def select_tables(manifest):
    datasets = manifest["datasets"]
    selected = [d for d in datasets if d["layer"] in ("dwd", "dws")]
    selected += [d for d in datasets if d["name"] in ODS_TABLES]
    order = {name: i for i, name in enumerate(MODEL_LABELS)}
    selected.sort(key=lambda d: order.get(d["name"], 999))
    return selected


def build_db_schema(dataset):
    columns = []
    for c in dataset.get("columns", []):
        if c["name"] in EXCLUDE_COLUMNS:
            continue
        columns.append({
            "columnName": c["name"],
            "dataType": clickhouse_column_type(c["type"]),
            "comment": CHINESE_NAMES.get(c["name"], c["name"]),
            "fieldType": field_type(c),
        })
    return {
        "db": "synthetic",
        "table": dataset["name"],
        "dbColumns": columns,
    }


def ensure_model(client, dataset, domain_id, database_id):
    table = dataset["name"]
    models = client.ok(f"/api/semantic/model/getModelList/{domain_id}") or []
    for model in models:
        if model.get("bizName") == table:
            return model["id"], False

    payload = {
        "name": MODEL_LABELS.get(table, table),
        "bizName": table,
        "databaseId": database_id,
        "domainId": domain_id,
        "db": "synthetic",
        "tables": [table],
        "buildByLLM": False,
        "dbSchemas": [build_db_schema(dataset)],
    }
    client.ok("/api/semantic/model/createModelBatch", "POST", payload)
    models = client.ok(f"/api/semantic/model/getModelList/{domain_id}") or []
    for model in models:
        if model.get("bizName") == table:
            return model["id"], True
    raise RuntimeError(f"创建后找不到模型: {table}")


def update_metrics(client, model_id):
    metrics = client.ok(f"/api/semantic/metric/getMetricList/{model_id}") or []
    for metric in metrics:
        biz = metric.get("bizName")
        if biz not in MEASURE_NAMES:
            continue
        params = metric.get("metricDefineByMeasureParams") or {}
        measures = params.get("measures") or []
        if not measures:
            continue
        measure = dict(measures[0])
        measure["agg"] = "SUM"
        measure["name"] = CHINESE_NAMES.get(biz, measure.get("name", biz))
        measure["expr"] = measure.get("expr") or biz
        payload = {
            "id": metric["id"],
            "modelId": model_id,
            "name": CHINESE_NAMES.get(biz, biz),
            "bizName": biz,
            "description": CHINESE_NAMES.get(biz, biz),
            "status": metric.get("status", 1),
            "metricDefineType": "MEASURE",
            "metricDefineByMeasureParams": {
                "expr": params.get("expr") or biz,
                "measures": [measure],
            },
        }
        client.ok("/api/semantic/metric/updateMetric", "POST", payload)


def update_dimensions(client, model_id):
    dims = client.ok(f"/api/semantic/dimension/getDimensionList/{model_id}") or []
    existing = {d.get("bizName") for d in dims}

    # 旧模型可能把关联键建成了 metric；删掉，改回维度。
    metrics = client.ok(f"/api/semantic/metric/getMetricList/{model_id}") or []
    for metric in metrics:
        if metric.get("bizName") in ("customer_id", "product_id"):
            client.ok(f"/api/semantic/metric/deleteMetric/{metric['id']}", "DELETE")

    for dim in dims:
        biz = dim.get("bizName")
        payload = {
            "id": dim["id"],
            "modelId": model_id,
            "name": CHINESE_NAMES.get(biz, biz),
            "bizName": biz,
            "description": CHINESE_NAMES.get(biz, biz),
            "type": dim.get("type"),
            "expr": dim.get("expr") or biz,
            "semanticType": dim.get("semanticType") or "CATEGORY",
            "status": dim.get("status", 1),
        }
        client.ok("/api/semantic/dimension/updateDimension", "POST", payload)

    for name in ("customer_id", "product_id"):
        if name not in existing:
            payload = {
                "modelId": model_id,
                "name": CHINESE_NAMES[name],
                "bizName": name,
                "description": CHINESE_NAMES[name],
                "type": "categorical",
                "expr": name,
                "semanticType": "CATEGORY",
                "status": 1,
            }
            client.ok("/api/semantic/dimension/createDimension", "POST", payload)


def ensure_dataset(client, dataset, domain_id, model_id):
    table = dataset["name"]
    biz = table + "_dataset"
    datasets = client.ok(f"/api/semantic/dataSet/getDataSetList?domainId={domain_id}") or []
    for item in datasets:
        configs = ((item.get("dataSetDetail") or {}).get("dataSetModelConfigs") or [])
        if item.get("bizName") == biz or any(c.get("id") == model_id for c in configs):
            return item["id"], False

    payload = {
        "name": MODEL_LABELS.get(table, table) + "数据集",
        "bizName": biz,
        "description": "POC dataset for " + table,
        "domainId": domain_id,
        "isOpen": 1,
        "dataSetDetail": {
            "dataSetModelConfigs": [
                {"id": model_id, "includesAll": True, "metrics": [], "dimensions": []}
            ]
        },
    }
    created = client.ok("/api/semantic/dataSet", "POST", payload)
    return created["id"], True


def update_agent(client, agent_id, dataset_ids, chat_model_id):
    agents = client.ok("/api/chat/agent/getAgentList") or []
    agent = next((a for a in agents if a.get("id") == agent_id), None)
    if not agent:
        raise RuntimeError(f"找不到助手 id={agent_id}")
    agent = dict(agent)
    for key in ("createdBy", "updatedBy", "createdAt", "updatedAt", "dataSetIds"):
        agent.pop(key, None)
    agent["isOpen"] = 1
    agent["toolConfig"] = json.dumps(
        {"tools": [{"dataSetIds": dataset_ids, "id": "1", "type": "DATASET"}]},
        ensure_ascii=False,
    )
    for app in (agent.get("chatAppConfig") or {}).values():
        app["chatModelId"] = chat_model_id
        if app.get("name") in ("语义SQL解析", "结果数据解读"):
            app["enable"] = True
    client.ok("/api/chat/agent", "PUT", agent)


def main():
    args = parse_args()
    manifest = json.loads((Path(args.lake) / "manifest.json").read_text(encoding="utf-8"))
    tables = select_tables(manifest)
    if args.limit:
        tables = tables[: args.limit]
    token = login(args.url, args.user, args.password)
    client = Client(args.url, token)

    mapping = {}
    for i, dataset in enumerate(tables, 1):
        table = dataset["name"]
        model_id, model_created = ensure_model(client, dataset, args.domain_id, args.database_id)
        update_metrics(client, model_id)
        update_dimensions(client, model_id)
        dataset_id, dataset_created = ensure_dataset(client, dataset, args.domain_id, model_id)
        mapping[table] = {"model_id": model_id, "dataset_id": dataset_id}
        print(
            f"[{i}/{len(tables)}] {table} model={model_id}{'*' if model_created else ''} "
            f"dataset={dataset_id}{'*' if dataset_created else ''}"
        )

    dataset_ids = [item["dataset_id"] for item in mapping.values()]
    update_agent(client, args.agent_id, dataset_ids, args.chat_model_id)
    Path(args.map_output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.map_output).write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n完成: {len(mapping)} 个模型/数据集, 助手 {args.agent_id} 已绑定")
    print(f"映射: {args.map_output}")


if __name__ == "__main__":
    main()
