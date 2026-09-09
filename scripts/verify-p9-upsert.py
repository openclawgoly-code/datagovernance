#!/usr/bin/env python3
"""P9:按主键写入(UPSERT)—— 它是不是真的在更新,而不是在插重复行。

<b>为什么单独一份</b>:UPSERT 是本仓库里<b>唯一一种六个方言写法各不相同</b>的
写入模式 ——

  PostgreSQL   INSERT ... ON CONFLICT (pk) DO UPDATE SET
  MySQL        INSERT ... ON DUPLICATE KEY UPDATE
  Oracle/达梦  MERGE INTO ... USING (SELECT ? FROM dual) ... WHEN MATCHED
  SQL Server   MERGE INTO ... USING (VALUES (?)) ... WHEN MATCHED
  Doris/SR     <b>普通 INSERT</b> —— 它们没有 upsert 语法,去重靠表模型

语句生成那一半由 DialectUpsertTest 逐方言钉死(纯字符串,不需要目标端)。
这份脚本补的是另一半:<b>生成的语句拿到真库上跑,结果对不对</b>。

判据不是"任务成功",而是三件必须同时成立的事:
  一、目标表的<b>总行数</b>没有因为重复写而变多;
  二、本来就存在的行被<b>更新</b>了(不是被跳过);
  三、原本没有的行被<b>插入</b>了。
只验第一条会漏掉"UPSERT 退化成 DO NOTHING",只验第三条会漏掉"退化成 APPEND"。

<b>跨方言</b>:同一份任务配置分别打到 PostgreSQL 与 MySQL,生成的语句完全不同,
结果必须一样。这是这份脚本的核心 —— 单方言跑通证明不了方言分支写对了。
Oracle / SQL Server / 达梦在本仓库的验证环境里没有实例,只有单元测试看着,
这一点在 README 里说清楚了,不假装验过。

前置:
  应用已启动,且 DG_ADMIN_PASSWORD 与启动时一致
  MySQL 可选(./scripts/start-test-mysql.sh);没有则跨方言那一节跳过
"""
import json
import os
import shutil
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

MYSQL_HOST = os.environ.get("DG_SEED_MYSQL_HOST")
MYSQL_PORT = int(os.environ.get("DG_SEED_MYSQL_PORT", "33306"))
MYSQL_USER = os.environ.get("DG_SEED_MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("DG_SEED_MYSQL_PASSWORD", "")
MYSQL_DB = os.environ.get("DG_VERIFY_MYSQL_DB", "dg_upsert")

# 源表行数与"目标表里已经有的"那一段。两段要有交集也要有差集 ——
# 全是新行只验得到插入,全是旧行只验得到更新。
ROWS = int(os.environ.get("DG_VERIFY_UPSERT_ROWS", "1000"))
EXISTING = ROWS // 2

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


def mysql(sql, db=None):
    """口令走 MYSQL_PWD 而不是 -p 参数 —— 命令行参数在 ps 里人人可见。"""
    env = dict(os.environ, MYSQL_PWD=MYSQL_PASSWORD)
    client = "mariadb" if shutil.which("mariadb") else "mysql"
    args = [client, "-h", MYSQL_HOST, "-P", str(MYSQL_PORT), "-u", MYSQL_USER, "-N", "-B"]
    if db:
        args += ["-D", db]
    r = subprocess.run(args + ["-e", sql], capture_output=True, text=True, env=env, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"mysql 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def wait_terminal(execution_id, seconds=300):
    detail = None
    for _ in range(seconds * 2):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(0.5)
    return detail


created_jobs = []
created_datasources = []


def make_job(name, config, publish=True):
    job = call("POST", "/jobs", {
        "name": name, "jobType": "OFFLINE_SYNC", "config": config, "timeoutMs": 300000,
    })["data"]
    created_jobs.append(job["id"])
    compiled = call("POST", f"/jobs/{job['id']}/compile")["data"]
    if publish and compiled["succeeded"]:
        call("POST", f"/jobs/{job['id']}/publish")
    return job["id"], compiled


def run_to_end(job_id):
    execution = call("POST", f"/jobs/{job_id}/run")["data"]["id"]
    return wait_terminal(execution)


print("=" * 78)
print(" P9 · 按主键写入(UPSERT)—— 生成的语句拿到真库上跑,结果对不对")
print("=" * 78)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")

psql(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")
src = f"p9_src_{RUN}"
# 源表:1..ROWS,name 一律是新值 v2
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{src}; "
     f"CREATE TABLE {SCHEMA}.{src} AS "
     f"SELECT g AS id, 'v2-' || g AS name, g * 10 AS amount "
     f"FROM generate_series(1, {ROWS}) g")

pg_ds = call("POST", "/datasources", {
    "name": f"P9-PG-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})["data"]["id"]
created_datasources.append(pg_ds)
call("POST", f"/datasources/{pg_ds}/test")


def seed_pg_target(name, extra_columns="", pk="id", with_pk=True):
    """目标表:前 EXISTING 行已经存在,值是旧的 v1。"""
    constraint = f", PRIMARY KEY ({pk})" if with_pk else ""
    psql(f"DROP TABLE IF EXISTS {SCHEMA}.{name}; "
         f"CREATE TABLE {SCHEMA}.{name} ("
         f"  id int NOT NULL, name text, amount int{extra_columns}{constraint})")
    psql(f"INSERT INTO {SCHEMA}.{name} (id, name, amount) "
         f"SELECT g, 'v1-' || g, 0 FROM generate_series(1, {EXISTING}) g")
    return name


def browse(ds, database, schema, tables):
    """编译要读列结构,而模式级浏览只列表名 —— 必须逐表探一次。"""
    call("GET", f"/datasources/{ds}/catalog?database={database}&schema={schema}&refresh=true")
    for t in tables:
        call("GET", f"/datasources/{ds}/catalog?database={database}"
                    f"&schema={schema}&table={t}")


# ═══ 一、PostgreSQL:更新与插入同时发生 ══════════════════════════════
print(f"\n【一】PostgreSQL —— ON CONFLICT DO UPDATE")
print(f"       源表 {ROWS} 行(值 v2),目标表已有前 {EXISTING} 行(值 v1)")

dst = seed_pg_target(f"p9_dst_{RUN}")
browse(pg_ds, PG_DB, SCHEMA, [src, dst])

pg_config = {
    "sourceDataSourceId": pg_ds, "sourceDatabase": PG_DB,
    "sourceSchema": SCHEMA, "sourceTable": src,
    "targetDataSourceId": pg_ds, "targetDatabase": PG_DB,
    "targetSchema": SCHEMA, "targetTable": dst,
    "fieldMappings": {"id": "id", "name": "name", "amount": "amount"},
    "writeMode": "UPSERT", "primaryKeys": ["id"], "batchSize": 200,
}
job, compiled = make_job(f"P9-PG-UPSERT-{RUN}", pg_config)
check("UPSERT 任务编译通过", compiled["succeeded"], compiled["summary"])
check("PostgreSQL 目标端会提示 ON CONFLICT 依赖真实约束",
      any("ON CONFLICT" in d["message"] for d in compiled["diagnostics"]),
      next((d["message"] for d in compiled["diagnostics"] if "ON CONFLICT" in d["message"]),
           "没有这条提示"))

detail = run_to_end(job)
check("执行成功", detail["execution"]["status"] == "SUCCEEDED",
      detail["execution"].get("message") or detail["execution"]["status"])

total = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))
check(f"总行数是 {ROWS} —— 没有因为重复写而变多",
      total == ROWS, f"{total} 行(期望 {ROWS};{ROWS + EXISTING} 说明退化成了追加)")

updated = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst} "
                   f"WHERE id <= {EXISTING} AND name LIKE 'v2-%'"))
check(f"本来就存在的 {EXISTING} 行被更新了(不是被跳过)",
      updated == EXISTING,
      f"{updated}/{EXISTING} 行拿到了新值(0 说明退化成了 DO NOTHING)")

inserted = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst} WHERE id > {EXISTING}"))
check(f"原本没有的 {ROWS - EXISTING} 行被插入了",
      inserted == ROWS - EXISTING, f"{inserted} 行")

stale = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst} WHERE name LIKE 'v1-%'"))
check("目标表里一条旧值都不剩", stale == 0, f"{stale} 行还是 v1")


# ═══ 二、重跑一次,结果一模一样(幂等)═════════════════════════════════
# 这一条是 TableCopier.idempotentWriteMode() 把 UPSERT 算作幂等的依据。
# 它不成立的话,失败重投会像 APPEND 那样把已落盘的行再写一遍(见 P8)。
print(f"\n【二】同一个任务再跑一遍 —— 结果必须一模一样")

before = psql(f"SELECT md5(string_agg(id || '|' || name || '|' || amount, ',' "
              f"ORDER BY id)) FROM {SCHEMA}.{dst}")
detail2 = run_to_end(job)
after = psql(f"SELECT md5(string_agg(id || '|' || name || '|' || amount, ',' "
             f"ORDER BY id)) FROM {SCHEMA}.{dst}")
total2 = int(psql(f"SELECT count(*) FROM {SCHEMA}.{dst}"))

check("第二次执行成功", detail2["execution"]["status"] == "SUCCEEDED",
      detail2["execution"]["status"])
check("行数没变", total2 == ROWS, f"{total2} 行")
check("整表内容的校验和没变 —— 重跑是安全的,所以重试也是",
      before == after and before, f"{(before or '')[:16]} → {(after or '')[:16]}")


# ═══ 三、复合主键 ═════════════════════════════════════════════════════
print(f"\n【三】复合主键 —— 条件要用 AND 串起来,少一列就会认错行")

comp_src = f"p9_csrc_{RUN}"
comp_dst = f"p9_cdst_{RUN}"
# 两个租户 × 每个 ROWS/2 行。只按 id 匹配的话,两个租户会互相覆盖 ——
# 那正是"漏了一列"的症状,而总行数会掉一半,看得出来。
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{comp_src}; "
     f"CREATE TABLE {SCHEMA}.{comp_src} AS "
     f"SELECT t.tenant, g AS id, 'v2-' || t.tenant || '-' || g AS name "
     f"FROM generate_series(1, {ROWS // 2}) g, "
     f"     (VALUES ('t1'), ('t2')) AS t(tenant)")
psql(f"DROP TABLE IF EXISTS {SCHEMA}.{comp_dst}; "
     f"CREATE TABLE {SCHEMA}.{comp_dst} ("
     f"  tenant text NOT NULL, id int NOT NULL, name text, "
     f"  PRIMARY KEY (tenant, id))")
psql(f"INSERT INTO {SCHEMA}.{comp_dst} "
     f"SELECT 't1', g, 'v1-t1-' || g FROM generate_series(1, {EXISTING // 2}) g")

browse(pg_ds, PG_DB, SCHEMA, [comp_src, comp_dst])
comp_job, comp_compiled = make_job(f"P9-复合主键-{RUN}", {
    "sourceDataSourceId": pg_ds, "sourceDatabase": PG_DB,
    "sourceSchema": SCHEMA, "sourceTable": comp_src,
    "targetDataSourceId": pg_ds, "targetDatabase": PG_DB,
    "targetSchema": SCHEMA, "targetTable": comp_dst,
    "fieldMappings": {"tenant": "tenant", "id": "id", "name": "name"},
    "writeMode": "UPSERT", "primaryKeys": ["tenant", "id"], "batchSize": 200,
})
check("复合主键编译通过", comp_compiled["succeeded"], comp_compiled["summary"])
comp_detail = run_to_end(comp_job)
check("复合主键执行成功", comp_detail["execution"]["status"] == "SUCCEEDED",
      comp_detail["execution"].get("message") or comp_detail["execution"]["status"])

comp_total = int(psql(f"SELECT count(*) FROM {SCHEMA}.{comp_dst}"))
check(f"两个租户各 {ROWS // 2} 行,共 {ROWS} 行 —— 没有互相覆盖",
      comp_total == ROWS,
      f"{comp_total} 行(掉到 {ROWS // 2} 说明匹配条件漏了 tenant 那一列)")
comp_updated = int(psql(f"SELECT count(*) FROM {SCHEMA}.{comp_dst} "
                        f"WHERE tenant='t1' AND id <= {EXISTING // 2} AND name LIKE 'v2-%'"))
check(f"t1 租户已存在的 {EXISTING // 2} 行被更新",
      comp_updated == EXISTING // 2, f"{comp_updated} 行")


# ═══ 四、目标表没有唯一约束时,失败要响 ════════════════════════════════
# 编译期的那条 warning 说的就是这个。它若静默退化成普通插入,用户会拿到
# 一张重复行越堆越多的表 —— 那比报错坏得多。
print(f"\n【四】目标表这几列上没有唯一约束 —— 必须报错,不能静默退化成追加")

bare = f"p9_bare_{RUN}"
seed_pg_target(bare, with_pk=False)
browse(pg_ds, PG_DB, SCHEMA, [bare])
bare_job, bare_compiled = make_job(f"P9-无约束-{RUN}", {
    **pg_config, "targetTable": bare,
})
check("编译仍然通过(约束存不存在要到执行期才知道)",
      bare_compiled["succeeded"], bare_compiled["summary"])
bare_detail = run_to_end(bare_job)
bare_message = bare_detail["execution"].get("message") or ""
check("执行失败而不是静默写成追加",
      bare_detail["execution"]["status"] == "FAILED", bare_detail["execution"]["status"])
check("报错说得清是约束的问题",
      "constraint" in bare_message.lower() or "conflict" in bare_message.lower()
      or "42P10" in bare_message,
      bare_message[:120])
bare_total = int(psql(f"SELECT count(*) FROM {SCHEMA}.{bare}"))
check("一行都没写进去(整批被拒,不是写了一半)",
      bare_total == EXISTING, f"{bare_total} 行(建表时灌了 {EXISTING} 行)")


# ═══ 五、编译期的三条主键校验 ═════════════════════════════════════════
print(f"\n【五】编译期把配不出正确语句的情形挡在前面")

_, no_keys = make_job(f"P9-无主键-{RUN}", {**pg_config, "primaryKeys": []}, publish=False)
check("没填主键被拦",
      not no_keys["succeeded"]
      and any("必须指定主键" in d["message"] for d in no_keys["diagnostics"]),
      no_keys["summary"])

_, bad_key = make_job(f"P9-主键不存在-{RUN}",
                      {**pg_config, "primaryKeys": ["no_such_column"]}, publish=False)
check("主键不在目标表里被拦",
      not bad_key["succeeded"]
      and any("没有主键字段" in d["message"] for d in bad_key["diagnostics"]),
      bad_key["summary"])

# 最隐蔽的一条:主键不在映射目标端 —— 插入时它是 NULL,永远匹配不上,
# 于是 UPSERT 静默退化成 APPEND。语句合法、任务成功、数据是错的。
_, unmapped = make_job(f"P9-主键未映射-{RUN}", {
    **pg_config,
    "fieldMappings": {"name": "name", "amount": "amount"},   # 少了 id
}, publish=False)
check("主键没出现在字段映射目标端被拦(这条最隐蔽:不拦就静默退化成追加)",
      not unmapped["succeeded"]
      and any("没有出现在字段映射的目标端" in d["message"]
              for d in unmapped["diagnostics"]),
      unmapped["summary"])


# ═══ 六、跨方言:同一份配置打到 MySQL ══════════════════════════════════
# 这一节才是重点。单方言跑通证明不了方言分支写对了 —— PostgreSQL 那条路
# 走的是 ON CONFLICT,MySQL 走的是 ON DUPLICATE KEY UPDATE,两条完全不同的
# 语句必须给出同一个结果。
print(f"\n【六】跨方言 —— 同一份配置打到 MySQL,语句不同,结果必须一样")

mysql_ready = False
if not MYSQL_HOST:
    skip("MySQL 上的 UPSERT(共 4 条)",
         "没有设置 DG_SEED_MYSQL_HOST。起法见 scripts/start-test-mysql.sh")
else:
    my_src = f"p9_src_{RUN}"
    my_dst = f"p9_dst_{RUN}"
    try:
        mysql(f"CREATE DATABASE IF NOT EXISTS {MYSQL_DB}")
        mysql(f"DROP TABLE IF EXISTS {my_src}; "
              f"CREATE TABLE {my_src} (id INT, name VARCHAR(64), amount INT)", MYSQL_DB)
        # MySQL 没有 generate_series,用递归 CTE 造行
        mysql(f"INSERT INTO {my_src} (id, name, amount) "
              f"WITH RECURSIVE g(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM g WHERE n < {ROWS}) "
              f"SELECT n, CONCAT('v2-', n), n * 10 FROM g", MYSQL_DB)
        mysql(f"DROP TABLE IF EXISTS {my_dst}; "
              f"CREATE TABLE {my_dst} (id INT NOT NULL, name VARCHAR(64), amount INT, "
              f"PRIMARY KEY (id))", MYSQL_DB)
        mysql(f"INSERT INTO {my_dst} (id, name, amount) "
              f"WITH RECURSIVE g(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM g WHERE n < {EXISTING}) "
              f"SELECT n, CONCAT('v1-', n), 0 FROM g", MYSQL_DB)
        mysql_ready = True
    except RuntimeError as exc:
        # 连不上不该让整份脚本崩掉 —— 前五节验的是 PostgreSQL,与 MySQL 无关。
        # 崩在这里的话,那五节的结论也一起丢了。
        skip("MySQL 上的 UPSERT(共 4 条)", str(exc).strip()[:120])
        mysql_ready = False

if MYSQL_HOST and mysql_ready:
    my_ds = call("POST", "/datasources", {
        "name": f"P9-MySQL-{RUN}", "type": "MYSQL",
        "host": MYSQL_HOST, "port": MYSQL_PORT, "databaseName": MYSQL_DB,
        "username": MYSQL_USER,
        "inlineSecret": {"authType": "PASSWORD", "username": MYSQL_USER,
                         "secret": MYSQL_PASSWORD},
    })["data"]["id"]
    created_datasources.append(my_ds)
    probe = call("POST", f"/datasources/{my_ds}/test")["data"]
    if not probe.get("success"):
        skip("MySQL 上的 UPSERT(共 4 条)", f"连不上: {probe.get('message')}")
    else:
        # MySQL 只有库这一层,没有 schema
        call("GET", f"/datasources/{my_ds}/catalog?database={MYSQL_DB}&refresh=true")
        for t in (my_src, my_dst):
            call("GET", f"/datasources/{my_ds}/catalog?database={MYSQL_DB}&table={t}")

        my_job, my_compiled = make_job(f"P9-MySQL-UPSERT-{RUN}", {
            "sourceDataSourceId": my_ds, "sourceDatabase": MYSQL_DB, "sourceTable": my_src,
            "targetDataSourceId": my_ds, "targetDatabase": MYSQL_DB, "targetTable": my_dst,
            "fieldMappings": {"id": "id", "name": "name", "amount": "amount"},
            "writeMode": "UPSERT", "primaryKeys": ["id"], "batchSize": 200,
        })
        check("MySQL 上的 UPSERT 任务编译通过", my_compiled["succeeded"],
              my_compiled["summary"])
        my_detail = run_to_end(my_job)
        check("MySQL 上执行成功", my_detail["execution"]["status"] == "SUCCEEDED",
              my_detail["execution"].get("message") or my_detail["execution"]["status"])

        my_total = int(mysql(f"SELECT count(*) FROM {my_dst}", MYSQL_DB) or 0)
        check(f"MySQL 目标表总行数是 {ROWS}(与 PostgreSQL 一致)",
              my_total == ROWS, f"{my_total} 行")
        my_updated = int(mysql(f"SELECT count(*) FROM {my_dst} "
                               f"WHERE id <= {EXISTING} AND name LIKE 'v2-%'", MYSQL_DB) or 0)
        check(f"MySQL 上已存在的 {EXISTING} 行同样被更新(ON DUPLICATE KEY UPDATE 生效)",
              my_updated == EXISTING, f"{my_updated}/{EXISTING} 行")


# ═══ 清理 ═════════════════════════════════════════════════════════════
print("\n清理…")
for job_id in created_jobs:
    call("DELETE", f"/jobs/{job_id}", raw=True)
for ds_id in created_datasources:
    call("DELETE", f"/datasources/{ds_id}", raw=True)
psql(f"DO $$ DECLARE r record; BEGIN "
     f"FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='{SCHEMA}' "
     f"AND tablename LIKE 'p9\\_%{RUN}' LOOP "
     f"EXECUTE 'DROP TABLE {SCHEMA}.' || quote_ident(r.tablename); END LOOP; END $$;")
if MYSQL_HOST:
    try:
        mysql(f"DROP DATABASE IF EXISTS {MYSQL_DB}")
    except RuntimeError:
        pass

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
print(" 结果:P9 全部通过")
print("=" * 78)
