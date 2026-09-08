#!/usr/bin/env python3
"""P1 完成判据的端到端验证。全程走真实 HTTP 接口与真实 PostgreSQL。"""
import json
import os
import sys
import urllib.request
import urllib.error

# 连接参数从环境变量读,不硬编码 —— 这个脚本会进版本库
import time

API = os.environ.get("DG_VERIFY_API", "http://127.0.0.1:8080") + "/api/v1"
# 每次运行加一个后缀,让脚本可反复执行而不撞名称唯一约束
RUN = time.strftime("%H%M%S")
ADMIN_USER = os.environ.get("DG_VERIFY_USER", "admin")
ADMIN_PASSWORD = os.environ.get("DG_ADMIN_PASSWORD")
PG_HOST = os.environ.get("DG_TEST_PG_HOST", "127.0.0.1")
PG_PORT = int(os.environ.get("DG_TEST_PG_PORT", "55432"))
PG_USER = os.environ.get("DG_TEST_PG_USER", "postgres")
PG_PASSWORD = os.environ.get("DG_TEST_PG_PASSWORD", "postgres")

if not ADMIN_PASSWORD:
    sys.exit("请设置 DG_ADMIN_PASSWORD(应用启动时用的那个管理员口令)")
token = None
workspace = None
failures = []


def call(method, path, body=None, ws=None, expect=200, raw=False):
    url = API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    use_ws = ws if ws is not None else workspace
    if use_ws:
        req.add_header("X-Workspace-Id", use_ws)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read())
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read())
        status = e.code
    if raw:
        return status, payload
    return payload


def check(label, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label}" + (f"  — {detail}" if detail else ""))
    if not ok:
        failures.append(label)
    return ok


print("=" * 74)
print(" P1 完成判据验证 — 能创建5类数据源 / 通过连通性测试 / 浏览库表结构 / 按空间隔离")
print("=" * 74)

# ── 登录 ──────────────────────────────────────────────────────────────
res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
# 有多个可访问空间时后端不自动选中(currentWorkspaceId 缺省),
# 真实客户端此时会让用户选;脚本取第一个即可
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")
print(f"菜单节点 {len(res['data']['menus'])} 个,权限码 {len(res['data']['permissions'])} 个")

# ── 判据 1:能创建 5 类数据源 ────────────────────────────────────────
print("\n【判据1】能创建 5 类数据源")
types = call("GET", "/datasource-types")["data"]
print(f"  平台暴露 {len(types)} 种类型:")
for t in types:
    c = t["capabilities"]
    tree = "三级树" if c["hasSchemaLevel"] else ("目录树" if c["canBrowseFiles"] else
                                              ("两级树" if c["canBrowseCatalog"] else "无结构浏览"))
    name = t["displayName"]
    print("    %-11s %-12s %-11s :%-6s %s" % (t["type"], name, t["family"], t["defaultPort"], tree))

check("暴露的类型数 >= 5", len(types) >= 5, f"{len(types)} 种")
type_names = {t["type"] for t in types}
check("五类关系型齐备(含达梦DM8)",
      {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "DAMENG"} <= type_names)
check("MPP / 文件 / 接口三族齐备",
      {"DORIS", "STARROCKS", "FTP", "SFTP", "REST_API"} <= type_names)

# 逐类创建。只有 PostgreSQL 有真实服务端,其余验证的是"能建、能存、能查"
specs = [
    ("PG-真实探测目标-" + RUN, "POSTGRESQL", {"host": PG_HOST, "port": PG_PORT,
                                    "databaseName": "dg_probe", "username": PG_USER},
     {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD}),
    ("MySQL-订单库-" + RUN, "MYSQL", {"host": "10.10.0.11", "port": 3306,
                             "databaseName": "orders", "username": "app"},
     {"authType": "PASSWORD", "username": "app", "secret": "mysql-pass"}),
    ("达梦-主数据-" + RUN, "DAMENG", {"host": "10.10.0.12", "port": 5236,
                            "databaseName": "MDM", "username": "SYSDBA"},
     {"authType": "PASSWORD", "username": "SYSDBA", "secret": "dm-pass"}),
    ("Doris-分析库-" + RUN, "DORIS", {"host": "10.10.0.13", "port": 9030,
                             "databaseName": "dw", "username": "analyst"},
     {"authType": "PASSWORD", "username": "analyst", "secret": "doris-pass"}),
    ("SFTP-数据交换-" + RUN, "SFTP", {"host": "10.10.0.14", "port": 22,
                             "databaseName": "/data/exchange", "username": "ftpuser"},
     {"authType": "PASSWORD", "username": "ftpuser", "secret": "sftp-pass"}),
    ("健康平台接口-" + RUN, "REST_API", {"baseUrl": "https://health.example.gov.cn/api"},
     {"authType": "TOKEN", "secret": "token-abc"}),
]

created = {}
for name, dstype, conn, secret in specs:
    body = {"name": name, "type": dstype, "inlineSecret": secret}
    body.update(conn)
    res = call("POST", "/datasources", body)
    ok = res.get("success") and res["data"]["status"] == "DRAFT"
    created[dstype] = res["data"]["id"] if ok else None
    check(f"创建 {dstype:<11} 「{name}」", ok,
          res["data"]["status"] if ok else res.get("message"))

# ── 凭据边界 ──────────────────────────────────────────────────────────
print("\n【凭据边界】数据源响应不得含口令")
pg_id = created["POSTGRESQL"]
detail = call("GET", f"/datasources/{pg_id}")["data"]
body_text = json.dumps(detail, ensure_ascii=False)
check("响应体不含明文口令", PG_PASSWORD not in body_text.replace('"username": "%s"' % PG_USER, ""),
      "只有 username,无 password")
check("响应体有 credentialId(不可解密的引用)", detail.get("credentialId") is not None,
      detail.get("credentialId"))
check("响应体没有任何 password/secret 字段",
      not any(k.lower() in ("password", "secret", "passwordenc") for k in detail.keys()))

creds = call("GET", "/credentials")["data"]
cred_text = json.dumps(creds, ensure_ascii=False)
check("凭据列表不含明文也不含密文",
      PG_PASSWORD not in cred_text and "v1:" not in cred_text, f"{len(creds)} 条凭据")

# ── 判据 2:通过连通性测试 ────────────────────────────────────────────
print("\n【判据2】通过连通性测试(打真实 PostgreSQL)")
res = call("POST", f"/datasources/{pg_id}/test")
result = res["data"]
check("连通性测试成功", result["success"] is True, result.get("message"))
check("返回真实服务端版本", "PostgreSQL" in (result.get("serverVersion") or ""),
      result.get("serverVersion"))
after = call("GET", f"/datasources/{pg_id}")["data"]
check("状态迁移 DRAFT → AVAILABLE", after["status"] == "AVAILABLE", after["status"])
check("写回最近测试结论", after["lastTestSuccess"] is True,
      f"耗时 {after['lastTestLatencyMs']}ms")

print("\n  失败路径:不可达的 MySQL 应回到 DRAFT 而非 UNREACHABLE")
mysql_id = created["MYSQL"]
res = call("POST", f"/datasources/{mysql_id}/test")
check("测试返回失败结果而非抛异常", res["success"] and res["data"]["success"] is False,
      res["data"].get("errorCode"))
after_fail = call("GET", f"/datasources/{mysql_id}")["data"]
check("手工测试失败 → DRAFT(配置问题,不是环境问题)",
      after_fail["status"] == "DRAFT", after_fail["status"])

# ── 判据 3:浏览库表结构 ──────────────────────────────────────────────
print("\n【判据3】浏览库表结构(逐层下钻真实 PostgreSQL)")
page = call("GET", f"/datasources/{pg_id}/catalog")["data"]
dbs = [d["name"] for d in page["databases"]]
check("第1层 列出库", len(dbs) > 0, f"{len(dbs)} 个: {', '.join(dbs[:4])}")

page = call("GET", f"/datasources/{pg_id}/catalog?database=dg_probe")["data"]
schemas = [s["name"] for s in page["schemas"]]
check("第2层 列出模式(系统模式已过滤)",
      len(schemas) > 0 and "pg_catalog" not in schemas, ", ".join(schemas[:5]))

target_schema = "dg_probe_schema" if "dg_probe_schema" in schemas else schemas[0]
page = call("GET", f"/datasources/{pg_id}/catalog?database=dg_probe&schema={target_schema}")["data"]
tables = [t["name"] for t in page["tables"]]
check("第3层 列出表", len(tables) > 0, f"{len(tables)} 张: {', '.join(tables[:4])}")

if tables:
    tbl = "probe_all_types" if "probe_all_types" in tables else tables[0]
    page = call("GET",
                f"/datasources/{pg_id}/catalog?database=dg_probe&schema={target_schema}&table={tbl}")["data"]
    cols = page["columns"]
    check("第4层 列出字段", len(cols) > 0, f"{len(cols)} 个字段")
    if cols:
        print("       字段名          原始类型         规范类型        主键 可空")
        for c in cols[:8]:
            print("       %-15s %-16s %-15s %-4s %s" % (
                c["name"], c["rawType"], c["canonicalType"],
                "是" if c["primaryKey"] else "", "是" if c["nullable"] else "否"))
        by_name = {c["name"]: c for c in cols}
        if "ts_zoned" in by_name and "ts_plain" in by_name:
            check("带时区/不带时区的时间戳被区分",
                  by_name["ts_zoned"]["canonicalType"] == "TIMESTAMP_TZ"
                  and by_name["ts_plain"]["canonicalType"] == "TIMESTAMP",
                  "TIMESTAMP_TZ vs TIMESTAMP")

print("\n  未通过验证的数据源不允许浏览结构:")
status, res = call("GET", f"/datasources/{mysql_id}/catalog", raw=True)
check("DRAFT 状态浏览被拒", status == 409 and res.get("code") == "MTD_DATASOURCE_NOT_ACTIVE",
      f"HTTP {status} {res.get('code')}")

# ── 判据 4:按空间隔离 ────────────────────────────────────────────────
print("\n【判据4】按空间隔离")
res = call("POST", "/workspaces", {"code": "tenant-verify-" + RUN, "name": "隔离验证空间",
                                   "description": "隔离验证用"})
ws_b = res["data"]["id"]
check("创建第二个空间", res.get("success"), f"{res['data']['code']} / {ws_b}")

count_a = call("GET", "/datasources?page=1&size=50")["data"]["total"]
count_b = call("GET", "/datasources?page=1&size=50", ws=ws_b)["data"]["total"]
check("A 空间能看到自己的数据源", count_a >= 6, f"{count_a} 个")
check("B 空间看不到 A 空间的数据源", count_b == 0, f"{count_b} 个")

status, res = call("GET", f"/datasources/{pg_id}", ws=ws_b, raw=True)
check("跨空间按 ID 直取被拒(且不泄露存在性)",
      status == 404 and res.get("code") == "MTD_DATASOURCE_NOT_FOUND",
      f"HTTP {status} {res.get('code')}")

status, res = call("GET", "/datasources", ws="ws_does_not_exist", raw=True)
check("伪造空间 ID 被拒", status in (403, 404),
      f"HTTP {status} {res.get('code')}")

# ── 汇总 ──────────────────────────────────────────────────────────────
print("\n" + "=" * 74)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P1 四条完成判据全部通过")
print("=" * 74)
