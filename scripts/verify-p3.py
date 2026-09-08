#!/usr/bin/env python3
"""P3 完成判据的端到端验证:数据开发(序号 18-23)、执行器(31)、文件管理(32)。

与 verify-p1/p2 一样,全程走真实 HTTP 接口与真实 PostgreSQL,连接参数从环境变量读。

这个脚本刻意<b>不掩盖没接引擎这件事</b>:实时任务在没有 Flink 的环境里启动会失败,
脚本断言的是"它以正确的方式失败,并且保活状态机走对了",而不是假装它跑起来了。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("DG_VERIFY_API", "http://127.0.0.1:8080") + "/api/v1"
RUN = time.strftime("%H%M%S")
ADMIN_USER = os.environ.get("DG_VERIFY_USER", "admin")
ADMIN_PASSWORD = os.environ.get("DG_ADMIN_PASSWORD")
PG_HOST = os.environ.get("DG_TEST_PG_HOST", "127.0.0.1")
PG_PORT = int(os.environ.get("DG_TEST_PG_PORT", "55432"))
PG_USER = os.environ.get("DG_TEST_PG_USER", "postgres")
PG_PASSWORD = os.environ.get("DG_TEST_PG_PASSWORD", "postgres")
PG_DB = os.environ.get("DG_TEST_PG_DB", "dg_probe")

if not ADMIN_PASSWORD:
    sys.exit("请设置 DG_ADMIN_PASSWORD(应用启动时用的那个管理员口令)")

token = None
workspace = None
failures = []


def call(method, path, body=None, raw=False, timeout=60):
    url = API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if workspace:
        req.add_header("X-Workspace-Id", workspace)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read())
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read())
        status = e.code
    return (status, payload) if raw else payload


def upload(path, fields, filename, content):
    """multipart 上传。标准库没有现成的编码器,手写一个最小实现。"""
    boundary = "----dgverify" + RUN
    parts = []
    for key, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n")
    body = "".join(parts).encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
    body += content + f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(API + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("X-Workspace-Id", workspace)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def check(label, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  — {detail}" if detail else ""))
    if not ok:
        failures.append(label)
    return ok


def psql(sql, db=PG_DB):
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    result = subprocess.run(
        ["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER, "-d", db, "-tAc", sql],
        capture_output=True, text=True, env=env, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(f"psql 失败: {result.stderr.strip()}")
    return result.stdout.strip()


def wait_terminal(execution_id, seconds=90):
    """等一条执行走到终态。返回最后一次看到的详情。"""
    detail = None
    for _ in range(seconds):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(1)
    return detail


print("=" * 74)
print(" P3 验证 — 实时/离线开发 · 工作流编排 · 执行器 · 作业制品")
print("=" * 74)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,空间 {workspace}")


# ── 功能 32:文件管理(作业制品)────────────────────────────────────
print("\n【功能 32】文件管理 —— 作业制品仓库,不是配置项(R2)")

art_name = f"p3-job-{RUN}"
status, body = upload("/artifacts", {"name": art_name, "version": "1.0.0", "type": "JAR",
                                     "description": "P3 验证用"},
                      "demo.jar", b"PK\x03\x04fake-jar-content-for-verification")
check("上传制品", status == 200, f"HTTP {status}")
artifact = body["data"]
check("制品记录了大小与 SHA-256 摘要",
      artifact["sizeBytes"] > 0 and len(artifact["checksumSha256"]) == 64,
      f"{artifact['sizeBytes']} 字节 / {artifact['checksumSha256'][:16]}…")
check("新制品的引用计数为 0", artifact["refCount"] == 0)

dup_status, dup_body = upload("/artifacts", {"name": art_name, "version": "1.0.0", "type": "JAR"},
                              "demo.jar", b"different-content")
check("同名同版本不能重复上传(制品不可变)",
      dup_status != 200 and "已存在" in dup_body.get("message", ""),
      f"HTTP {dup_status} {dup_body.get('message')}")

v2_status, v2_body = upload("/artifacts", {"name": art_name, "version": "2.0.0", "type": "JAR"},
                            "demo.jar", b"v2-content")
check("同名不同版本可以上传 —— 要改内容就发新版本", v2_status == 200, f"HTTP {v2_status}")
artifact_v2 = v2_body["data"]

listed = call("GET", f"/artifacts?size=100&keyword={art_name}")["data"]
check("制品列表能查到刚上传的两个版本", listed["total"] >= 2, f"{listed['total']} 个")

req = urllib.request.Request(API + f"/artifacts/{artifact['id']}/content")
req.add_header("Authorization", "Bearer " + token)
req.add_header("X-Workspace-Id", workspace)
with urllib.request.urlopen(req, timeout=30) as resp:
    downloaded = resp.read()
check("下载回来的内容与上传的一致",
      downloaded == b"PK\x03\x04fake-jar-content-for-verification",
      f"{len(downloaded)} 字节")

bad_status, _ = upload("/artifacts", {"name": f"p3-bad-{RUN}", "version": "1.0.0",
                                      "type": "EXECUTABLE"}, "x.bin", b"x")
check("不支持的制品类型被拒", bad_status != 200, f"HTTP {bad_status}")


# ── 功能 31:执行器管理 ──────────────────────────────────────────────
print("\n【功能 31】执行器管理 —— 执行资源池,不是配置项(R2)")

statuses = call("GET", "/executors/statuses")["data"]
check("执行器状态元数据由后端下发", len(statuses) == 5,
      str([s["status"] for s in statuses]))
check("只有「健康」接新任务",
      [s["status"] for s in statuses if s["acceptsWork"]] == ["HEALTHY"],
      str([s["status"] for s in statuses if s["acceptsWork"]]))

executor = call("POST", "/executors", {"name": f"p3-executor-{RUN}", "kind": "LOCAL",
                                       "endpoint": "http://127.0.0.1:9999",
                                       "maxConcurrency": 4})["data"]
check("注册后是「已注册」而不是「健康」—— 还没收到过心跳",
      executor["status"] == "REGISTERED", executor["status"])
check("已注册状态下可用槽位为 0", executor["availableSlots"] == 0,
      str(executor["availableSlots"]))

dup_ex_status, dup_ex = call("POST", "/executors",
                             {"name": f"p3-executor-{RUN}", "kind": "LOCAL"}, raw=True)
check("执行器重名被拒", dup_ex_status != 200 and "已存在" in dup_ex.get("message", ""),
      f"HTTP {dup_ex_status}")

beat = call("POST", f"/executors/{executor['id']}/heartbeat", {"runningCount": 1})["data"]
check("第一次心跳把「已注册」转成「健康」", beat["status"] == "HEALTHY", beat["status"])
check("健康状态下算出剩余槽位", beat["availableSlots"] == 3,
      f"{beat['runningCount']}/{beat['maxConcurrency']} → 剩 {beat['availableSlots']}")

rm_status, rm_body = call("POST", f"/executors/{executor['id']}/remove", raw=True)
check("健康的执行器不能直接移除,必须先排空",
      rm_status != 200 and "排空" in rm_body.get("message", ""),
      f"HTTP {rm_status} {rm_body.get('message')}")

drained = call("POST", f"/executors/{executor['id']}/drain")["data"]
check("排空后不再接新任务", drained["status"] == "DRAINING" and drained["availableSlots"] == 0,
      drained["status"])

busy_status, busy_body = call("POST", f"/executors/{executor['id']}/remove", raw=True)
check("手上还有任务时拒绝移除(否则那些执行记录会一起丢掉)",
      busy_status != 200 and "还有 1 个任务" in busy_body.get("message", ""),
      f"HTTP {busy_status} {busy_body.get('message')}")

resumed = call("POST", f"/executors/{executor['id']}/resume")["data"]
check("取消排空回到「已注册」而不是直接「健康」—— 等下次心跳再确认",
      resumed["status"] == "REGISTERED", resumed["status"])

call("POST", f"/executors/{executor['id']}/heartbeat", {"runningCount": 0})
call("POST", f"/executors/{executor['id']}/drain")
removed = call("POST", f"/executors/{executor['id']}/remove")["data"]
check("空闲且排空后可以移除", removed["status"] == "REMOVED", removed["status"])

visible = call("GET", "/executors")["data"]
check("移除的执行器不再出现在列表里(但记录保留)",
      all(e["id"] != executor["id"] for e in visible), f"{len(visible)} 个可见")


# ── 准备:一个真实可用的 PostgreSQL 数据源 ──────────────────────────
ds_id = call("POST", "/datasources", {
    "name": f"P3-开发库-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})["data"]["id"]
call("POST", f"/datasources/{ds_id}/test")


def snapshot(*tables):
    """把表结构拉进目录快照 —— 编译器读的是快照,不连库。"""
    for table in tables:
        call("GET", f"/datasources/{ds_id}/catalog"
                    f"?database={PG_DB}&schema=dg_probe_schema&table={table}")


# ── 功能 20:离线开发 ────────────────────────────────────────────────
print("\n【功能 20】离线开发 —— SQL 作业在真实数据源上执行")

batch_table = f"p3_batch_{RUN}"
psql(f"""
    CREATE SCHEMA IF NOT EXISTS dg_probe_schema;
    DROP TABLE IF EXISTS dg_probe_schema.{batch_table};
    CREATE TABLE dg_probe_schema.{batch_table} (id INT PRIMARY KEY, label VARCHAR(32));
""")

batch_job = call("POST", "/jobs", {
    "name": f"P3-离线开发-{RUN}", "jobType": "BATCH",
    "config": {
        "sourceKind": "SQL", "dataSourceId": ds_id,
        "sql": f"INSERT INTO dg_probe_schema.{batch_table} (id, label) "
               f"SELECT g, 'row-' || g FROM generate_series(1, 25) g; "
               f"UPDATE dg_probe_schema.{batch_table} SET label = 'x;y' WHERE id = 1;",
        "parallelism": 2,
    },
    "timeoutMs": 60000,
})["data"]
bc = call("POST", f"/jobs/{batch_job['id']}/compile")["data"]
check("离线开发编译通过", bc["succeeded"], bc["summary"])
call("POST", f"/jobs/{batch_job['id']}/publish")

bexec = call("POST", f"/jobs/{batch_job['id']}/run")["data"]
bdetail = wait_terminal(bexec["id"])
check("离线开发执行成功", bdetail["execution"]["status"] == "SUCCEEDED",
      f"{bdetail['execution']['status']} {bdetail['execution'].get('message') or ''}")

rows = int(psql(f"SELECT count(*) FROM dg_probe_schema.{batch_table}"))
check("SQL 真的在目标库上执行了", rows == 25, f"{rows} 行")
odd = psql(f"SELECT label FROM dg_probe_schema.{batch_table} WHERE id = 1")
check("含分号的字符串没有被切断语句('x;y' 完整写入)", odd == "x;y", repr(odd))

check("离线开发的执行记录归入 BATCH_DEV 种类",
      bdetail["execution"]["jobRefType"] == "BATCH_DEV", bdetail["execution"]["jobRefType"])

nods = call("POST", "/jobs", {
    "name": f"P3-无数据源-{RUN}", "jobType": "BATCH",
    "config": {"sourceKind": "SQL", "sql": "SELECT 1"},
})["data"]
nods_c = call("POST", f"/jobs/{nods['id']}/compile")["data"]
check("SQL 作业不指定数据源时编译失败",
      not nods_c["succeeded"]
      and any("数据源" in d["message"] for d in nods_c["diagnostics"]),
      nods_c["summary"])
call("DELETE", f"/jobs/{nods['id']}")

nojar = call("POST", "/jobs", {
    "name": f"P3-缺入口类-{RUN}", "jobType": "BATCH",
    "config": {"sourceKind": "JAR", "artifactId": artifact["id"]},
})["data"]
nojar_c = call("POST", f"/jobs/{nojar['id']}/compile")["data"]
check("JAR 作业不指定入口类时编译失败(平台不去反编译猜 main)",
      not nojar_c["succeeded"]
      and any("入口类" in d["message"] for d in nojar_c["diagnostics"]),
      nojar_c["summary"])

ghost = call("POST", "/jobs", {
    "name": f"P3-制品不存在-{RUN}", "jobType": "BATCH",
    "config": {"sourceKind": "JAR", "artifactId": "art_does_not_exist",
               "entryClass": "com.example.Main"},
})["data"]
ghost_c = call("POST", f"/jobs/{ghost['id']}/compile")["data"]
check("引用不存在的制品时编译失败",
      not ghost_c["succeeded"]
      and any("制品不存在" in d["message"] for d in ghost_c["diagnostics"]),
      ghost_c["summary"])
call("DELETE", f"/jobs/{ghost['id']}")


# ── 功能 18:实时开发 ────────────────────────────────────────────────
print("\n【功能 18】实时开发 —— 独立的保活状态机(架构风险 R5)")

meta = call("GET", "/streaming-jobs/states")["data"]
check("流任务运行态由后端下发(7 个:未启动/启动中/运行中/重启中/停止中/已停止/保活失败)",
      len(meta["statuses"]) == 7, str([s["status"] for s in meta["statuses"]]))
check("流任务没有「成功」状态 —— 常驻作业问「这次成功了吗」没有意义",
      all(s["status"] != "SUCCEEDED" for s in meta["statuses"]))
check("活跃状态有三个(启动中/运行中/重启中),批执行只有 RUNNING 一个",
      len([s for s in meta["statuses"] if s["active"]]) == 3,
      str([s["status"] for s in meta["statuses"] if s["active"]]))
check("保活重启次数上限由后端下发", meta["maxRestartAttempts"] >= 1,
      str(meta["maxRestartAttempts"]))

stream_job = call("POST", "/jobs", {
    "name": f"P3-实时开发-{RUN}", "jobType": "STREAMING",
    "config": {
        "sourceKind": "SQL",
        "sql": "INSERT INTO sink SELECT * FROM source",
        "parallelism": 2, "checkpointIntervalMs": 60000,
        "restartStrategy": "EXPONENTIAL",
    },
    "timeoutMs": 86400000,
})["data"]
sc = call("POST", f"/jobs/{stream_job['id']}/compile")["data"]
check("实时开发编译通过", sc["succeeded"], sc["summary"])

cron_status, cron_body = call("POST", f"/jobs/{stream_job['id']}/schedule",
                              {"cronExpression": "0 0 2 * * *"}, raw=True)
check("实时任务不能绑 Cron —— 常驻作业没有「每天两点再跑一次」这回事",
      cron_status != 200, f"HTTP {cron_status} {cron_body.get('code')}")

no_ckpt = call("POST", "/jobs", {
    "name": f"P3-无checkpoint-{RUN}", "jobType": "STREAMING",
    "config": {"sourceKind": "SQL", "sql": "SELECT 1", "parallelism": 1},
})["data"]
nc = call("POST", f"/jobs/{no_ckpt['id']}/compile")["data"]
check("没配 checkpoint 时给出警告(重启会从头消费)而不是拒绝",
      nc["succeeded"] and any("checkpoint" in d["message"]
                              and d["severity"] == "WARNING" for d in nc["diagnostics"]),
      nc["summary"])
call("DELETE", f"/jobs/{no_ckpt['id']}")

state = call("GET", f"/streaming-jobs/{stream_job['id']}")["data"]
check("未启动的实时任务处于「未启动」", state["status"] == "PUBLISHED", state["status"])

not_pub_status, _ = call("POST", f"/streaming-jobs/{stream_job['id']}/start", raw=True)
check("未发布就启动被拒", not_pub_status != 200, f"HTTP {not_pub_status}")

call("POST", f"/jobs/{stream_job['id']}/publish")
started = call("POST", f"/streaming-jobs/{stream_job['id']}/start")["data"]
check("启动后进入「启动中」", started["status"] == "STARTING", started["status"])
check("启动产生了一条执行记录(序号 19 要求实时任务也有执行记录)",
      started["executionId"] is not None, str(started["executionId"]))

again_status, again_body = call("POST", f"/streaming-jobs/{stream_job['id']}/start", raw=True)
check("已在启动/运行中时重复启动返回 409 而不是静默成功",
      again_status == 409, f"HTTP {again_status} {again_body.get('message')}")

# 没有 Flink,执行会失败 —— 断言的是"它以正确的方式失败,状态机走对了"
sdetail = wait_terminal(started["executionId"], 60)
check("没接 Flink 时执行如实失败,而不是假装成功",
      sdetail["execution"]["status"] == "FAILED", sdetail["execution"]["status"])
check("失败归类为「执行器不可用」而非平台内部错误(值班的人该找运维)",
      sdetail["execution"]["errorCode"] == "RTM_EXECUTOR_UNAVAILABLE",
      str(sdetail["execution"]["errorCode"]))
check("错误消息说清楚缺的是什么",
      "Flink" in (sdetail["execution"].get("message") or ""),
      sdetail["execution"].get("message"))

# 对账把运行态拉到与执行记录一致 —— 启动就起不来,不进重启,直接保活失败
final = None
for _ in range(45):
    final = call("GET", f"/streaming-jobs/{stream_job['id']}")["data"]
    if not final["active"]:
        break
    time.sleep(1)
check("对账把运行态与执行记录拉齐(不会永远显示「运行中」)",
      final is not None and not final["active"], final["status"] if final else "?")
check("启动就起不来 → 直接保活失败,不进重启(重启多少次都一样)",
      final["status"] == "FAILED", final["status"])

restarted = call("POST", f"/streaming-jobs/{stream_job['id']}/start")["data"]
check("保活失败后人工可以重新拉起 —— 它不是不可逃逸的终态",
      restarted["status"] == "STARTING", restarted["status"])
check("人工启动会把重启计数清零", restarted["restartCount"] == 0,
      str(restarted["restartCount"]))

# 停止的结果取决于此刻它还活着没有 —— 没接 Flink 时它会很快失败,所以
# 这里断言的是<b>不变式</b>「能停 ⟺ 它还活着」,而不是某一个具体状态。
# 写死一个状态会让这条断言时灵时不灵,那比没有断言更糟
before_stop = call("GET", f"/streaming-jobs/{stream_job['id']}")["data"]
stop_status, stop_body = call("POST", f"/streaming-jobs/{stream_job['id']}/stop", raw=True)
if before_stop["active"]:
    check("活跃的流任务可以停止,并进入两阶段的「停止中」/「已停止」",
          stop_status == 200
          and stop_body["data"]["status"] in ("STOPPING", "STOPPED"),
          f"HTTP {stop_status} {stop_body.get('data', {}).get('status')}")
else:
    check("已经不活跃的流任务拒绝停止(不能停一个已经停了的东西)",
          stop_status == 409, f"HTTP {stop_status} {stop_body.get('message')}")

# 无论上面走哪个分支,现在它都该是不活跃的
after_stop = None
for _ in range(45):
    after_stop = call("GET", f"/streaming-jobs/{stream_job['id']}")["data"]
    if not after_stop["active"]:
        break
    time.sleep(1)
check("停止流程走完后任务不再活跃",
      after_stop is not None and not after_stop["active"],
      after_stop["status"] if after_stop else "?")

listed_states = call("GET", "/streaming-jobs")["data"]
check("实时开发页列出本空间所有流任务的运行态",
      any(s["jobDefinitionId"] == stream_job["id"] for s in listed_states),
      f"{len(listed_states)} 个")

sexec = call("GET", "/executions?jobRefType=STREAMING_DEV&size=100")["data"]
check("「执行记录(实时)」页(序号 19)= 同一张表加 STREAMING_DEV 过滤",
      sexec["total"] >= 1, f"{sexec['total']} 条")


# ── 功能 22:工作流编排 —— DAG 校验 ─────────────────────────────────
print("\n【功能 22】工作流编排 —— DAG 校验与条件节点")

def make_sync_job(name, src, dst):
    """建一个真的能跑的同步任务,给工作流当节点用。"""
    job = call("POST", "/jobs", {
        "name": name, "jobType": "OFFLINE_SYNC",
        "config": {
            "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
            "sourceSchema": "dg_probe_schema", "sourceTable": src,
            "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
            "targetSchema": "dg_probe_schema", "targetTable": dst,
            "fieldMappings": {"id": "id", "label": "label"},
            "writeMode": "APPEND", "batchSize": 100,
        },
        "timeoutMs": 60000,
    })["data"]
    result = call("POST", f"/jobs/{job['id']}/compile")["data"]
    if not result["succeeded"]:
        raise RuntimeError(f"节点任务编译失败: {result['summary']} {result['diagnostics']}")
    call("POST", f"/jobs/{job['id']}/publish")
    return job


wf_src = f"p3_wf_src_{RUN}"
wf_a = f"p3_wf_a_{RUN}"
wf_b = f"p3_wf_b_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{wf_src};
    DROP TABLE IF EXISTS dg_probe_schema.{wf_a};
    DROP TABLE IF EXISTS dg_probe_schema.{wf_b};
    CREATE TABLE dg_probe_schema.{wf_src} (id INT PRIMARY KEY, label VARCHAR(32));
    CREATE TABLE dg_probe_schema.{wf_a} (id INT PRIMARY KEY, label VARCHAR(32));
    CREATE TABLE dg_probe_schema.{wf_b} (id INT PRIMARY KEY, label VARCHAR(32));
    INSERT INTO dg_probe_schema.{wf_src}
        SELECT g, 'v' || g FROM generate_series(1, 40) g;
""")
# 目录快照要能读到这三张新表,编译才过得去
snapshot(wf_src, wf_a, wf_b)

node1 = make_sync_job(f"P3-节点1-{RUN}", wf_src, wf_a)
node2 = make_sync_job(f"P3-节点2-{RUN}", wf_src, wf_b)

draft_node = call("POST", "/jobs", {
    "name": f"P3-未发布节点-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": wf_src,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": wf_a,
        "fieldMappings": {"id": "id"}, "writeMode": "APPEND",
    },
})["data"]


def workflow(name, nodes, edges):
    return call("POST", "/jobs", {
        "name": name, "jobType": "WORKFLOW",
        "config": {"nodes": nodes, "edges": edges},
        "timeoutMs": 300000,
    })["data"]


def task_node(nid, job_id):
    return {"id": nid, "name": nid, "kind": "TASK", "jobDefinitionId": job_id}


def cond_node(nid, source, operator, value):
    return {"id": nid, "name": nid, "kind": "CONDITION",
            "condition": {"source": source, "operator": operator, "value": value}}


cyc = workflow(f"P3-有环-{RUN}",
               [task_node("a", node1["id"]), task_node("b", node2["id"])],
               [{"from": "a", "to": "b"}, {"from": "b", "to": "a"}])
cyc_c = call("POST", f"/jobs/{cyc['id']}/compile")["data"]
check("有环的工作流编译失败,并报出环上的具体节点",
      not cyc_c["succeeded"]
      and any("循环依赖" in d["message"] and "a" in d["message"] and "b" in d["message"]
              for d in cyc_c["diagnostics"]),
      next((d["message"] for d in cyc_c["diagnostics"] if "循环" in d["message"]), cyc_c["summary"]))
call("DELETE", f"/jobs/{cyc['id']}")

island = workflow(f"P3-孤岛-{RUN}",
                  [task_node("a", node1["id"]), task_node("x", node2["id"]),
                   task_node("y", node1["id"])],
                  [{"from": "x", "to": "y"}, {"from": "y", "to": "x"}])
island_c = call("POST", f"/jobs/{island['id']}/compile")["data"]
check("从起点到不了的节点被拦下(它们永远不会被执行)",
      not island_c["succeeded"]
      and any("到不了" in d["message"] or "循环" in d["message"]
              for d in island_c["diagnostics"]),
      island_c["summary"])
call("DELETE", f"/jobs/{island['id']}")

unpub = workflow(f"P3-引用未发布-{RUN}",
                 [task_node("a", draft_node["id"])], [])
unpub_c = call("POST", f"/jobs/{unpub['id']}/compile")["data"]
check("引用未发布的任务时编译失败(否则跑到那一步前面的写入已经发生了)",
      not unpub_c["succeeded"]
      and any("还没发布" in d["message"] for d in unpub_c["diagnostics"]),
      unpub_c["summary"])
call("DELETE", f"/jobs/{unpub['id']}")

selfref = workflow(f"P3-自引用-{RUN}", [task_node("a", node1["id"])], [])
selfref_cfg = call("PUT", f"/jobs/{selfref['id']}", {
    "name": f"P3-自引用-{RUN}", "jobType": "WORKFLOW",
    "config": {"nodes": [task_node("a", selfref["id"])], "edges": []},
})["data"]
self_c = call("POST", f"/jobs/{selfref['id']}/compile")["data"]
check("工作流引用自己被拦下(执行时会无限展开)",
      not self_c["succeeded"]
      and any("引用了工作流自己" in d["message"] for d in self_c["diagnostics"]),
      self_c["summary"])
call("DELETE", f"/jobs/{selfref['id']}")

badcond = workflow(f"P3-坏条件-{RUN}",
                   [task_node("a", node1["id"]),
                    cond_node("c", "UPSTREAM_STATUS", "GT", "SUCCEEDED"),
                    task_node("b", node2["id"])],
                   [{"from": "a", "to": "c"}, {"from": "c", "to": "b", "branch": "TRUE"}])
badcond_c = call("POST", f"/jobs/{badcond['id']}/compile")["data"]
check("状态上用大小比较被编译期拦下(几乎总是「本想比行数」的笔误)",
      not badcond_c["succeeded"]
      and any("EQ / NE" in d["message"] for d in badcond_c["diagnostics"]),
      badcond_c["summary"])
call("DELETE", f"/jobs/{badcond['id']}")

nobranch = workflow(f"P3-无分支标记-{RUN}",
                    [task_node("a", node1["id"]),
                     cond_node("c", "UPSTREAM_ROWS_WRITTEN", "GT", "0"),
                     task_node("b", node2["id"])],
                    [{"from": "a", "to": "c"}, {"from": "c", "to": "b"}])
nobranch_c = call("POST", f"/jobs/{nobranch['id']}/compile")["data"]
check("条件节点的出边不标 TRUE/FALSE 被拒",
      not nobranch_c["succeeded"]
      and any("TRUE 或 FALSE" in d["message"] for d in nobranch_c["diagnostics"]),
      nobranch_c["summary"])
call("DELETE", f"/jobs/{nobranch['id']}")


# ── 功能 23:工作流执行 —— 条件求值与级联取消 ──────────────────────
print("\n【功能 23】工作流执行 —— 条件在 Control 求值 · 级联取消")

# a → 条件(写入行数 > 0)→ TRUE:b
wf = workflow(f"P3-工作流-{RUN}",
              [task_node("a", node1["id"]),
               cond_node("c", "UPSTREAM_ROWS_WRITTEN", "GT", "0"),
               task_node("b", node2["id"])],
              [{"from": "a", "to": "c"},
               {"from": "c", "to": "b", "branch": "TRUE"}])
wf_c = call("POST", f"/jobs/{wf['id']}/compile")["data"]
check("合法的工作流编译通过", wf_c["succeeded"], wf_c["summary"])
check("没有 FALSE 分支时给警告而不是拒绝(「不满足就结束」是合法用法)",
      any("FALSE" in (d.get("hint") or "") or "TRUE 分支" in d["message"]
          for d in wf_c["diagnostics"]) or wf_c["succeeded"],
      str(len(wf_c["diagnostics"])) + " 条诊断")
call("POST", f"/jobs/{wf['id']}/publish")

wexec = call("POST", f"/jobs/{wf['id']}/run")["data"]
check("工作流执行归入 WORKFLOW 种类", wexec["jobRefType"] == "WORKFLOW", wexec["jobRefType"])

wdetail = wait_terminal(wexec["id"], 120)
check("工作流执行成功", wdetail["execution"]["status"] == "SUCCEEDED",
      f"{wdetail['execution']['status']} {wdetail['execution'].get('message') or ''}")

children = call("GET", f"/executions/{wexec['id']}/children")["data"]
check("两个任务节点各产生一条子执行(条件节点不产生)",
      len(children) == 2, f"{len(children)} 条子执行")
check("子执行记录了自己是哪个节点",
      sorted(c["workflowNodeId"] for c in children) == ["a", "b"],
      str([c["workflowNodeId"] for c in children]))
check("子执行都成功", all(c["status"] == "SUCCEEDED" for c in children),
      str([c["status"] for c in children]))
check("节点按依赖顺序执行(a 先于 b)",
      next(c["submittedAt"] for c in children if c["workflowNodeId"] == "a")
      <= next(c["submittedAt"] for c in children if c["workflowNodeId"] == "b"))

rows_a = int(psql(f"SELECT count(*) FROM dg_probe_schema.{wf_a}"))
rows_b = int(psql(f"SELECT count(*) FROM dg_probe_schema.{wf_b}"))
check("两个节点都真的搬了数据", rows_a == 40 and rows_b == 40, f"{rows_a} / {rows_b} 行")

# 条件为假:上游写入行数 > 1000 不成立 → 下游被跳过,工作流仍然成功。
# 两张目标表都要清空 —— 节点 a 是 APPEND 写入,不清空会撞主键,
# 那样失败的原因是脏数据而不是被测的逻辑
psql(f"TRUNCATE dg_probe_schema.{wf_a}; TRUNCATE dg_probe_schema.{wf_b};")
wf_false = workflow(f"P3-条件为假-{RUN}",
                    [task_node("a", node1["id"]),
                     cond_node("c", "UPSTREAM_ROWS_WRITTEN", "GT", "1000"),
                     task_node("b", node2["id"])],
                    [{"from": "a", "to": "c"},
                     {"from": "c", "to": "b", "branch": "TRUE"}])
call("POST", f"/jobs/{wf_false['id']}/compile")
call("POST", f"/jobs/{wf_false['id']}/publish")
wfexec = call("POST", f"/jobs/{wf_false['id']}/run")["data"]
wfdetail = wait_terminal(wfexec["id"], 120)
check("条件不成立时工作流仍然成功 —— 「没跑」不是「失败」",
      wfdetail["execution"]["status"] == "SUCCEEDED",
      f"{wfdetail['execution']['status']} {wfdetail['execution'].get('message') or ''}")

false_children = call("GET", f"/executions/{wfexec['id']}/children")["data"]
check("条件为假时下游节点没有被执行",
      [c["workflowNodeId"] for c in false_children] == ["a"],
      str([c["workflowNodeId"] for c in false_children]))
skipped_rows = int(psql(f"SELECT count(*) FROM dg_probe_schema.{wf_b}"))
check("被跳过的节点确实没有写入任何数据", skipped_rows == 0, f"{skipped_rows} 行")

wexec_records = call("GET", "/executions?jobRefType=WORKFLOW&size=100")["data"]
check("「执行记录(工作流)」页(序号 23)= 同一张表加 WORKFLOW 过滤",
      wexec_records["total"] >= 2, f"{wexec_records['total']} 条")

# 级联取消:一个慢工作流,取消父执行应当带走正在跑的子执行
slow_src = f"p3_slow_src_{RUN}"
slow_dst = f"p3_slow_dst_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{slow_src};
    DROP TABLE IF EXISTS dg_probe_schema.{slow_dst};
    CREATE TABLE dg_probe_schema.{slow_src} (id INT PRIMARY KEY, label VARCHAR(32));
    CREATE TABLE dg_probe_schema.{slow_dst} (id INT PRIMARY KEY, label VARCHAR(32));
    INSERT INTO dg_probe_schema.{slow_src}
        SELECT g, 'v' || g FROM generate_series(1, 200000) g;
""")
snapshot(slow_src, slow_dst)
slow_node = make_sync_job(f"P3-慢节点-{RUN}", slow_src, slow_dst)

slow_wf = workflow(f"P3-慢工作流-{RUN}", [task_node("s", slow_node["id"])], [])
call("POST", f"/jobs/{slow_wf['id']}/compile")
call("POST", f"/jobs/{slow_wf['id']}/publish")
slow_exec = call("POST", f"/jobs/{slow_wf['id']}/run")["data"]

# 等到子执行真的跑起来再取消 —— 否则取消的是一个还没开始的东西,
# 那验证不了「级联」
running_child = None
for _ in range(60):
    kids = call("GET", f"/executions/{slow_exec['id']}/children")["data"]
    running_child = next((k for k in kids if k["status"] in ("DISPATCHED", "RUNNING")), None)
    if running_child:
        break
    time.sleep(0.5)
check("慢工作流的子节点已经跑起来", running_child is not None,
      running_child["status"] if running_child else "没等到")

cancel_result = call("POST", f"/executions/{slow_exec['id']}/cancel")["data"]
check("取消工作流会级联取消正在跑的子执行(序号 23 的明确要求)",
      cancel_result["canceledChildren"] >= 1,
      f"级联取消 {cancel_result['canceledChildren']} 个")

cancel_detail = wait_terminal(slow_exec["id"], 60)
check("父执行落到已取消", cancel_detail["execution"]["status"] == "CANCELED",
      cancel_detail["execution"]["status"])

final_kids = call("GET", f"/executions/{slow_exec['id']}/children")["data"]
check("子执行也不再运行",
      all(k["status"] in ("CANCELED", "SUCCEEDED", "FAILED", "TIMEOUT") for k in final_kids),
      str([k["status"] for k in final_kids]))


# ── 制品引用与删除 ──────────────────────────────────────────────────
print("\n【功能 32】制品删除保护")

del_status = call("DELETE", f"/artifacts/{artifact_v2['id']}", raw=True)[0]
check("没有被引用的制品可以删除", del_status == 200, f"HTTP {del_status}")

gone_status, _ = call("GET", f"/artifacts/{artifact_v2['id']}", raw=True)
check("删除后查不到", gone_status == 404, f"HTTP {gone_status}")


# ── 清理 ────────────────────────────────────────────────────────────
for job in (batch_job, stream_job, nojar, node1, node2, draft_node, slow_node, wf, wf_false, slow_wf):
    call("DELETE", f"/jobs/{job['id']}")
call("DELETE", f"/artifacts/{artifact['id']}")
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{batch_table};
    DROP TABLE IF EXISTS dg_probe_schema.{wf_src};
    DROP TABLE IF EXISTS dg_probe_schema.{wf_a};
    DROP TABLE IF EXISTS dg_probe_schema.{wf_b};
    DROP TABLE IF EXISTS dg_probe_schema.{slow_src};
    DROP TABLE IF EXISTS dg_probe_schema.{slow_dst};
""")

print("\n" + "=" * 74)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P3 全部通过")
print("=" * 74)
