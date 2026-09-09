#!/usr/bin/env python3
"""P7:并发与规模 —— 前六份脚本都是单任务、小数据量,这里补上另一半。

<b>为什么这是最后一块真空</b>:verify-p1..p6 里每个任务都是提交一个、等它跑完、
再提交下一个。那样的验证证明不了两件事 ——

  一、并发下执行事实还对不对。Runtime 的线程池默认 core=2 / max=8 /
      队列 64,提交 12 个任务只有 2 个真在跑,其余排队。排队中的执行报什么
      状态、指标会不会写串到别人的记录上,从来没被检查过。
  二、取消在真实数据量下是否真的生效。全仓<b>只有一处</b>取消调用
      (verify-p3 的工作流级联),它验的是级联与终态,没验目标库有没有
      真的停止增长,也没在状态落定后<b>再查一次</b>。

第二条尤其要紧,因为它正是当初那个缺陷的场景:进度回调整行写回,把已经
落到 CANCELING 的状态又刷成 RUNNING。小数据量下回调根本没机会与取消并发,
所以那个缺陷是靠读代码发现的,不是被测出来的。

<b>并发下"指标写串"怎么测</b>:让每个任务搬<b>不同的行数</b>。12 个任务分别搬
2000、4000…24000 行,于是"第 i 条执行记录的 rowsWritten 是不是 i*2000"这个
问题有唯一答案 —— 串了立刻看得见。所有任务共用同一张源表,只靠 whereClause
切,这样源端的差异不会引入别的变量。

行数不能太小:几十行的任务几十毫秒就跑完,12 个串着跑也不会重叠,那验证的是
"跑得快"而不是"并发对"。

本脚本自己造源表,不依赖公开数据集 —— 它验的是并发,不该因为没灌过 Pagila 就跑不了。

前置:
  应用已启动,且 DG_ADMIN_PASSWORD 与启动时一致
"""
import json
import os
import subprocess
import sys
import threading
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

# 并发任务数。要明显超过默认 corePoolSize=2,才能把"排队"这条路径走到。
CONCURRENCY = int(os.environ.get("DG_VERIFY_CONCURRENCY", "12"))
ROWS_PER_STEP = int(os.environ.get("DG_VERIFY_ROWS_STEP", "2000"))

# 取消测试用的源表行数。要大到"取消时它还没跑完",小到别把这个脚本拖太久。
CANCEL_ROWS = int(os.environ.get("DG_VERIFY_CANCEL_ROWS", "300000"))

# 线程池容量。声明了才跑过载那一节 —— 默认配置下要 73 个任务才触发拒绝,
# 为此建 73 个任务定义不划算。用小池子重启应用再跑那一节更省事,
# 见本节的 SKIP 提示。
POOL_LIMIT = os.environ.get("DG_VERIFY_POOL_LIMIT")

if not ADMIN_PASSWORD:
    sys.exit("请设置 DG_ADMIN_PASSWORD(应用启动时用的那个管理员口令)")

token = None
workspace = None
failures = []
skipped = []


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


def skip(label, why):
    print(f"  [SKIP] {label}  — {why}")
    skipped.append(f"{label}({why})")


def psql(sql, db=PG_DB):
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    r = subprocess.run(["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER,
                        "-d", db, "-tAc", sql],
                       capture_output=True, text=True, env=env, timeout=300)
    if r.returncode != 0:
        raise RuntimeError(f"psql 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def make_pg_datasource(name, database):
    res = call("POST", "/datasources", {
        "name": name, "type": "POSTGRESQL",
        "host": PG_HOST, "port": PG_PORT, "databaseName": database, "username": PG_USER,
        "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
    })
    ds_id = res["data"]["id"]
    call("POST", f"/datasources/{ds_id}/test")
    return ds_id


def wait_terminal(execution_id, seconds=300):
    detail = None
    for _ in range(seconds * 2):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(0.5)
    return detail


print("=" * 78)
print(" P7 · 并发与规模 —— 执行事实在并发下的完整性 / 取消在真实数据量下的行为")
print("=" * 78)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")

created_jobs = []
created_datasources = []
SCHEMA = "dg_probe_schema"
psql(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")

probe_ds = make_pg_datasource(f"P7-探测目标-{RUN}", PG_DB)
created_datasources.append(probe_ds)


# ═══ 一、并发下的执行事实完整性 ══════════════════════════════════════
# 这一节和第三节要的是<b>相反的池子配置</b>:这里要池子装得下,那里要池子被打穿。
# 同一次运行里两者不可兼得,所以按 DG_VERIFY_POOL_LIMIT 二选一 ——
# 硬凑在一起的话,小池子下这一节会有一半任务被拒,红成一片却什么缺陷都没有。
print(f"\n【一】{CONCURRENCY} 个同步任务并发提交 —— 执行事实会不会串")
print(f"       线程池默认 core=2 / max=8,所以大部分任务会排队。")
print(f"       每个任务搬不同的行数({ROWS_PER_STEP}…{CONCURRENCY * ROWS_PER_STEP}),"
      f"串了立刻看得见。")

targets = {}
if not POOL_LIMIT:
    conc_src = f"p7_conc_src_{RUN}"
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{conc_src}; "
         f"CREATE TABLE {SCHEMA}.{conc_src} AS "
         f"SELECT g AS id, 'title-' || g AS title, md5(g::text) AS digest "
         f"FROM generate_series(1, {CONCURRENCY * ROWS_PER_STEP}) g")

    if POOL_LIMIT:
        skip(f"{CONCURRENCY} 个任务并发下的执行事实完整性(共 8 条)",
             "本次以小池子运行(DG_VERIFY_POOL_LIMIT 已设),池子装不下这么多任务。"
             "这一节请用默认配置单独跑一遍")
    for i in (range(1, CONCURRENCY + 1) if not POOL_LIMIT else []):
        table = f"p7_conc_{RUN}_{i}"
        psql(f"DROP TABLE IF EXISTS {SCHEMA}.{table}; "
             f"CREATE TABLE {SCHEMA}.{table} (id int, title text, digest text)")
        targets[i] = table
    # 编译要读源表与目标表的<b>列</b>结构,而模式级浏览只列表名不带列 ——
    # 必须逐表探一次。不探的话编译期就以「读不到表结构」失败,
    # 那验证的是目录缓存而不是并发。
    call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}&schema={SCHEMA}&refresh=true")
    for table in list(targets.values()) + [conc_src]:
        call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}"
                    f"&schema={SCHEMA}&table={table}")

    jobs = {}
    for i in range(1, CONCURRENCY + 1):
        expected = i * ROWS_PER_STEP
        job = call("POST", "/jobs", {
            "name": f"P7-并发-{RUN}-{i:02d}", "jobType": "OFFLINE_SYNC",
            "description": f"期望搬 {expected} 行",
            "config": {
                "sourceDataSourceId": probe_ds, "sourceDatabase": PG_DB,
                "sourceSchema": SCHEMA, "sourceTable": conc_src,
                "targetDataSourceId": probe_ds, "targetDatabase": PG_DB,
                "targetSchema": SCHEMA, "targetTable": targets[i],
                "fieldMappings": {"id": "id", "title": "title", "digest": "digest"},
                "whereClause": f"id <= {expected}",
                "writeMode": "APPEND", "batchSize": 500,
            },
            "timeoutMs": 300000,
        })["data"]
        created_jobs.append(job["id"])
        compiled = call("POST", f"/jobs/{job['id']}/compile")["data"]
        if not compiled["succeeded"]:
            sys.exit(f"任务 {i} 编译失败:{compiled['summary']}")
        call("POST", f"/jobs/{job['id']}/publish")
        jobs[i] = job["id"]

    # 真并发提交:12 个线程同时打 /run。串行提交测不到竞争 ——
    # 而竞争恰恰发生在"同一时刻多个执行记录被创建、多个进度回调同时写库"这一瞬间。
    submitted = {}
    submit_errors = []
    barrier = threading.Barrier(CONCURRENCY)


    def submit(index):
        try:
            barrier.wait(timeout=30)          # 对齐起跑线,让提交真的同时发生
            submitted[index] = call("POST", f"/jobs/{jobs[index]}/run")["data"]["id"]
        except Exception as exc:
            submit_errors.append(f"{index}: {exc}")


    threads = [threading.Thread(target=submit, args=(i,)) for i in range(1, CONCURRENCY + 1)]
    started_at = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=120)

    check(f"{CONCURRENCY} 个任务全部提交成功",
          len(submitted) == CONCURRENCY and not submit_errors,
          f"{len(submitted)} 个,错误 {submit_errors[:2]}" if submit_errors
          else f"{len(submitted)} 个,耗时 {time.time() - started_at:.1f}s")

    check("每个任务拿到互不相同的执行 ID(没有共用同一条记录)",
          len(set(submitted.values())) == len(submitted),
          f"{len(set(submitted.values()))} 个不同 ID")

    # 排队期间抽样只作观察,<b>不作断言</b>:任务跑得快时可能一次都采不到
    # RUNNING,那不是缺陷而是采样时机。真正的判据在下面,用持久化的时间戳算。
    max_running_seen = 0
    for _ in range(20):
        statuses = [call("GET", f"/executions/{e}")["data"]["execution"]["status"]
                    for e in submitted.values()]
        max_running_seen = max(max_running_seen, statuses.count("RUNNING"))
        if all(s in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT") for s in statuses):
            break
        time.sleep(1)
    print(f"       轮询期间观察到的并发运行峰值:{max_running_seen}(仅供参考,不作判据)")

    finals = {i: wait_terminal(e, 300) for i, e in submitted.items()}
    succeeded = [i for i, d in finals.items() if d["execution"]["status"] == "SUCCEEDED"]
    check(f"{CONCURRENCY} 个执行全部成功", len(succeeded) == CONCURRENCY,
          f"{len(succeeded)}/{CONCURRENCY} 成功" +
          ("" if len(succeeded) == CONCURRENCY else
           f",失败的: {[(i, finals[i]['execution']['status']) for i in finals if i not in succeeded][:3]}"))

    # ── 这两条是本节的核心 ────────────────────────────────────────────────
    metric_mismatch = {}
    for i, detail in finals.items():
        expected = i * ROWS_PER_STEP
        written = detail["execution"].get("rowsWritten")
        if written != expected:
            metric_mismatch[i] = (expected, written)
    check("每条执行记录的 rowsWritten 是它自己的行数(指标没写串到别人记录上)",
          not metric_mismatch,
          "; ".join(f"任务{i}: 期望{a} 实得{b}" for i, (a, b) in list(metric_mismatch.items())[:4])
          or f"{CONCURRENCY} 条全对({ROWS_PER_STEP}…{CONCURRENCY * ROWS_PER_STEP} 行)")

    data_mismatch = {}
    for i, table in targets.items():
        expected = i * ROWS_PER_STEP
        actual = int(psql(f"SELECT count(*) FROM {SCHEMA}.{table}"))
        if actual != expected:
            data_mismatch[i] = (expected, actual)
    check("每张目标表的实际行数是它自己的行数(数据没搬串)",
          not data_mismatch,
          "; ".join(f"任务{i}: 期望{a} 实得{b}" for i, (a, b) in list(data_mismatch.items())[:4])
          or f"{CONCURRENCY} 张表全对")

    # ── 并发上限:从持久化的时间戳算,不靠采样 ────────────────────────────
    # 每条执行记录都存了 startedAt / finishedAt。把这些区间摊平成事件点扫一遍,
    # 就得到"任意时刻同时在跑几个"的确切上界 —— 这是执行事实表自己的证词,
    # 不受轮询时机影响,跑一万次结果都一样。
    #
    # 它超过 maxPoolSize 只有两种可能:线程池没管住并发,或者执行记录的
    # 时间戳是错的。两种都是缺陷,而且都会让「任务监控」页面上的数字失去意义。
    def parse_ts(value):
        """解析到<b>亚秒</b>。

        截断到秒会让每个几十毫秒的任务退化成零长度区间,重叠峰值恒等于 1 ——
        断言照样是绿的,却什么都没验到。这条注释是买来的:第一版就这么写的。
        """
        if not value:
            return None
        from datetime import datetime
        text = value.replace("Z", "")
        for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
            try:
                return datetime.strptime(text, fmt).timestamp()
            except ValueError:
                continue
        return None


    events = []
    missing_ts = []
    for i, detail in finals.items():
        ex = detail["execution"]
        start, end = parse_ts(ex.get("startedAt")), parse_ts(ex.get("finishedAt"))
        if start is None or end is None:
            missing_ts.append(i)
            continue
        events.append((start, 1))
        events.append((end, -1))

    check("每条成功的执行都记了开始与结束时间(没有时间戳缺失的记录)",
          not missing_ts, f"缺时间戳的任务: {missing_ts}" if missing_ts else f"{len(finals)} 条齐全")

    # 同一时刻先 +1 再 -1:低估重叠会让上界断言失去意义,宁可高估
    events.sort(key=lambda e: (e[0], -e[1]))
    concurrent = peak = 0
    for _, delta in events:
        concurrent += delta
        peak = max(peak, concurrent)

    check("按执行记录的时间戳复算,同时在跑的执行数没有超过线程池上限",
          peak <= 8, f"区间重叠峰值 {peak}(maxPoolSize=8)")

    # 12 个任务、池子只有 8 —— 一定有任务是排队等出来的,不可能全程重叠。
    # 这条反过来证明"排队"这条路径真的被走到了,而不是池子被悄悄放大了。
    check("确实发生了排队(并非 12 个全程并行)", peak < CONCURRENCY,
          f"峰值 {peak} < 提交数 {CONCURRENCY}")

    # 执行记录是审计与监控的唯一事实来源(架构约束 R4),并发下一条都不能少
    listed = call("GET", f"/executions?page=1&size=200")["data"]
    rows = listed.get("records", listed) if isinstance(listed, dict) else listed
    listed_ids = {r["id"] for r in rows} if isinstance(rows, list) else set()
    check("并发产生的执行记录全部能在列表接口查到(R4 事实表没丢记录)",
          set(submitted.values()) <= listed_ids,
          f"{len(set(submitted.values()) & listed_ids)}/{CONCURRENCY} 条可查")



# ═══ 二、真实数据量下的取消 ══════════════════════════════════════════
print(f"\n【二】{CANCEL_ROWS:,} 行的同步跑到一半取消 —— 目标库真的停,状态不回弹")

src = f"p7_cancel_src_{RUN}"
dst = f"p7_cancel_dst_{RUN}"
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{src}; DROP TABLE IF EXISTS {SCHEMA}.{dst};")
psql(f"CREATE TABLE {SCHEMA}.{src} AS "
     f"SELECT g AS id, 'payload-' || g AS payload, md5(g::text) AS digest "
     f"FROM generate_series(1, {CANCEL_ROWS}) g")
psql(f"CREATE TABLE {SCHEMA}.{dst} (id int, payload text, digest text)")
call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}&schema={SCHEMA}&refresh=true")
for table in (src, dst):
    call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}"
                f"&schema={SCHEMA}&table={table}")

slow = call("POST", "/jobs", {
    "name": f"P7-可取消的大同步-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": probe_ds, "sourceDatabase": PG_DB,
        "sourceSchema": SCHEMA, "sourceTable": src,
        "targetDataSourceId": probe_ds, "targetDatabase": PG_DB,
        "targetSchema": SCHEMA, "targetTable": dst,
        "fieldMappings": {"id": "id", "payload": "payload", "digest": "digest"},
        # 批次刻意调小:提交得越频繁,取消旗与进度回调的交错就越密集,
        # 也就越接近当初那个缺陷真正会发作的条件
        "writeMode": "APPEND", "batchSize": 200,
    },
    "timeoutMs": 600000,
})["data"]
created_jobs.append(slow["id"])
compiled = call("POST", f"/jobs/{slow['id']}/compile")["data"]
if not check("大数据量同步编译通过", compiled["succeeded"], compiled["summary"]):
    # 编译没过就没有可取消的执行,后面每一条断言都会以 KeyError 的形式
    # 崩掉 —— 那种失败信息对排查毫无帮助。直接说清楚再退出。
    print("\n  编译失败,取消一节无法进行。诊断:")
    for d in compiled.get("diagnostics", [])[:5]:
        print(f"    {d}")
    sys.exit(1)
call("POST", f"/jobs/{slow['id']}/publish")

big_exec = call("POST", f"/jobs/{slow['id']}/run")["data"]["id"]

# 等它真的开始搬,再取消 —— 取消一个还没开始的执行,验证不了任何东西
rows_before_cancel = 0
for _ in range(120):
    rows_before_cancel = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))
    status = call("GET", f"/executions/{big_exec}")["data"]["execution"]["status"]
    if status == "RUNNING" and rows_before_cancel > 1000:
        break
    time.sleep(0.5)
check("同步已经真的跑起来并在写入", rows_before_cancel > 1000,
      f"取消前已写入 {rows_before_cancel:,} 行")

call("POST", f"/executions/{big_exec}/cancel")
rows_at_cancel = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))

detail = wait_terminal(big_exec, 120)
final_status = detail["execution"]["status"]
check("执行落到已取消", final_status == "CANCELED", final_status)

# 目标库真的停止增长。<b>这是取消唯一算数的判据</b> —— 状态标成 CANCELED
# 而数据还在流进目标库,是最坏的一种"取消成功"。
time.sleep(4)
rows_settled = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))
time.sleep(3)
rows_later = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))
check("取消后目标库停止增长(隔 3 秒两次采样行数不变)",
      rows_settled == rows_later,
      f"{rows_settled:,} → {rows_later:,}")

check("目标表行数少于源表(是真的中途停了,不是跑完才标记取消)",
      rows_later < CANCEL_ROWS,
      f"{rows_later:,} / {CANCEL_ROWS:,} 行")

# ── 状态不回弹 ────────────────────────────────────────────────────────
# 这条断言有来历:进度回调曾经整行写回执行记录,把已经落到 CANCELING 的
# 状态又刷成 RUNNING —— t0 回调读出 execution,t1 用户取消并提交,
# t2 回调把整行写回去。任务照常跑完,而界面上"取消中"闪一下就没了。
# 那个缺陷是靠读代码发现的,从没被测出来过;它只会在真实数据量下发作,
# 因为小数据量里回调根本没机会与取消并发。
time.sleep(6)
after = call("GET", f"/executions/{big_exec}")["data"]["execution"]
check("状态落定 10 秒后仍然是已取消(进度回调没把它刷回运行中)",
      after["status"] == "CANCELED",
      f"现在是 {after['status']}")

check("取消后不再有新的重试尝试",
      after.get("attemptCount", 1) <= 1,
      f"尝试 {after.get('attemptCount')} 次")


# ═══ 三、线程池过载不静默 ════════════════════════════════════════════
print("\n【三】线程池打满时的行为 —— 过载必须是明确的拒绝,不是静默丢失")

if not POOL_LIMIT:
    skip("过载被记成 DispatchRejected 而不是静默丢失",
         "默认配置(max=8 + 队列 64)要 73 个任务才触发拒绝。"
         "用小池子重启应用再跑这一节:"
         "--dg.runtime.max-pool-size=1 --dg.runtime.queue-capacity=1 "
         "并设 DG_VERIFY_POOL_LIMIT=2")
else:
    limit = int(POOL_LIMIT)
    burst = limit + 6
    print(f"       声明的池子容量 {limit},提交 {burst} 个慢任务把它打穿")

    burst_targets = []
    burst_jobs = []
    for i in range(burst):
        t = f"p7_burst_{RUN}_{i}"
        psql(f"DROP TABLE IF EXISTS {SCHEMA}.{t}; "
             f"CREATE TABLE {SCHEMA}.{t} (id int, payload text, digest text)")
        burst_targets.append(t)
    call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}&schema={SCHEMA}&refresh=true")
    # 逐表探列结构。只做模式级浏览的话编译会以「读不到目标表结构」失败,
    # 而失败的任务发布不了、跑不起来 —— 于是这一节会以"0 条被拒"收场,
    # 看起来像平台没拒绝,实际是根本没提交上去。
    for t in burst_targets:
        call("GET", f"/datasources/{probe_ds}/catalog?database={PG_DB}"
                    f"&schema={SCHEMA}&table={t}")

    for i, t in enumerate(burst_targets):
        job = call("POST", "/jobs", {
            "name": f"P7-过载-{RUN}-{i:02d}", "jobType": "OFFLINE_SYNC",
            "config": {
                "sourceDataSourceId": probe_ds, "sourceDatabase": PG_DB,
                "sourceSchema": SCHEMA, "sourceTable": src,
                "targetDataSourceId": probe_ds, "targetDatabase": PG_DB,
                "targetSchema": SCHEMA, "targetTable": t,
                "fieldMappings": {"id": "id", "payload": "payload", "digest": "digest"},
                "writeMode": "APPEND", "batchSize": 200,
            },
            "timeoutMs": 600000,
        })["data"]
        created_jobs.append(job["id"])
        c = call("POST", f"/jobs/{job['id']}/compile")["data"]
        if not c["succeeded"]:
            sys.exit(f"过载用的任务 {i} 编译失败:{c['summary']}")
        call("POST", f"/jobs/{job['id']}/publish")
        burst_jobs.append(job["id"])

    burst_execs = []
    http_refused = 0
    for jid in burst_jobs:
        status, payload = call("POST", f"/jobs/{jid}/run", raw=True)
        if status == 200:
            burst_execs.append(payload["data"]["id"])
        else:
            # 在 HTTP 层就被挡下也算"明确反馈",但要单独计数 ——
            # 它与"建了执行记录再标记被拒"是两种不同的行为
            http_refused += 1
    if http_refused:
        print(f"       另有 {http_refused} 次提交在 HTTP 层被拒(未建执行记录)")

    burst_finals = [wait_terminal(e, 300)["execution"] for e in burst_execs]
    rejected = [e for e in burst_finals
                if e.get("errorCode") == "RTM_DISPATCH_REJECTED"]

    check("池子打穿后出现明确的下发拒绝,而不是静默丢弃",
          len(rejected) > 0,
          f"{len(rejected)}/{len(burst_finals)} 条被拒")
    check("被拒的执行落在失败终态(有记录可查,不是凭空消失)",
          all(e["status"] == "FAILED" for e in rejected),
          str(sorted({e["status"] for e in rejected})) if rejected else "无")
    check("每一个提交出去的任务都有终态,一个都没丢",
          len(burst_finals) == len(burst_execs) and
          all(e["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT")
              for e in burst_finals),
          f"{len(burst_finals)} 条全部有终态")

    for e in burst_execs:
        call("POST", f"/executions/{e}/cancel", raw=True)


# ═══ 清理 ═════════════════════════════════════════════════════════════
print("\n清理…")
for job_id in created_jobs:
    call("DELETE", f"/jobs/{job_id}", raw=True)
for ds_id in created_datasources:
    call("DELETE", f"/datasources/{ds_id}", raw=True)
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{src}; DROP TABLE IF EXISTS {SCHEMA}.{dst};")
if not POOL_LIMIT:
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{conc_src}")
for t in targets.values():
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{t}")
psql(f"DO $$ DECLARE r record; BEGIN "
     f"FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='{SCHEMA}' "
     f"AND tablename LIKE 'p7_burst_{RUN}%' LOOP "
     f"EXECUTE 'DROP TABLE {SCHEMA}.' || quote_ident(r.tablename); END LOOP; END $$;")

print("\n" + "=" * 78)
if skipped:
    print(f" 跳过 {len(skipped)} 项:")
    for s in skipped:
        print(f"   - {s}")
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P7 全部通过")
print("=" * 78)
