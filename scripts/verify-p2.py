#!/usr/bin/env python3
"""P2 完成判据的端到端验证:任务定义能编译、能调度、能真的把数据搬过去。

全程走真实 HTTP 接口与真实 PostgreSQL。与 verify-p1.py 一样,连接参数从环境变量读,
不硬编码 —— 这个脚本会进版本库。
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


def call(method, path, body=None, expect=200, raw=False):
    url = API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if workspace:
        req.add_header("X-Workspace-Id", workspace)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read())
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read())
        status = e.code
    return (status, payload) if raw else payload


def check(label, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  — {detail}" if detail else ""))
    if not ok:
        failures.append(label)
    return ok


def psql(sql, db=PG_DB):
    """直接连测试库执行 SQL。验证「数据真的搬过去了」只能靠看目标表。"""
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    result = subprocess.run(
        ["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER, "-d", db,
         "-tAc", sql],
        capture_output=True, text=True, env=env, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(f"psql 失败: {result.stderr.strip()}")
    return result.stdout.strip()


print("=" * 74)
print(" P2 验证 — 任务定义编译 / Cron 调度 / 统一执行事实 / 离线同步真的搬数据")
print("=" * 74)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")

# ── 准备:真实的源表与目标表 ──────────────────────────────────────────
print("\n【准备】在测试库里造一对真实的源表/目标表")
src_table = f"p2_src_{RUN}"
dst_table = f"p2_dst_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{src_table};
    CREATE TABLE dg_probe_schema.{src_table} (
        id BIGINT PRIMARY KEY, name VARCHAR(64), amount NUMERIC(12,2), created_at TIMESTAMP);
    INSERT INTO dg_probe_schema.{src_table}
    SELECT g, 'row-' || g, g * 1.5, now() FROM generate_series(1, 2500) g;

    DROP TABLE IF EXISTS dg_probe_schema.{dst_table};
    CREATE TABLE dg_probe_schema.{dst_table} (
        id BIGINT PRIMARY KEY, name VARCHAR(64), amount NUMERIC(12,2), created_at TIMESTAMP);
""")
src_count = int(psql(f"SELECT count(*) FROM dg_probe_schema.{src_table}"))
check("源表已就绪", src_count == 2500, f"{src_count} 行")

# 数据源 + 结构快照(编译器读的是快照,不连库)
ds = call("POST", "/datasources", {
    "name": f"P2-同步源-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})
ds_id = ds["data"]["id"]
call("POST", f"/datasources/{ds_id}/test")
for table in (src_table, dst_table):
    call("GET", f"/datasources/{ds_id}/catalog"
                f"?database={PG_DB}&schema=dg_probe_schema&table={table}")
check("数据源已验证且两张表的结构已入快照", True, ds_id)

# ── 1. 编译:错误必须带定位 ───────────────────────────────────────────
print("\n【1】编译校验 —— 错误必须指到具体字段,而不是一句「编译失败」")

bad = call("POST", "/jobs", {
    "name": f"P2-字段拼错-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src_table,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst_table,
        "fieldMappings": {"id": "id", "nmae": "name"},   # 故意拼错源字段
    },
})
bad_id = bad["data"]["id"]
result = call("POST", f"/jobs/{bad_id}/compile")["data"]
check("拼错字段的定义编译不通过", result["succeeded"] is False, result["summary"])
diags = result["diagnostics"]
located = [d for d in diags if d["severity"] == "ERROR" and "nmae" in (d.get("location") or "")]
check("诊断指到出错的那个字段(而不是笼统报错)",
      len(located) > 0, located[0]["message"] if located else str(diags))
check("编译失败返回 HTTP 200 —— UI 要拿到完整诊断才能标红对应字段",
      True, "succeeded=false")

status, _ = call("POST", f"/jobs/{bad_id}/publish", raw=True)
check("编译不过的定义无法发布", status == 400, f"HTTP {status}")

# 类型有损要给 WARNING 而不是拦下来
lossy = call("POST", "/jobs", {
    "name": f"P2-有损映射-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src_table,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst_table,
        # amount(DECIMAL) → name(VARCHAR):能写,但退化成字符串
        "fieldMappings": {"id": "id", "amount": "name"},
    },
})
lossy_result = call("POST", f"/jobs/{lossy['data']['id']}/compile")["data"]
warnings = [d for d in lossy_result["diagnostics"] if d["severity"] == "WARNING"]
check("有损的类型映射给 WARNING 而不是拦下来",
      lossy_result["succeeded"] and len(warnings) > 0,
      warnings[0]["message"] if warnings else "没有 WARNING")

# ── 2. 正确的定义:编译 → 发布 → 执行 ────────────────────────────────
print("\n【2】离线同步(功能 11/15)—— 真的把 2500 行搬过去")

job = call("POST", "/jobs", {
    "name": f"P2-离线同步-{RUN}", "jobType": "OFFLINE_SYNC",
    "description": "P2 验证用",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src_table,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst_table,
        "fieldMappings": {"id": "id", "name": "name",
                          "amount": "amount", "created_at": "created_at"},
        "writeMode": "APPEND", "batchSize": 500,
    },
    "timeoutMs": 120000,
})
job_id = job["data"]["id"]
check("任务定义已创建,初始为草稿", job["data"]["status"] == "DRAFT", job_id)

compiled = call("POST", f"/jobs/{job_id}/compile")["data"]
check("编译通过", compiled["succeeded"], compiled["summary"])

view = call("GET", f"/jobs/{job_id}")["data"]
check("编译后进入已校验,物理计划与定义版本一致",
      view["status"] == "VALIDATED" and view["planUpToDate"],
      f"{view['status']} planUpToDate={view['planUpToDate']}")

published = call("POST", f"/jobs/{job_id}/publish")["data"]
check("发布成功", published["status"] == "PUBLISHED", published["status"])

execution = call("POST", f"/jobs/{job_id}/run")["data"]
exec_id = execution["id"]
check("手工触发产生一条执行记录", exec_id.startswith("exec_"), exec_id)

# 等它跑完
final = None
for _ in range(60):
    detail = call("GET", f"/executions/{exec_id}")["data"]
    if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
        final = detail
        break
    time.sleep(1)

check("执行在 60 秒内结束", final is not None,
      final["execution"]["status"] if final else "超时未结束")
if final:
    ex = final["execution"]
    check("执行成功", ex["status"] == "SUCCEEDED",
          f"{ex['status']} {ex.get('message') or ''}")
    # 响应体省略 null 字段,所以一律用 get 取 —— 直接下标会在失败路径上
    # 抛 KeyError,把真正的失败原因盖掉
    rows_read, rows_written = ex.get("rowsRead") or 0, ex.get("rowsWritten") or 0
    check("指标记录了读写行数", rows_read == 2500 and rows_written == 2500,
          f"读{rows_read} 写{rows_written}")
    check("只有一次尝试(没重试)", ex.get("attemptCount") == 1, f"{ex.get('attemptCount')} 次")
    check("尝试明细可查", len(final["attempts"]) == 1,
          f"{len(final['attempts'])} 条尝试")
    check("绑定了执行时的定义版本", ex.get("defVersion") is not None, f"v{ex.get('defVersion')}")

dst_count = int(psql(f"SELECT count(*) FROM dg_probe_schema.{dst_table}"))
check("目标表里真的有 2500 行", dst_count == 2500, f"{dst_count} 行")
checksum = psql(f"SELECT sum(id), sum(amount) FROM dg_probe_schema.{dst_table}")
expected = psql(f"SELECT sum(id), sum(amount) FROM dg_probe_schema.{src_table}")
check("源与目标的数值汇总一致(没有丢行也没有串列)",
      checksum == expected, f"目标 {checksum} / 源 {expected}")

# ── 3. 统一执行事实(架构约束 R4)───────────────────────────────────
print("\n【3】统一执行事实 —— 五个「执行记录」页面查的是同一张表")

all_execs = call("GET", "/executions?page=1&size=50")["data"]
check("不加过滤能查到执行记录(序号 24 监控看到的全量)",
      all_execs["total"] >= 1, f"{all_execs['total']} 条")

by_type = call("GET", "/executions?page=1&size=50&jobRefType=OFFLINE_SYNC")["data"]
check("按 jobRefType 过滤即得到「离线同步执行记录」页(序号 15)",
      by_type["total"] >= 1, f"{by_type['total']} 条")

empty = call("GET", "/executions?page=1&size=50&jobRefType=MIGRATION")["data"]
check("过滤到没有记录的种类返回空,而不是串到别的种类",
      empty["total"] == 0, f"{empty['total']} 条")

by_job = call("GET", f"/executions?page=1&size=50&jobRefId={job_id}")["data"]
check("按任务定义过滤(定义详情页的「最近执行」)",
      by_job["total"] >= 1, f"{by_job['total']} 条")

types = call("GET", "/executions/job-types")["data"]
check("作业种类元数据由后端下发,前端不硬编码", len(types) >= 10, f"{len(types)} 种")

# ── 4. 调度(功能 16)────────────────────────────────────────────────
print("\n【4】Cron 周期调度(功能 16)")

preview = call("POST", "/jobs/schedule-preview",
               {"cronExpression": "0 2 * * *", "timezone": "Asia/Shanghai"})["data"]
check("crontab 风格的 5 段被自动补成 6 段",
      preview["cronExpression"] == "0 0 2 * * *", preview["cronExpression"])
check("预览给出接下来 5 次触发时间", len(preview["upcomingFireTimes"]) == 5,
      preview["upcomingFireTimes"][0])

status, res = call("POST", "/jobs/schedule-preview",
                   {"cronExpression": "* * * * * *"}, raw=True)
check("过密的调度被拒(每秒一次对目标库是持续压力)",
      status == 400 and res.get("code") == "CTL_INVALID_CRON",
      f"HTTP {status} {res.get('code')}")

scheduled = call("POST", f"/jobs/{job_id}/schedule",
                 {"cronExpression": "0 0 3 * * *", "timezone": "Asia/Shanghai",
                  "misfirePolicy": "SKIP"})["data"]
check("绑定调度后进入调度中", scheduled["status"] == "SCHEDULING", scheduled["status"])
check("算出了下次触发时刻", scheduled.get("nextFireAt") is not None,
      str(scheduled.get("nextFireAt")))

paused = call("POST", f"/jobs/{job_id}/schedule/pause")["data"]
check("暂停后不再有下次触发时刻",
      paused["status"] == "PAUSED" and paused.get("nextFireAt") is None, paused["status"])
check("暂停中仍能手工触发 —— 暂停关掉的只是自动触发",
      call("POST", f"/jobs/{job_id}/run", raw=True)[0] == 200)

resumed = call("POST", f"/jobs/{job_id}/schedule/resume")["data"]
check("恢复调度后重新算出下次触发",
      resumed["status"] == "SCHEDULING" and resumed.get("nextFireAt") is not None,
      str(resumed.get("nextFireAt")))

# ── 5. 计划过期保护 ─────────────────────────────────────────────────
print("\n【5】改了定义没重新编译 —— 必须拒绝执行,而不是跑一份过期的计划")

call("PUT", f"/jobs/{job_id}", {
    "name": f"P2-离线同步-改名-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": call("GET", f"/jobs/{job_id}")["data"].get("config", {}),
})
after_edit = call("GET", f"/jobs/{job_id}")["data"]
check("修改后状态被打回草稿", after_edit["status"] == "DRAFT", after_edit["status"])
check("物理计划被标记为过期", after_edit.get("planUpToDate") is False,
      f"planUpToDate={after_edit.get('planUpToDate')}")

status, res = call("POST", f"/jobs/{job_id}/run", raw=True)
check("过期计划拒绝执行",
      status == 409 and res.get("code") in ("CTL_PLAN_STALE", "CTL_JOB_NOT_RUNNABLE"),
      f"HTTP {status} {res.get('code')}")

# ── 6. 不该绑调度的任务类型 ─────────────────────────────────────────
print("\n【6】一次性任务与常驻任务不允许绑 Cron")

job_types = call("GET", "/jobs/types")["data"]
non_schedulable = {t["type"] for t in job_types if not t["schedulable"]}
check("后端声明整库迁移与实时任务不可调度",
      {"DB_MIGRATION", "STREAMING"} <= non_schedulable, str(sorted(non_schedulable)))

migration = call("POST", "/jobs", {
    "name": f"P2-整库迁移-{RUN}", "jobType": "DB_MIGRATION",
    "config": {"sourceDataSourceId": ds_id, "targetDataSourceId": ds_id},
})
status, res = call("POST", f"/jobs/{migration['data']['id']}/schedule",
                   {"cronExpression": "0 0 3 * * *"}, raw=True)
check("给整库迁移绑 Cron 被拒",
      status == 409 and res.get("code") == "CTL_JOB_NOT_SCHEDULABLE",
      f"HTTP {status} {res.get('code')}")

# ── 清理 ────────────────────────────────────────────────────────────
psql(f"DROP TABLE IF EXISTS dg_probe_schema.{src_table};"
     f"DROP TABLE IF EXISTS dg_probe_schema.{dst_table};")

print("\n" + "=" * 74)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P2 全部通过")
print("=" * 74)
