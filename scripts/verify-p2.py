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

# 用一个本平台<b>结构上</b>不会产生执行的种类。WORKFLOW_NODE 是这样一个:
# 工作流的节点是以它们各自的种类下发的(OFFLINE_SYNC 之类),这个枚举值
# 只作为分类保留,从来没有执行落在它名下。
#
# 这里原本用的是 PYTHON_JOB,后来 P5 把它变成了一种真任务,这条断言随之
# 失效 —— 选"眼下恰好没有记录"的种类总会这样,要选"结构上不可能有"的
empty = call("GET", "/executions?page=1&size=50&jobRefType=WORKFLOW_NODE")["data"]
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

# ── 7. 整库迁移(功能 9/10)──────────────────────────────────────────
print("\n【7】整库迁移(功能 9/10)—— 建表语句预览 + 真的迁两张表")

mig_a = f"p2_mig_a_{RUN}"
mig_b = f"p2_mig_b_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{mig_a};
    CREATE TABLE dg_probe_schema.{mig_a} (
        id BIGINT PRIMARY KEY, label VARCHAR(32) NOT NULL, ratio DOUBLE PRECISION,
        tz_at TIMESTAMPTZ);
    INSERT INTO dg_probe_schema.{mig_a}
    SELECT g, 'a-' || g, g / 3.0, now() FROM generate_series(1, 300) g;

    DROP TABLE IF EXISTS dg_probe_schema.{mig_b};
    CREATE TABLE dg_probe_schema.{mig_b} (id BIGINT PRIMARY KEY, note TEXT);
    INSERT INTO dg_probe_schema.{mig_b}
    SELECT g, 'note-' || g FROM generate_series(1, 120) g;

    CREATE SCHEMA IF NOT EXISTS dg_mig_target_{RUN};
""")
for table in (mig_a, mig_b):
    call("GET", f"/datasources/{ds_id}/catalog"
                f"?database={PG_DB}&schema=dg_probe_schema&table={table}")
check("迁移源表已就绪", True, f"{mig_a} 300 行 / {mig_b} 120 行")

# 建表语句预览:功能 9 的「预览并修改」
ddl = call("POST", "/ddl/preview", {
    "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
    "sourceSchema": "dg_probe_schema", "sourceTable": mig_a,
    "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
    "targetSchema": f"dg_mig_target_{RUN}",
    "tablePrefix": "t_",
})["data"]
check("生成了建表语句", ddl["script"].upper().startswith("CREATE TABLE"),
      ddl["script"].split("\n")[0])
check("目标表名应用了前缀规则", ddl["targetTable"] == f"t_{mig_a}", ddl["targetTable"])
check("主键被识别并写进建表语句", "PRIMARY KEY" in ddl["script"])
check("PostgreSQL 的注释是独立语句(所以可能不止一条)",
      isinstance(ddl["statements"], list) and len(ddl["statements"]) >= 1,
      f"{len(ddl['statements'])} 条")

# 同一张源表换成 Doris 目标,应当出现降级提醒(TIMESTAMPTZ → DATETIME)
doris_ds = call("POST", "/datasources", {
    "name": f"P2-Doris目标-{RUN}", "type": "DORIS",
    "host": "10.10.0.99", "port": 9030, "databaseName": "dw", "username": "analyst",
    "inlineSecret": {"authType": "PASSWORD", "username": "analyst", "secret": "x"},
})["data"]["id"]
status, res = call("POST", "/ddl/preview", {
    "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
    "sourceSchema": "dg_probe_schema", "sourceTable": mig_a,
    "targetDataSourceId": doris_ds, "targetDatabase": "dw",
}, raw=True)
if status == 200:
    doris_ddl = res["data"]
    check("迁到 Doris 时给出类型降级提醒(时区会丢)",
          any("时区" in w for w in doris_ddl["warnings"]),
          "; ".join(doris_ddl["warnings"])[:120])
    check("Doris 建表语句包含数据模型与分桶",
          "DISTRIBUTED BY HASH" in doris_ddl["script"])
else:
    check("迁到 Doris 时给出类型降级提醒(时区会丢)", False, f"HTTP {status}")

# 真的迁两张表
mig_job = call("POST", "/jobs", {
    "name": f"P2-整库迁移-实跑-{RUN}", "jobType": "DB_MIGRATION",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema",
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": f"dg_mig_target_{RUN}",
        "tables": [mig_a, mig_b],
        "tablePrefix": "t_", "createTable": True,
        "writeMode": "APPEND", "batchSize": 200,
    },
    "timeoutMs": 120000,
})["data"]
mig_id = mig_job["id"]

mig_compile = call("POST", f"/jobs/{mig_id}/compile")["data"]
check("整库迁移编译通过", mig_compile["succeeded"], mig_compile["summary"])
call("POST", f"/jobs/{mig_id}/publish")

mig_exec = call("POST", f"/jobs/{mig_id}/run")["data"]
mig_final = None
for _ in range(90):
    d = call("GET", f"/executions/{mig_exec['id']}")["data"]
    if d["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
        mig_final = d
        break
    time.sleep(1)

if mig_final:
    mex = mig_final["execution"]
    check("整库迁移执行成功", mex["status"] == "SUCCEEDED",
          f"{mex['status']} {mex.get('message') or ''}")
    check("两张表的行数汇总正确",
          (mex.get("rowsRead") or 0) == 420 and (mex.get("rowsWritten") or 0) == 420,
          f"读{mex.get('rowsRead')} 写{mex.get('rowsWritten')}")
    check("执行记录归入整库迁移种类", mex["jobRefType"] == "MIGRATION", mex["jobRefType"])
else:
    check("整库迁移执行成功", False, "90 秒内未结束")

for src, expected in ((mig_a, 300), (mig_b, 120)):
    actual = int(psql(f"SELECT count(*) FROM dg_mig_target_{RUN}.t_{src}"))
    check(f"目标表 t_{src} 有 {expected} 行", actual == expected, f"{actual} 行")

# 序号 10 的执行记录页 = 同一张表加 MIGRATION 过滤
mig_records = call("GET", "/executions?page=1&size=20&jobRefType=MIGRATION")["data"]
check("「整库迁移记录」页(序号 10)= 同一张表加过滤",
      mig_records["total"] >= 1, f"{mig_records['total']} 条")

psql(f"DROP SCHEMA IF EXISTS dg_mig_target_{RUN} CASCADE;"
     f"DROP TABLE IF EXISTS dg_probe_schema.{mig_a};"
     f"DROP TABLE IF EXISTS dg_probe_schema.{mig_b};")

# ── 8. 清洗/转换规则(功能 17)──────────────────────────────────────
print("\n【8】规则管理(功能 17)—— 定义归 Metadata、执行归 Runtime 的双栖对象")

kinds = call("GET", "/rules/kinds")["data"]
# 断言下界而不是精确值:这个清单会随需求增长(P5 加了脱敏),
# 而"前端不硬编码参数表单"这件事与具体有几种无关
check("规则种类元数据由后端下发(前端不硬编码参数表单)",
      len(kinds) >= 8 and all(k.get("paramSpec") is not None for k in kinds),
      f"{len(kinds)} 种")
check("清洗与转换两类齐备",
      {"CLEANSE", "TRANSFORM"} == {k["category"] for k in kinds},
      str(sorted({k["category"] for k in kinds})))
decrypt = next(k for k in kinds if k["kind"] == "DECRYPT")
check("解密规则的参数规格里是 credentialId 而不是密钥",
      "credentialId" in decrypt["paramSpec"] and "key" not in decrypt["paramSpec"],
      str(list(decrypt["paramSpec"])))

# 必填参数缺失要被拦
status, res = call("POST", "/rules", {
    "name": f"缺参数-{RUN}", "kind": "CHANGE_CASE", "params": {},
}, raw=True)
check("缺必填参数的规则被拒", status == 400 and "缺少必填参数" in res.get("message", ""),
      f"HTTP {status} {res.get('message')}")

# 明文密钥不许写进规则参数
status, res = call("POST", "/rules", {
    "name": f"明文密钥-{RUN}", "kind": "DECRYPT",
    "params": {"algorithm": "AES_GCM", "credentialId": "这是一个明文密钥"},
}, raw=True)
check("解密规则的 credentialId 必须是凭据引用,不能是明文",
      status == 400 and "凭据引用" in res.get("message", ""),
      f"HTTP {status} {res.get('message')}")

trim_rule = call("POST", "/rules", {
    "name": f"去空格-{RUN}", "kind": "TRIM", "params": {"mode": "BOTH"},
})["data"]
upper_rule = call("POST", "/rules", {
    "name": f"转大写-{RUN}", "kind": "CHANGE_CASE", "params": {"mode": "UPPER"},
})["data"]
check("规则创建成功", trim_rule["id"].startswith("rule_") and upper_rule["id"].startswith("rule_"),
      f"{trim_rule['kindDisplayName']} / {upper_rule['kindDisplayName']}")
check("新规则的引用计数为 0", trim_rule["referenceCount"] == 0)

# 规则真的作用在同步的数据上
rule_src = f"p2_rule_src_{RUN}"
rule_dst = f"p2_rule_dst_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{rule_src};
    CREATE TABLE dg_probe_schema.{rule_src} (id BIGINT PRIMARY KEY, code VARCHAR(64));
    INSERT INTO dg_probe_schema.{rule_src} VALUES
        (1, '  abc  '), (2, '  Def'), (3, 'ghi  ');

    DROP TABLE IF EXISTS dg_probe_schema.{rule_dst};
    CREATE TABLE dg_probe_schema.{rule_dst} (id BIGINT PRIMARY KEY, code VARCHAR(64));
""")
for table in (rule_src, rule_dst):
    call("GET", f"/datasources/{ds_id}/catalog"
                f"?database={PG_DB}&schema=dg_probe_schema&table={table}")

rule_job = call("POST", "/jobs", {
    "name": f"P2-带规则的同步-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": rule_src,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": rule_dst,
        "fieldMappings": {"id": "id", "code": "code"},
        # 顺序有意义:先去空格再转大写
        "fieldRules": {"code": [trim_rule["id"], upper_rule["id"]]},
        "writeMode": "APPEND", "batchSize": 100,
    },
    "timeoutMs": 60000,
})["data"]

rule_compile = call("POST", f"/jobs/{rule_job['id']}/compile")["data"]
check("带规则的同步编译通过", rule_compile["succeeded"], rule_compile["summary"])

# 规则挂在不存在的映射字段上要被拦
bad_rule_job = call("POST", "/jobs", {
    "name": f"P2-规则字段错-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": rule_src,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": rule_dst,
        "fieldMappings": {"id": "id"},
        "fieldRules": {"code": [trim_rule["id"]]},   # code 不在映射里
    },
})["data"]
bad_compile = call("POST", f"/jobs/{bad_rule_job['id']}/compile")["data"]
check("规则挂在未映射字段上被拦(编译期)",
      not bad_compile["succeeded"]
      and any("不在字段映射里" in d["message"] for d in bad_compile["diagnostics"]),
      bad_compile["summary"])

call("POST", f"/jobs/{rule_job['id']}/publish")
rule_exec = call("POST", f"/jobs/{rule_job['id']}/run")["data"]
for _ in range(60):
    d = call("GET", f"/executions/{rule_exec['id']}")["data"]
    if d["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
        break
    time.sleep(1)
check("带规则的同步执行成功", d["execution"]["status"] == "SUCCEEDED",
      f"{d['execution']['status']} {d['execution'].get('message') or ''}")

actual = psql(f"SELECT code FROM dg_probe_schema.{rule_dst} ORDER BY id")
check("规则真的作用在数据上(去空格 + 转大写)",
      actual.split("\n") == ["ABC", "DEF", "GHI"], repr(actual))

# 被引用的规则不许删 —— 引用计数由 Control 在保存任务时维护
referenced = call("GET", f"/rules/{trim_rule['id']}")["data"]
check("规则被任务引用后计数 > 0", (referenced.get("referenceCount") or 0) > 0,
      f"引用数 {referenced.get('referenceCount')}")

status, res = call("DELETE", f"/rules/{trim_rule['id']}", raw=True)
check("被引用的规则不许删(否则任务会在凌晨的调度里找不到规则)",
      status == 409 and res.get("code") == "MTD_RULE_IN_USE",
      f"HTTP {status} {res.get('code')}")

# 解除引用后就能删了
call("DELETE", f"/jobs/{bad_rule_job['id']}")
call("DELETE", f"/jobs/{rule_job['id']}")
after_release = call("GET", f"/rules/{trim_rule['id']}")["data"]
check("任务删除后引用计数归零", (after_release.get("referenceCount") or 0) == 0,
      f"引用数 {after_release.get('referenceCount')}")
status, _ = call("DELETE", f"/rules/{trim_rule['id']}", raw=True)
check("解除引用后可以删除", status == 200, f"HTTP {status}")

psql(f"DROP TABLE IF EXISTS dg_probe_schema.{rule_src};"
     f"DROP TABLE IF EXISTS dg_probe_schema.{rule_dst};")

# ── 9. 文件解析与接口解析(功能 12/13)──────────────────────────────
print("\n【9】文件解析(功能12)与接口解析(功能13)")

# 起一个本地 HTTP 服务当接口数据源 —— 比 mock 更能验证真实的 HTTP 路径
import http.server, json as _json, socketserver, threading

# 0 = 让内核挑一个空闲端口。固定端口会在上一次运行中途失败时留下占用,
# 于是重跑报「地址已被占用」,把真正要查的那个失败盖掉
STUB_PORT_REQUESTED = int(os.environ.get("DG_VERIFY_STUB_PORT", "0"))


class _StubHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        # 两页,每页两条;第三页空,用来验证「空页即结束」
        page = 1
        if "page=" in self.path:
            page = int(self.path.split("page=")[1].split("&")[0])
        items = ([{"uid": page * 10 + 1, "label": f"p{page}-a"},
                  {"uid": page * 10 + 2, "label": f"p{page}-b"}] if page <= 2 else [])
        body = _json.dumps({"data": {"items": items}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class _StubServer(socketserver.TCPServer):
    allow_reuse_address = True


stub = _StubServer(("127.0.0.1", STUB_PORT_REQUESTED), _StubHandler)
API_PORT = stub.server_address[1]
threading.Thread(target=stub.serve_forever, daemon=True).start()

api_dst = f"p2_api_dst_{RUN}"
psql(f"""
    DROP TABLE IF EXISTS dg_probe_schema.{api_dst};
    CREATE TABLE dg_probe_schema.{api_dst} (uid BIGINT PRIMARY KEY, label VARCHAR(64));
""")
call("GET", f"/datasources/{ds_id}/catalog"
            f"?database={PG_DB}&schema=dg_probe_schema&table={api_dst}")

api_ds = call("POST", "/datasources", {
    "name": f"P2-桩接口-{RUN}", "type": "REST_API",
    "baseUrl": f"http://127.0.0.1:{API_PORT}",
    "inlineSecret": {"authType": "NONE"},
})["data"]["id"]
call("POST", f"/datasources/{api_ds}/test")

# 分页必须有上限 —— 没有上限的分页是一个无限循环的邀请
no_limit = call("POST", "/jobs", {
    "name": f"P2-接口无上限-{RUN}", "jobType": "API_PARSE",
    "config": {
        "sourceDataSourceId": api_ds, "path": "/items",
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": api_dst,
        "fieldMappings": {"uid": "uid", "label": "label"},
        "jsonPath": "data.items",
        "pagination": {"mode": "PAGE", "pageParam": "page", "sizeParam": "size", "size": 2},
    },
})["data"]
no_limit_compile = call("POST", f"/jobs/{no_limit['id']}/compile")["data"]
check("分页拉取必须指定页数上限(否则可能无限循环)",
      not no_limit_compile["succeeded"]
      and any("上限" in d["message"] for d in no_limit_compile["diagnostics"]),
      no_limit_compile["summary"])

api_job = call("POST", "/jobs", {
    "name": f"P2-接口解析-{RUN}", "jobType": "API_PARSE",
    "config": {
        "sourceDataSourceId": api_ds, "path": "/items",
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": api_dst,
        "fieldMappings": {"uid": "uid", "label": "label"},
        "jsonPath": "data.items",
        "pagination": {"mode": "PAGE", "pageParam": "page", "sizeParam": "size",
                       "size": 2, "maxPages": 10},
        "writeMode": "APPEND", "batchSize": 10,
    },
    "timeoutMs": 60000,
})["data"]
api_compile = call("POST", f"/jobs/{api_job['id']}/compile")["data"]
check("接口解析编译通过", api_compile["succeeded"], api_compile["summary"])
call("POST", f"/jobs/{api_job['id']}/publish")

api_exec = call("POST", f"/jobs/{api_job['id']}/run")["data"]
for _ in range(60):
    d = call("GET", f"/executions/{api_exec['id']}")["data"]
    if d["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
        break
    time.sleep(1)
check("接口解析执行成功", d["execution"]["status"] == "SUCCEEDED",
      f"{d['execution']['status']} {d['execution'].get('message') or ''}")
check("分页拉取在空页处停止(2 页 × 2 条 = 4 行,不是 10 页)",
      (d["execution"].get("rowsWritten") or 0) == 4,
      f"写{d['execution'].get('rowsWritten')} 行")

api_rows = int(psql(f"SELECT count(*) FROM dg_probe_schema.{api_dst}"))
check("目标表里真的有 4 行", api_rows == 4, f"{api_rows} 行")
api_label = psql(f"SELECT label FROM dg_probe_schema.{api_dst} ORDER BY uid")
check("两页的数据都写进去了", api_label.split("\n") == ["p1-a", "p1-b", "p2-a", "p2-b"],
      repr(api_label))

check("接口解析记录归入 API_PARSE 种类",
      d["execution"]["jobRefType"] == "API_PARSE", d["execution"]["jobRefType"])

stub.shutdown()
psql(f"DROP TABLE IF EXISTS dg_probe_schema.{api_dst};")


# ── 功能 16:任务目录 ─────────────────────────────────────────────────
print("\n【功能 16】任务目录 —— 人工维护的组织结构")

root_cat = call("POST", "/jobs/catalog",
                {"name": f"数仓-{RUN}", "description": "ODS 层同步"})["data"]
check("新建根目录", root_cat["id"].startswith("tsc_"), root_cat["id"])

child_cat = call("POST", "/jobs/catalog",
                 {"parentId": root_cat["id"], "name": "ODS"})["data"]
check("新建子目录", child_cat["parentId"] == root_cat["id"], child_cat["parentId"])

dup_status, dup_body = call("POST", "/jobs/catalog",
                            {"name": f"数仓-{RUN}"}, raw=True)
check("同级重名被拒绝", dup_status != 200 and "已有" in json.dumps(dup_body, ensure_ascii=False),
      f"HTTP {dup_status}")

# 同名但在不同父目录下应当允许 —— 树的每一层是独立的命名空间
sibling = call("POST", "/jobs/catalog",
               {"parentId": child_cat["id"], "name": f"数仓-{RUN}"})["data"]
check("不同父目录下可以重名", sibling["name"] == f"数仓-{RUN}", sibling["name"])

# 深度:根(1) → ODS(2) → 同名(3) → 第四层(4) 应当被拒
deep_status, deep_body = call("POST", "/jobs/catalog",
                              {"parentId": sibling["id"], "name": "太深了"}, raw=True)
check("目录层级超过 4 层被拒绝",
      deep_status != 200 and "层级" in json.dumps(deep_body, ensure_ascii=False),
      f"HTTP {deep_status}")

tree = call("GET", "/jobs/catalog")["data"]
mine = [n for n in tree["nodes"] if n["id"] == root_cat["id"]]
check("目录树按父子关系嵌套返回",
      len(mine) == 1 and len(mine[0]["children"]) == 1
      and mine[0]["children"][0]["id"] == child_cat["id"],
      json.dumps([n["name"] for n in tree["nodes"]], ensure_ascii=False))

before_uncat = tree["uncategorizedCount"]
check("未分类计数单独返回", isinstance(before_uncat, int) and before_uncat > 0,
      f"{before_uncat} 个未分类")

# 任务挂在叶子节点上,好让两条删除保护各自独立可测:
# 叶子被任务挡住,它的父目录被子目录挡住
cat_job = call("POST", "/jobs", {
    "name": f"P2-归类任务-{RUN}", "jobType": "OFFLINE_SYNC",
    "catalogId": sibling["id"],
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src_table,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst_table,
        "fieldMappings": {"id": "id"}, "writeMode": "APPEND", "batchSize": 100,
    },
})["data"]
check("任务可以归属目录", cat_job["catalogId"] == sibling["id"], cat_job["catalogId"])

tree2 = call("GET", "/jobs/catalog")["data"]
mid = next(n["children"][0] for n in tree2["nodes"] if n["id"] == root_cat["id"])
leaf = mid["children"][0]
check("任务计数落在直接挂载的那个目录上", leaf["taskCount"] == 1, f"{leaf['taskCount']} 个")
check("计数不向上累加 —— 父目录仍是 0(与树的展示一致)",
      mid["taskCount"] == 0, f"{mid['taskCount']} 个")
check("归类后未分类计数不变(该任务本来就是新建的)",
      tree2["uncategorizedCount"] == before_uncat,
      f"{before_uncat} → {tree2['uncategorizedCount']}")

by_cat = call("GET", f"/jobs?catalogId={sibling['id']}&size=100")["data"]
check("按目录过滤任务列表",
      [r["id"] for r in by_cat["records"]] == [cat_job["id"]],
      f"{by_cat['total']} 条")

uncat = call("GET", "/jobs?catalogId=__none__&size=100")["data"]
# 响应全局省略 null 字段(application.yml 的 non_null),所以"没有 catalogId"
# 就是未分类 —— 用 .get() 而不是下标
check("__none__ 过滤出未分类任务",
      uncat["total"] == before_uncat
      and all(r.get("catalogId") is None for r in uncat["records"]),
      f"{uncat['total']} 条")

busy_status, busy_body = call("DELETE", f"/jobs/catalog/{sibling['id']}", raw=True)
check("含任务的目录不可删除(级联删除会带走几个月的调度配置)",
      busy_status != 200 and "个任务" in busy_body.get("message", ""),
      f"HTTP {busy_status} {busy_body.get('message')}")

parent_status, parent_body = call("DELETE", f"/jobs/catalog/{child_cat['id']}", raw=True)
check("含子目录的目录不可删除",
      parent_status != 200 and "子目录" in parent_body.get("message", ""),
      f"HTTP {parent_status} {parent_body.get('message')}")

renamed = call("PUT", f"/jobs/catalog/{root_cat['id']}",
               {"name": f"数仓改名-{RUN}", "sortOrder": 5})["data"]
check("目录可以改名", renamed["name"] == f"数仓改名-{RUN}", renamed["name"])

call("DELETE", f"/jobs/{cat_job['id']}")
empty_status, _ = call("DELETE", f"/jobs/catalog/{sibling['id']}", raw=True)
check("清空后目录可以删除", empty_status == 200, f"HTTP {empty_status}")
call("DELETE", f"/jobs/catalog/{child_cat['id']}")
call("DELETE", f"/jobs/catalog/{root_cat['id']}")


# ── 功能 14:批量新增 ─────────────────────────────────────────────────
print("\n【功能 14】批量新增 —— 创建的是 N 个独立定义,不是一个批量任务")

batch_tables = [f"bt_{RUN}_a", f"bt_{RUN}_b", f"bt_{RUN}_c"]
batch_req = {
    "namePattern": f"P2-批量-{RUN}-" + "{table}",
    "description": "批量创建验证",
    "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
    "sourceSchema": "dg_probe_schema", "tables": batch_tables,
    "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
    "targetSchema": "dg_probe_schema", "targetTablePrefix": "ods_",
    "writeMode": "APPEND", "batchSize": 500, "timeoutMs": 60000,
}
batch = call("POST", "/jobs/batch", batch_req)["data"]
check("批量创建返回逐表结果",
      len(batch["created"]) == 3 and len(batch["failed"]) == 0,
      f"成功 {len(batch['created'])} 失败 {len(batch['failed'])}")

check("每张表一个独立的任务定义(ID 各不相同)",
      len({j["id"] for j in batch["created"]}) == 3,
      str([j["id"][:12] for j in batch["created"]]))

check("任务名按模板渲染",
      sorted(j["name"] for j in batch["created"])
      == sorted(f"P2-批量-{RUN}-{t}" for t in batch_tables),
      str([j["name"] for j in batch["created"]]))

first = call("GET", f"/jobs/{batch['created'][0]['id']}")["data"]
check("源表逐个写进各自的配置",
      first["config"]["sourceTable"] in batch_tables, first["config"]["sourceTable"])
check("目标表名加了前缀",
      first["config"]["targetTable"] == "ods_" + first["config"]["sourceTable"],
      first["config"]["targetTable"])
check("批量创建的是草稿,仍需各自编译发布", first["status"] == "DRAFT", first["status"])

# 部分成功:重跑同一批,其中一张换成新表名。3 张里 3 张重名 + 1 张新的
mixed = dict(batch_req, tables=batch_tables + [f"bt_{RUN}_d"])
partial = call("POST", "/jobs/batch", mixed)["data"]
check("部分成功是正常结果,不是整批回滚",
      len(partial["created"]) == 1 and len(partial["failed"]) == 3,
      f"成功 {len(partial['created'])} 失败 {len(partial['failed'])}")
check("失败逐条带表名与原因",
      sorted(f["table"] for f in partial["failed"]) == sorted(batch_tables)
      and all(f["reason"] for f in partial["failed"]),
      str(partial["failed"][:1]))

over_status, over_body = call("POST", "/jobs/batch",
                              dict(batch_req, tables=[f"t{i}" for i in range(201)]),
                              raw=True)
check("超过批量上限被拒绝(201 张更像是想要整库迁移)",
      over_status != 200 and "最多批量创建 200" in over_body.get("message", ""),
      f"HTTP {over_status} {over_body.get('message')}")

empty_status2, empty_body2 = call("POST", "/jobs/batch",
                                  dict(batch_req, tables=[]), raw=True)
check("没选源表被拒绝",
      empty_status2 != 200 and "源表" in empty_body2.get("message", ""),
      f"HTTP {empty_status2} {empty_body2.get('message')}")

# 批量创建的定义没有字段映射 —— 编译必须明确报出来,而不是编译通过后跑出个空表
bc = call("POST", f"/jobs/{batch['created'][0]['id']}/compile")["data"]
check("批量创建的定义缺字段映射时编译失败(不猜映射)",
      not bc["succeeded"] and any("映射" in d["message"] for d in bc["diagnostics"]),
      bc["summary"])

for j in batch["created"] + partial["created"]:
    call("DELETE", f"/jobs/{j['id']}")


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
