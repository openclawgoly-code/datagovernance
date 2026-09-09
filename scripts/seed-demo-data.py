#!/usr/bin/env python3
"""给演示环境铺一层真实数据 —— 空列表页截出来没有意义。

只铺"后面截图要看的东西":几个数据源、几个不同类型的任务、几条真跑出来的
执行记录、一条告警渠道与规则。全部走真实接口,不直接改库。
"""
import json, os, sys, time, urllib.error, urllib.request

API = "http://127.0.0.1:8080/api/v1"
PW = os.environ["DG_ADMIN_PASSWORD"]
PG = dict(host="127.0.0.1", port=55432, user="postgres", password="postgres")
token = workspace = None

def call(m, p, body=None, raw=False):
    req = urllib.request.Request(API + p, data=json.dumps(body).encode() if body is not None else None, method=m)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    if workspace: req.add_header("X-Workspace-Id", workspace)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return (r.status, json.loads(r.read())) if raw else json.loads(r.read())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read())
        return (e.code, payload) if raw else payload

r = call("POST", "/auth/login", {"username": "admin", "password": PW})
token = r["data"]["token"]
workspace = r["data"].get("currentWorkspaceId") or r["data"]["workspaces"][0]["id"]
print("登录成功,空间", workspace)

def ds(name, dstype, **kw):
    body = {"name": name, "type": dstype,
            "inlineSecret": {"authType": "PASSWORD", "username": kw.get("username", ""),
                             "secret": kw.pop("secret", "")}}
    body.update(kw)
    res = call("POST", "/datasources", body)
    if not res.get("success"):
        print("  跳过", name, res.get("message")); return None
    i = res["data"]["id"]
    call("POST", f"/datasources/{i}/test")
    return i

print("建数据源…")
pagila = ds("Pagila-影音租赁库", "POSTGRESQL", host=PG["host"], port=PG["port"],
            databaseName="dg_pagila", username=PG["user"], secret=PG["password"])
chinook = ds("Chinook-数字音乐商店", "POSTGRESQL", host=PG["host"], port=PG["port"],
             databaseName="dg_chinook", username=PG["user"], secret=PG["password"])
probe = ds("数仓-ODS 层", "POSTGRESQL", host=PG["host"], port=PG["port"],
           databaseName="dg_probe", username=PG["user"], secret=PG["password"])
mysql = ds("MySQL-业务库(信创迁移源)", "MYSQL", host="127.0.0.1", port=33306,
           databaseName="dg_chinook", username="root", secret="mysql")
health = ds("健康数据交换-FTP", "FTP", host="127.0.0.1", port=2121,
            databaseName="/", username="dgseed", secret="dgseed")
ds("亭湖区全民健康信息平台", "REST_API", baseUrl="https://health.example.gov.cn/api")
print("  已建", sum(1 for x in (pagila, chinook, probe, mysql, health) if x), "个可用数据源")

# 目录探测:任务编译要读表结构
import subprocess
def psql(sql, db):
    env = dict(os.environ, PGPASSWORD=PG["password"])
    return subprocess.run(["psql","-h",PG["host"],"-p",str(PG["port"]),"-U",PG["user"],
                           "-d",db,"-tAc",sql], capture_output=True, text=True, env=env).stdout.strip()

SCHEMA = "dg_probe_schema"
psql(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}", "dg_probe")
for t, cols in (("ods_film", "film_id int, title text, rating text, rental_rate numeric(4,2)"),
                ("ods_customer", "customer_id int, first_name text, last_name text, email text")):
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{t}; CREATE TABLE {SCHEMA}.{t} ({cols})", "dg_probe")

call("GET", f"/datasources/{pagila}/catalog?database=dg_pagila&schema=public&refresh=true")
for t in ("film", "customer", "payment", "actor"):
    call("GET", f"/datasources/{pagila}/catalog?database=dg_pagila&schema=public&table={t}")
call("GET", f"/datasources/{probe}/catalog?database=dg_probe&schema={SCHEMA}&refresh=true")
for t in ("ods_film", "ods_customer"):
    call("GET", f"/datasources/{probe}/catalog?database=dg_probe&schema={SCHEMA}&table={t}")

print("建清洗规则…")
rules = {}
for name, kind, params in (
        ("影片标题去空格", "TRIM", {"mode": "BOTH"}),
        ("评级统一大写", "CHANGE_CASE", {"mode": "UPPER"}),
        ("邮箱脱敏", "MASK", {"mode": "PARTIAL", "keepPrefix": "2", "keepSuffix": "8"})):
    res = call("POST", "/rules", {"name": name, "kind": kind, "params": params})
    if res.get("success"): rules[kind] = res["data"]["id"]

print("建任务…")
jobs = []
def job(name, jtype, cfg, timeout=180000, desc=""):
    res = call("POST", "/jobs", {"name": name, "jobType": jtype, "description": desc,
                                 "config": cfg, "timeoutMs": timeout})
    if not res.get("success"):
        print("  跳过", name, res.get("message")); return None
    j = res["data"]["id"]
    c = call("POST", f"/jobs/{j}/compile")["data"]
    if c["succeeded"]:
        call("POST", f"/jobs/{j}/publish")
    else:
        print("  编译未过", name, c["summary"])
    jobs.append((name, j, c["succeeded"]))
    return j

sync_film = job("影片主数据入仓", "OFFLINE_SYNC", {
    "sourceDataSourceId": pagila, "sourceDatabase": "dg_pagila", "sourceSchema": "public",
    "sourceTable": "film", "targetDataSourceId": probe, "targetDatabase": "dg_probe",
    "targetSchema": SCHEMA, "targetTable": "ods_film",
    "fieldMappings": {"film_id": "film_id", "title": "title", "rating": "rating",
                      "rental_rate": "rental_rate"},
    "fieldRules": {k: [v] for k, v in (("title", rules.get("TRIM")), ("rating", rules.get("CHANGE_CASE"))) if v},
    "writeMode": "OVERWRITE", "batchSize": 500}, desc="每日全量刷新影片主数据,标题去空格、评级统一大写")

sync_cust = job("客户信息入仓(邮箱脱敏)", "OFFLINE_SYNC", {
    "sourceDataSourceId": pagila, "sourceDatabase": "dg_pagila", "sourceSchema": "public",
    "sourceTable": "customer", "targetDataSourceId": probe, "targetDatabase": "dg_probe",
    "targetSchema": SCHEMA, "targetTable": "ods_customer",
    "fieldMappings": {"customer_id": "customer_id", "first_name": "first_name",
                      "last_name": "last_name", "email": "email"},
    "fieldRules": {"email": [rules["MASK"]]} if rules.get("MASK") else {},
    "writeMode": "OVERWRITE", "batchSize": 500}, desc="客户信息入仓,邮箱按前2后8保留脱敏")

if mysql:
    call("GET", f"/datasources/{mysql}/catalog?database=dg_chinook&refresh=true")
    job("信创迁移:MySQL 业务库 → 数仓", "DB_MIGRATION", {
        "sourceDataSourceId": mysql, "sourceDatabase": "dg_chinook",
        "targetDataSourceId": probe, "targetDatabase": "dg_probe", "targetSchema": "public",
        "tables": [], "createTable": True, "lowercaseNames": True,
        "writeMode": "OVERWRITE", "batchSize": 1000}, 600000,
        desc="整库迁移,目标表名与列名统一转小写")

if health:
    job("健康数据文件解析入库", "FILE_PARSE", {
        "sourceDataSourceId": health, "path": "/health", "filePattern": "patients_cn_gbk.csv",
        "format": "CSV", "charset": "GBK", "hasHeader": True,
        "targetDataSourceId": probe, "targetDatabase": "dg_probe",
        "targetSchema": SCHEMA, "targetTable": "ods_customer",
        "fieldMappings": {"姓名": "first_name"}, "writeMode": "APPEND"},
        desc="从交换目录读 GBK 编码的中文 CSV")

print("跑几次任务,产生执行记录…")
def run_and_wait(j, secs=90):
    e = call("POST", f"/jobs/{j}/run")
    if not e.get("success"): return None
    eid = e["data"]["id"]
    for _ in range(secs * 2):
        d = call("GET", f"/executions/{eid}")["data"]["execution"]
        if d["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return d
        time.sleep(0.5)
    return None

for j in (sync_film, sync_cust, sync_film):
    if j:
        d = run_and_wait(j)
        if d: print(f"  {d['jobName']}: {d['status']} 读{d.get('rowsRead')}写{d.get('rowsWritten')}")

print("建告警渠道与规则…")
ch = call("POST", "/governance/channels", {
    "name": "运维值班群", "type": "WEBHOOK", "config": {"url": "http://127.0.0.1:9/hook"},
    "enabled": True})
chid = ch["data"]["id"] if ch.get("success") else None
for name, trig, desc in (
        ("任务失败立即通知", "EXECUTION_FAILED", "任何任务失败都通知值班群"),
        ("空跑告警", "EXECUTION_EMPTY", "执行成功但一行都没写入 —— 最容易被忽略的故障"),
        ("执行器过载", "DISPATCH_REJECTED", "下发被拒,说明并发已达上限")):
    body = {"name": name, "triggerType": trig, "description": desc,
            "suppressWindowSeconds": 600, "enabled": True}
    if chid: body["channelIds"] = [chid]
    call("POST", "/governance/alert-rules", body)

print("\n完成。当前:")
for n, _, ok in jobs:
    print(f"  任务 {n} {'✓' if ok else '✗编译未过'}")
