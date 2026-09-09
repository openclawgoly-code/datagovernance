#!/usr/bin/env python3
"""P8:重试的幂等性 —— 失败重投会不会把已经落盘的行再写一遍。

<b>为什么单独拿出来验</b>:前七份脚本里的任务要么整个成功,要么整个失败。
真实世界最常见的第三种形态没被碰过 —— <b>写到一半失败</b>。它之所以要紧,是因为
两条各自都合理的设计合在一起会出事:

  一、{@code RowWriter.Session.flush()} 每攒满一批就 commit 一次。这是必需的:
      几百万行的文件不可能攒在一个事务里,那会把目标库的 WAL 撑爆。
  二、失败后按重试策略重投,重投的是<b>整个任务</b> —— runner 从源端第一行
      重新读起,它没有断点。

于是 writeMode=APPEND 时,第一次尝试提交了的行,在第二次尝试里会<b>再插一遍</b>。
任务最终报 FAILED,而目标表里躺着几份重复数据。用户看到的是"任务失败了",
不会想到"失败的任务还往目标表里塞了三份数据"。

而 OVERWRITE 不会:它每次开写前先 DELETE 整表,重投天然幂等。UPSERT 同理 ——
按主键覆盖,写几遍结果一样。<b>所以这不是"重试有害",而是"重试对某些写入模式
有害"</b>。修法必须保住前者:连不上目标库、认证失败、源端超时,这些失败一行都
没写,重试正是为它们准备的。

<b>怎么让它确定性地写到一半失败</b>:目标表加一条 CHECK,拒绝某一个特定的 id。
批次大小 1000、坏行在 3777,于是前三批(3000 行)提交成功,第四批撞上约束整批回滚。
每次尝试都卡在同一个地方,行为可复现。

<b>判据不看行数看重复</b>:count(*) 与 count(distinct id) 一比就知道有没有写重。
这个判据不依赖"每次尝试恰好写了多少行",所以源端返回顺序变了也不会误报。

前置:
  应用已启动,且 DG_ADMIN_PASSWORD 与启动时一致
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

SCHEMA = "dg_probe_schema"
SRC_ROWS = int(os.environ.get("DG_VERIFY_RETRY_ROWS", "5000"))
BATCH = int(os.environ.get("DG_VERIFY_RETRY_BATCH", "1000"))
# 坏行落在第四批里。批次边界之外的位置才验得到"前几批已提交" ——
# 若它落在第一批,一行都没提交,那验的是另一条路径(见第二节)。
BAD_ID = int(os.environ.get("DG_VERIFY_RETRY_BAD_ID", "3777"))
MAX_ATTEMPTS = 3
BACKOFF_SECONDS = 1     # 退避要短,否则这个脚本大半时间在等

if not ADMIN_PASSWORD:
    sys.exit("请设置 DG_ADMIN_PASSWORD(应用启动时用的那个管理员口令)")

token = None
workspace = None
failures = []


def call(method, path, body=None, raw=False, timeout=120):
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
            payload, status = json.loads(resp.read()), resp.status
    except urllib.error.HTTPError as e:
        payload, status = json.loads(e.read()), e.code
    return (status, payload) if raw else payload


def check(label, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  — {detail}" if detail else ""))
    if not ok:
        failures.append(label)
    return ok


def psql(sql, db=PG_DB):
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    r = subprocess.run(["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER,
                        "-d", db, "-tAc", sql],
                       capture_output=True, text=True, env=env, timeout=300)
    if r.returncode != 0:
        raise RuntimeError(f"psql 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def wait_terminal(execution_id, seconds=300):
    """等到<b>执行</b>落终态。

    不能等"尝试"落终态就返回:第一次尝试失败后执行还停在 RUNNING 等重投,
    此刻查目标表只看得到一份数据 —— 重复要到最后一次尝试之后才看得见。
    """
    detail = None
    for _ in range(seconds * 2):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(0.5)
    return detail


def make_job(name, target_table, write_mode, retry_attempts):
    job = call("POST", "/jobs", {
        "name": name, "jobType": "OFFLINE_SYNC",
        "config": {
            "sourceDataSourceId": ds, "sourceDatabase": PG_DB,
            "sourceSchema": SCHEMA, "sourceTable": src,
            "targetDataSourceId": ds, "targetDatabase": PG_DB,
            "targetSchema": SCHEMA, "targetTable": target_table,
            "fieldMappings": {"id": "id", "title": "title"},
            "writeMode": write_mode, "batchSize": BATCH,
        },
        "timeoutMs": 300000,
        "retryMaxAttempts": retry_attempts,
        "retryBackoffSeconds": BACKOFF_SECONDS,
    })["data"]
    created_jobs.append(job["id"])
    compiled = call("POST", f"/jobs/{job['id']}/compile")["data"]
    if not compiled["succeeded"]:
        sys.exit(f"任务 {name} 编译失败:{compiled['summary']}")
    call("POST", f"/jobs/{job['id']}/publish")
    return job["id"]


print("=" * 78)
print(" P8 · 重试的幂等性 —— 写到一半失败后重投,会不会把已落盘的行再写一遍")
print("=" * 78)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")

created_jobs = []
created_datasources = []

psql(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")
src = f"p8_src_{RUN}"
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{src}; "
     f"CREATE TABLE {SCHEMA}.{src} AS "
     f"SELECT g AS id, 'row-' || g AS title FROM generate_series(1, {SRC_ROWS}) g")

res = call("POST", "/datasources", {
    "name": f"P8-重试-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})
ds = res["data"]["id"]
created_datasources.append(ds)
call("POST", f"/datasources/{ds}/test")


def target_with_check(name):
    """目标表 = 源表结构 + 一条拒绝 BAD_ID 的 CHECK。"""
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{name}; "
         f"CREATE TABLE {SCHEMA}.{name} ("
         f"  id int, title text, CONSTRAINT {name}_ck CHECK (id <> {BAD_ID}))")
    return name


dst_append = target_with_check(f"p8_dst_append_{RUN}")
dst_overwrite = target_with_check(f"p8_dst_over_{RUN}")
dst_clean = f"p8_dst_clean_{RUN}"
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{dst_clean}; "
     f"CREATE TABLE {SCHEMA}.{dst_clean} (id int, title text)")

# 编译要读源表与目标表的<b>列</b>结构,模式级浏览只列表名 —— 必须逐表探一次
call("GET", f"/datasources/{ds}/catalog?database={PG_DB}&schema={SCHEMA}&refresh=true")
for t in (src, dst_append, dst_overwrite, dst_clean):
    call("GET", f"/datasources/{ds}/catalog?database={PG_DB}&schema={SCHEMA}&table={t}")


# ═══ 一、APPEND 写到一半失败 ══════════════════════════════════════════
print(f"\n【一】APPEND + 重试 {MAX_ATTEMPTS} 次,第 {BAD_ID} 行触发 CHECK 失败")
print(f"       源表 {SRC_ROWS} 行,批次 {BATCH} —— 每次尝试提交前几批后整批回滚")

job = make_job(f"P8-APPEND-{RUN}", dst_append, "APPEND", MAX_ATTEMPTS)
execution = call("POST", f"/jobs/{job}/run")["data"]["id"]
detail = wait_terminal(execution)
exec_view = detail["execution"]
attempts = detail.get("attempts") or []

check("执行最终失败(CHECK 约束确实拦住了)",
      exec_view["status"] == "FAILED", exec_view["status"])

total = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst_append}"))
distinct = int(psql(f"SELECT count(DISTINCT id) FROM {SCHEMA}.{dst_append}"))
print(f"       尝试 {exec_view.get('attemptCount')} 次,"
      f"目标表 {total} 行 / {distinct} 个不同 id")
for a in attempts:
    print(f"         第{a.get('attemptNo')}次: {a.get('status')} "
          f"写{a.get('rowsWritten')}行 {a.get('errorCode') or ''}")

check("目标表里没有重复行(已提交的行没有被重投再写一遍)",
      total == distinct,
      f"{total} 行但只有 {distinct} 个不同 id —— 多出的 {total - distinct} 行是重投写重的"
      if total != distinct else f"{total} 行,{distinct} 个 id,一一对应")

check("已经落盘的尝试不再重投(尝试次数停在 1)",
      exec_view.get("attemptCount") == 1,
      f"实际尝试 {exec_view.get('attemptCount')} 次")

# 失败原因要说清"为什么不重试"。缺了这句话,用户看到的是一个只试了一次就
# 放弃的任务,而他明明配了重试 3 次 —— 那看起来像平台没照配置办事。
message = exec_view.get("message") or ""
check("失败原因里说清了为什么不重试,以及目标端还有数据要清",
      "不再重试" in message and "清理目标端" in message,
      # 报错原文在前、说明在后,所以看尾巴才看得见这句
      "…" + message[-90:] if message else "(没有 message)")

check("失败的尝试也记下了它写进去多少行",
      (exec_view.get("rowsWritten") or 0) > 0,
      f"rowsWritten={exec_view.get('rowsWritten')} —— "
      f"为 0 的话,出事后没人知道目标端有多少脏数据要清")


# ═══ 二、一行都没写的失败,重试必须照旧 ════════════════════════════════
# 这一节是上一节的<b>对照组</b>。少了它,"禁止重试"这个修法看上去也能让上一节转绿 ——
# 但那是把婴儿和洗澡水一起倒了:连不上目标库、认证过期、源端超时,这些失败一行
# 都没写,重试正是为它们准备的。
print(f"\n【二】对照组:一行都没写就失败 —— 重试必须照旧发生")
print(f"       编译发布之后再把目标表删掉,于是 runner 在写第一行之前就失败")

job2 = make_job(f"P8-无写入-{RUN}", dst_clean, "APPEND", MAX_ATTEMPTS)
psql(f"DROP TABLE {SCHEMA}.{dst_clean}")
execution2 = call("POST", f"/jobs/{job2}/run")["data"]["id"]
detail2 = wait_terminal(execution2)
exec2 = detail2["execution"]

check("执行最终失败(目标表不存在)", exec2["status"] == "FAILED", exec2["status"])
check(f"重试照旧发生,尝试满 {MAX_ATTEMPTS} 次",
      exec2.get("attemptCount") == MAX_ATTEMPTS,
      f"实际尝试 {exec2.get('attemptCount')} 次 —— "
      f"少于 {MAX_ATTEMPTS} 说明修法误伤了本该重试的失败"
      if exec2.get("attemptCount") != MAX_ATTEMPTS else f"{MAX_ATTEMPTS} 次")


# ═══ 三、OVERWRITE 是幂等的,重试仍应发生 ══════════════════════════════
# 这一节决定修法是"精准"还是"一刀切"。OVERWRITE 每次开写前 DELETE 整表,
# 重投多少次目标表都是同一份数据 —— 禁掉它的重试是没有理由的损失。
print(f"\n【三】OVERWRITE 写到一半失败 —— 它开写前先清表,重投幂等,重试应照旧")

job3 = make_job(f"P8-OVERWRITE-{RUN}", dst_overwrite, "OVERWRITE", MAX_ATTEMPTS)
execution3 = call("POST", f"/jobs/{job3}/run")["data"]["id"]
detail3 = wait_terminal(execution3)
exec3 = detail3["execution"]

total3 = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst_overwrite}"))
distinct3 = int(psql(f"SELECT count(DISTINCT id) FROM {SCHEMA}.{dst_overwrite}"))
print(f"       尝试 {exec3.get('attemptCount')} 次,"
      f"目标表 {total3} 行 / {distinct3} 个不同 id")

check("执行最终失败", exec3["status"] == "FAILED", exec3["status"])
check(f"OVERWRITE 的重试没有被误伤,尝试满 {MAX_ATTEMPTS} 次",
      exec3.get("attemptCount") == MAX_ATTEMPTS,
      f"实际尝试 {exec3.get('attemptCount')} 次")
check("OVERWRITE 重投多次,目标表里依然没有重复行",
      total3 == distinct3,
      f"{total3} 行 / {distinct3} 个 id")


# ═══ 清理 ═════════════════════════════════════════════════════════════
print("\n清理…")
for job_id in created_jobs:
    call("DELETE", f"/jobs/{job_id}", raw=True)
for ds_id in created_datasources:
    call("DELETE", f"/datasources/{ds_id}", raw=True)
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{src}; "
     f"DROP TABLE IF EXISTS {SCHEMA}.{dst_append}; "
     f"DROP TABLE IF EXISTS {SCHEMA}.{dst_overwrite}; "
     f"DROP TABLE IF EXISTS {SCHEMA}.{dst_clean};")

print("\n" + "=" * 78)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P8 全部通过")
print("=" * 78)
