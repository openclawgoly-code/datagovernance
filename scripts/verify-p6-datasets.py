#!/usr/bin/env python3
"""拿公开数据集验证平台 —— verify-p1..p5 验证不到的那一半。

<b>这个脚本和前五个的区别</b>:前五个脚本的数据是它们自己造的 —— 表是现建的,
列是挑好的,编码一律 UTF-8,没有分区、没有自定义类型、没有中文列名。那些脚本
能证明"功能通不通",证明不了"遇到真实的库会不会塌"。结构探测、类型映射、
整库迁移、文件解析这四件事,恰恰只有真实的库才能证伪。

素材由 scripts/seed-public-datasets.sh 准备:

  Pagila      15 张表 + 55 个分区 + 8 个视图,含 ENUM / DOMAIN / text[] /
              tsvector / vector(pgvector) / 生成列。DVD 租赁模型,PostgreSQL 官方
              示例库的社区移植版。
  Chinook     数字音乐商店,11 张表。官方同时提供 PG / MySQL / Oracle / SQLServer
              四套脚本 —— 这是整库迁移唯一能有"参照答案"的形态。
  健康样本    中文表头 + GBK 编码 + 合成身份证号,走本地只读 FTP。

<b>断言的取舍</b>:这里只写"错了就一定是缺陷"的断言。像"分区数正好等于 55"
这种跟着上游数据集走的数字不写死,写死了下次上游加一个月的分区就变成假红。

前置:
  ./scripts/seed-public-datasets.sh          # 下载 + 灌库 + 备素材
  ./scripts/seed-public-datasets.sh ftp start
  应用已启动,且 DG_ADMIN_PASSWORD 与启动时一致
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

DB_PAGILA = "dg_pagila"
DB_CHINOOK = "dg_chinook"
DB_TARGET = "dg_migrate_target"

# MySQL 是可选的:没有它,跨方言迁移那一节整段跳过并说明原因。
# 起法见 scripts/start-test-mysql.sh
MYSQL_HOST = os.environ.get("DG_SEED_MYSQL_HOST")
MYSQL_PORT = int(os.environ.get("DG_SEED_MYSQL_PORT", "33306"))
MYSQL_USER = os.environ.get("DG_SEED_MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("DG_SEED_MYSQL_PASSWORD", "")
MYSQL_DB = os.environ.get("DG_SEED_MYSQL_DB", "dg_chinook")

FTP_HOST = os.environ.get("DG_SEED_FTP_HOST", "127.0.0.1")
FTP_PORT = int(os.environ.get("DG_SEED_FTP_PORT", "2121"))
FTP_USER = os.environ.get("DG_SEED_FTP_USER", "dgseed")
FTP_PASSWORD = os.environ.get("DG_SEED_FTP_PASSWORD", "dgseed")

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


def psql(sql, db):
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    r = subprocess.run(["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER,
                        "-d", db, "-tAc", sql],
                       capture_output=True, text=True, env=env, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"psql 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def mysql(sql):
    """对测试 MySQL 执行一条查询,返回制表符分隔的裸结果。

    口令走 MYSQL_PWD 环境变量而不是 -p 参数 —— 命令行参数在 ps 里人人可见。
    """
    env = dict(os.environ, MYSQL_PWD=MYSQL_PASSWORD)
    client = "mariadb" if shutil.which("mariadb") else "mysql"
    r = subprocess.run([client, "-h", MYSQL_HOST, "-P", str(MYSQL_PORT),
                        "-u", MYSQL_USER, "-N", "-B", "-e", sql],
                       capture_output=True, text=True, env=env, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"mysql 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def db_ready(db):
    try:
        return int(psql("SELECT count(*) FROM information_schema.tables "
                        "WHERE table_schema='public'", db)) > 0
    except Exception:
        return False


def make_pg_datasource(name, database):
    res = call("POST", "/datasources", {
        "name": name, "type": "POSTGRESQL",
        "host": PG_HOST, "port": PG_PORT, "databaseName": database, "username": PG_USER,
        "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
    })
    ds_id = res["data"]["id"]
    call("POST", f"/datasources/{ds_id}/test")
    return ds_id


def rows_as_dicts(result):
    """把 SqlQuery.Result 的 {columns:[...], rows:[[...]]} 摊成字典列表。

    结果是<b>按列名索引的位置数组</b>而不是字典 —— 这是对的:SQL 允许同名列
    (SELECT a.id, b.id),字典会悄悄丢掉一列。断言里要按名字取值,所以在这儿转一次。
    """
    names = [c["name"] for c in result.get("columns", [])]
    return [dict(zip(names, row)) for row in result.get("rows", [])]


def wait_terminal(execution_id, seconds=300):
    detail = None
    for _ in range(seconds):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(1)
    return detail


def run_job(job_id, seconds=300):
    execution = call("POST", f"/jobs/{job_id}/run")["data"]
    return wait_terminal(execution["id"], seconds)


print("=" * 78)
print(" P6 · 公开数据集验证 —— 结构探测 / 类型保真 / 整库迁移 / 文件解析")
print("=" * 78)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"].get("currentWorkspaceId") or res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,当前空间 {workspace}")

for db in (DB_PAGILA, DB_CHINOOK):
    if not db_ready(db):
        sys.exit(f"数据集 {db} 没准备好。先跑:./scripts/seed-public-datasets.sh")

created_datasources = []
created_jobs = []
created_rules = []


# ═══ 一、结构探测遇上真实的库(Pagila)═══════════════════════════════
print("\n【一】结构探测(功能 5/6)—— Pagila:15 张表藏在 55 个分区里")

pagila_ds = make_pg_datasource(f"P6-Pagila-{RUN}", DB_PAGILA)
created_datasources.append(pagila_ds)

detail = call("GET", f"/datasources/{pagila_ds}")["data"]
check("Pagila 数据源可用", detail["status"] == "AVAILABLE", detail["status"])

page = call("GET", f"/datasources/{pagila_ds}/catalog?database={DB_PAGILA}&schema=public"
                   "&refresh=true", timeout=180)["data"]
tables = page["tables"]
names = [t["name"] for t in tables]

# 真实库里到底有什么,直接问库,不问平台 —— 平台的答案正是被验证的对象
top_level = int(psql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                     " WHERE n.nspname='public' AND c.relkind IN ('r','p')"
                     " AND NOT c.relispartition", DB_PAGILA))
# relkind 限定成 r/p:relispartition 对分区索引也成立(Pagila 里有 110 个),
# 把索引算进"分区表"会让下面那条泄漏断言的分母虚高一倍多
partitions = int(psql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                      " WHERE n.nspname='public' AND c.relispartition"
                      " AND c.relkind IN ('r','p')", DB_PAGILA))
views = int(psql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                 " WHERE n.nspname='public' AND c.relkind IN ('v','m')", DB_PAGILA))
print(f"       库里实际有:顶层表 {top_level} · 分区 {partitions} · 视图 {views}")
print(f"       平台返回了 {len(tables)} 条")

# 分区是父表的存储细节,不是用户要浏览的对象。它们出现在表清单里,等于把
# 用户真正要找的 15 张表埋进几十条噪音里 —— 而且分区名带年月,列表按名字
# 排序后,payment 的分区会把 p 开头的真实表挤到几屏之外。
partition_names = set(psql(
    "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
    " WHERE n.nspname='public' AND c.relispartition AND c.relkind IN ('r','p')",
    DB_PAGILA).splitlines())
leaked = sorted(set(names) & partition_names)
check("分区不出现在表清单里(它们是父表的存储细节,不是可浏览对象)",
      len(leaked) == 0,
      f"泄漏 {len(leaked)}/{partitions} 个,如 {', '.join(leaked[:3])}" if leaked else "干净")

# 比"分区泄漏"更严重的一半:父表本身不见了。用户既看不到完整的 payment,
# 又要在 55 个月度碎片里找路 —— 这是恰好反过来的行为
check("分区父表本身出现在清单里", "payment" in names,
      "payment" if "payment" in names else "缺失")
check("视图与表都能列出", views > 0 and len(names) >= top_level,
      f"清单 {len(names)} 条 ≥ 顶层 {top_level}")


# ═══ 二、类型保真 ═════════════════════════════════════════════════════
print("\n【二】类型映射(风险 R7)—— rawType 是排查错误映射时唯一的证据")

film = call("GET", f"/datasources/{pagila_ds}/catalog?database={DB_PAGILA}"
                   "&schema=public&table=film")["data"]
cols = {c["name"]: c for c in film["columns"]}
print(f"       film 表 {len(cols)} 列")

# 每一条都是"平台没见过的类型"。要求不是"映射得多聪明",而是<b>原始类型名
# 必须原样留着</b> —— 映射错了以后,只有 rawType 能告诉排查的人源端本来是什么。
for column, expect_raw in [("rating", "mpaa_rating"),      # 自定义 ENUM
                           ("special_features", "_text"),   # text[] 数组
                           ("fulltext", "tsvector")]:       # 全文索引列
    c = cols.get(column)
    raw = (c or {}).get("rawType", "")
    check(f"film.{column} 的 rawType 保留源端原名",
          c is not None and expect_raw in raw,
          f"rawType={raw!r} canonical={(c or {}).get('canonicalType')}" if c else "列不存在")

# 未知类型必须有个归宿。落成 null 的话,前端画结构树时会渲染成空白格,
# 用户看到的是"这列没有类型",而不是"平台不认识这个类型"
unknown_typed = [n for n, c in cols.items() if not c.get("canonicalType")]
check("没有任何一列的 canonicalType 为空",
      not unknown_typed, ", ".join(unknown_typed) or "全部有归类")

emb = call("GET", f"/datasources/{pagila_ds}/catalog?database={DB_PAGILA}"
                  "&schema=public&table=film_embedding", raw=True)
if emb[0] == 200 and emb[1]["data"].get("columns"):
    ecols = {c["name"]: c for c in emb[1]["data"]["columns"]}
    v = ecols.get("embedding")
    check("pgvector 的 vector 列不会让结构探测崩掉,且保留 rawType",
          v is not None and "vector" in (v.get("rawType") or ""),
          f"rawType={(v or {}).get('rawType')!r}" if v else "列不存在")
else:
    skip("pgvector 的 vector 列探测", "seed 时没装 pgvector,film_embedding 被剥掉了")

gen = cols.get("length_hours")
check("生成列(GENERATED ALWAYS AS)被当作普通列列出,不报错",
      gen is not None, f"rawType={(gen or {}).get('rawType')!r}" if gen else "列不存在")


# ═══ 三、自定义 SQL 查询打真实数据量 ═══════════════════════════════════
print("\n【三】自定义查询(功能 7)—— 5 万行的 payment 表 + 跨分区聚合")

payment_rows = int(psql("SELECT count(*) FROM payment", DB_PAGILA))
q = call("POST", f"/datasources/{pagila_ds}/query", {
    "sql": "SELECT count(*) AS n FROM payment", "maxRows": 10, "timeoutSeconds": 60,
})["data"]
returned = int(q["rows"][0][0]) if q.get("rows") else -1
check(f"跨 {partitions} 个分区的聚合查询结果正确",
      returned == payment_rows, f"平台 {returned} vs 库 {payment_rows}")

wide = call("POST", f"/datasources/{pagila_ds}/query", {
    "sql": "SELECT film_id, title, rating, special_features, length_hours FROM film "
           "ORDER BY film_id", "maxRows": 20, "timeoutSeconds": 60,
})["data"]
check("行数上限生效,且结果被标记为截断",
      len(wide["rows"]) == 20 and wide.get("truncated") is True,
      f"{len(wide['rows'])} 行 truncated={wide.get('truncated')}")

wide_rows = rows_as_dicts(wide)
first = wide_rows[0] if wide_rows else {}
check("ENUM 与数组列能被序列化进查询结果(不是 null,也不抛异常)",
      first.get("rating") is not None and first.get("special_features") is not None,
      f"rating={first.get('rating')!r} special_features={first.get('special_features')!r}")

status, res_w = call("POST", f"/datasources/{pagila_ds}/query", {
    "sql": "DELETE FROM payment", "maxRows": 10}, raw=True)
check("写语句被只读白名单挡住(纵深防御的第一道)",
      status != 200, f"HTTP {status} {res_w.get('message', '')[:40]}")


# ═══ 四、整库迁移有参照答案(Chinook)═════════════════════════════════
print("\n【四】整库迁移(功能 9)—— Chinook 官方 PG 版当参照答案")

psql(f"DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;", DB_TARGET)
chinook_ds = make_pg_datasource(f"P6-Chinook源-{RUN}", DB_CHINOOK)
target_ds = make_pg_datasource(f"P6-迁移目标-{RUN}", DB_TARGET)
created_datasources += [chinook_ds, target_ds]

call("GET", f"/datasources/{chinook_ds}/catalog?database={DB_CHINOOK}&schema=public&refresh=true")
call("GET", f"/datasources/{target_ds}/catalog?database={DB_TARGET}&schema=public&refresh=true")

source_counts = dict(
    line.split("|") for line in psql(
        "SELECT table_name||'|'||(xpath('/row/c/text()', query_to_xml("
        "  format('SELECT count(*) AS c FROM public.%I', table_name),"
        "  false, true, '')))[1]::text::int"
        " FROM information_schema.tables"
        " WHERE table_schema='public' AND table_type='BASE TABLE'", DB_CHINOOK).splitlines())
print(f"       源库 {len(source_counts)} 张表,{sum(int(v) for v in source_counts.values())} 行")

migration = call("POST", "/jobs", {
    "name": f"P6-Chinook整库迁移-{RUN}", "jobType": "DB_MIGRATION",
    "description": "拿官方 PG 版 Chinook 对答案",
    "config": {
        "sourceDataSourceId": chinook_ds, "sourceDatabase": DB_CHINOOK, "sourceSchema": "public",
        "targetDataSourceId": target_ds, "targetDatabase": DB_TARGET, "targetSchema": "public",
        "tables": [], "createTable": True, "writeMode": "APPEND", "batchSize": 1000,
    },
    "timeoutMs": 600000,
})["data"]
created_jobs.append(migration["id"])

compiled = call("POST", f"/jobs/{migration['id']}/compile")["data"]
check("整库迁移编译通过", compiled["succeeded"], compiled["summary"])

if compiled["succeeded"]:
    call("POST", f"/jobs/{migration['id']}/publish")
    final = run_job(migration["id"], seconds=420)
    ex = final["execution"]
    check("迁移执行成功", ex["status"] == "SUCCEEDED",
          f"{ex['status']} {ex.get('errorMessage', '') or ''}"[:90])

    target_counts = dict(
        line.split("|") for line in psql(
            "SELECT table_name||'|'||(xpath('/row/c/text()', query_to_xml("
            "  format('SELECT count(*) AS c FROM public.%I', table_name),"
            "  false, true, '')))[1]::text::int"
            " FROM information_schema.tables"
            " WHERE table_schema='public' AND table_type='BASE TABLE'", DB_TARGET).splitlines()
        if line.strip())

    missing = sorted(set(source_counts) - set(target_counts))
    check("每一张源表都在目标端建出来了", not missing,
          f"缺 {len(missing)} 张: {', '.join(missing[:4])}" if missing
          else f"{len(target_counts)} 张")

    mismatched = {t: (source_counts[t], target_counts.get(t, "缺表"))
                  for t in source_counts if target_counts.get(t) != source_counts[t]}
    check("逐表行数与源库一致(这是整库迁移唯一算数的判据)",
          not mismatched,
          "; ".join(f"{t}: 源{a} 目标{b}" for t, (a, b) in list(mismatched.items())[:4])
          or f"{len(source_counts)} 张表全部对上")

    # 行数对上不代表内容对上 —— 抽一列真实文本比对,专治"整列写成 null"
    # 这种行数看不出来的错
    src_sample = psql("SELECT string_agg(name, '|' ORDER BY genre_id) FROM genre", DB_CHINOOK)
    dst_sample = psql("SELECT string_agg(name, '|' ORDER BY genre_id) FROM genre", DB_TARGET) \
        if "genre" in target_counts else ""
    check("抽样比对内容一致(行数对上不代表内容对上)",
          src_sample == dst_sample and bool(src_sample),
          f"{src_sample[:44]}…" if src_sample == dst_sample else
          f"源 {src_sample[:30]!r} vs 目标 {dst_sample[:30]!r}")


# ═══ 四之二、跨方言迁移:MySQL → PostgreSQL ═══════════════════════════
# 上面那一段是 PG → PG,DialectDdl 与 TypeMappers 走的是恒等映射,错了也看不出来。
# 真正要验的是这一段:Chinook 官方的 MySQL 版迁进 PostgreSQL,再拿官方的
# PG 版当答案对。两份脚本由同一个上游生成,表结构语义一致而方言不同 ——
# 这是本项目里唯一有"标准答案"的类型映射验证。
print("\n【四之二】跨方言迁移(风险 R7)—— MySQL 版 Chinook 迁进 PostgreSQL,拿官方 PG 版对答案")

if not MYSQL_HOST:
    skip("MySQL → PostgreSQL 跨方言迁移",
         "未设置 DG_SEED_MYSQL_HOST;先跑 ./scripts/start-test-mysql.sh")
else:
    mysql_ok = True
    try:
        mysql_tables = dict(
            line.split("\t") for line in mysql(
                "SELECT table_name, table_rows FROM information_schema.tables "
                f"WHERE table_schema='{MYSQL_DB}'").splitlines() if line.strip())
    except Exception as e:
        mysql_ok = False
        skip("MySQL → PostgreSQL 跨方言迁移", f"连不上 MySQL: {e}")

    if mysql_ok:
        print(f"       MySQL 源库 {len(mysql_tables)} 张表(表名是 PascalCase:"
              f"{', '.join(sorted(mysql_tables)[:3])}…)")

        psql("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;", DB_TARGET)
        mysql_ds = call("POST", "/datasources", {
            "name": f"P6-MySQL-Chinook-{RUN}", "type": "MYSQL",
            "host": MYSQL_HOST, "port": MYSQL_PORT, "databaseName": MYSQL_DB,
            "username": MYSQL_USER,
            "inlineSecret": {"authType": "PASSWORD", "username": MYSQL_USER,
                             "secret": MYSQL_PASSWORD},
        })["data"]["id"]
        created_datasources.append(mysql_ds)
        probe = call("POST", f"/datasources/{mysql_ds}/test")["data"]
        check("MySQL 数据源连通", probe["success"] is True,
              probe.get("serverVersion") or probe.get("message"))

        call("GET", f"/datasources/{mysql_ds}/catalog?database={MYSQL_DB}&refresh=true")
        call("GET", f"/datasources/{target_ds}/catalog?database={DB_TARGET}"
                    "&schema=public&refresh=true")

        # lowercaseNames:MySQL 版用 PascalCase(Track / InvoiceLine),PostgreSQL 里
        # 不加引号的标识符会折成小写。不转的话平台要么建出带引号的 "Track"
        # (从此每次查询都得带引号),要么两边对不上。
        cross = call("POST", "/jobs", {
            "name": f"P6-跨方言迁移-{RUN}", "jobType": "DB_MIGRATION",
            "description": "MySQL 版 Chinook → PostgreSQL,拿官方 PG 版对答案",
            "config": {
                "sourceDataSourceId": mysql_ds, "sourceDatabase": MYSQL_DB,
                "targetDataSourceId": target_ds, "targetDatabase": DB_TARGET,
                "targetSchema": "public",
                "tables": [], "createTable": True, "lowercaseNames": True,
                "writeMode": "APPEND", "batchSize": 1000,
            },
            "timeoutMs": 600000,
        })["data"]
        created_jobs.append(cross["id"])

        compiled = call("POST", f"/jobs/{cross['id']}/compile")["data"]
        check("跨方言迁移编译通过", compiled["succeeded"], compiled["summary"])

        if compiled["succeeded"]:
            call("POST", f"/jobs/{cross['id']}/publish")
            ex = run_job(cross["id"], seconds=420)["execution"]
            check("跨方言迁移执行成功", ex["status"] == "SUCCEEDED",
                  f"{ex['status']} {ex.get('message', '') or ''}"[:96])

            target_now = {t: int(n) for t, n in (
                line.split("|") for line in psql(
                    "SELECT table_name||'|'||(xpath('/row/c/text()', query_to_xml("
                    "  format('SELECT count(*) AS c FROM public.%I', table_name),"
                    "  false, true, '')))[1]::text::int"
                    " FROM information_schema.tables"
                    " WHERE table_schema='public' AND table_type='BASE TABLE'",
                    DB_TARGET).splitlines() if line.strip())}

            # 名字归一化后再比:MySQL 版叫 InvoiceLine、PG 版叫 invoice_line,
            # 这是两份上游脚本的命名习惯差异,不是平台的错。去掉下划线再小写,
            # 两边就落到同一个键上。
            def norm(name):
                return name.replace("_", "").lower()

            src_norm = {norm(t): int(n) for t, n in mysql_tables.items()}
            dst_norm = {norm(t): n for t, n in target_now.items()}

            missing = sorted(set(src_norm) - set(dst_norm))
            check("每一张 MySQL 表都在 PostgreSQL 端建出来了", not missing,
                  f"缺 {len(missing)} 张: {', '.join(missing[:4])}" if missing
                  else f"{len(dst_norm)} 张")

            # information_schema.table_rows 在 MySQL 上是估算值,不能直接当判据。
            # 逐表回源数一次真实行数 —— 迁移对不对只有真实行数说了算。
            row_mismatch = {}
            for src_table in mysql_tables:
                actual = int(mysql(f"SELECT COUNT(*) FROM `{MYSQL_DB}`.`{src_table}`"))
                got = dst_norm.get(norm(src_table))
                if got != actual:
                    row_mismatch[src_table] = (actual, got if got is not None else "缺表")
            check("逐表行数与 MySQL 源库一致", not row_mismatch,
                  "; ".join(f"{t}: 源{a} 目标{b}" for t, (a, b) in list(row_mismatch.items())[:4])
                  or f"{len(mysql_tables)} 张表全部对上")

            # ── 这才是跨方言迁移真正要验的东西 ──────────────────────────
            # 行数对上只说明搬运没漏。类型映射错了行数照样对得上,而错误要等到
            # 下游某次插入超长字符串、或金额被四舍五入成整数时才暴露。
            # 官方 PG 版 Chinook 就是标准答案,逐列比。
            def columns_of(db, table):
                rows = psql(
                    "SELECT column_name||'|'||data_type"
                    "||coalesce('('||character_maximum_length||')','')"
                    "||coalesce('('||numeric_precision||','||numeric_scale||')','')"
                    f" FROM information_schema.columns WHERE table_schema='public'"
                    f" AND table_name='{table}' ORDER BY ordinal_position", db)
                return {c.split("|")[0].replace("_", "").lower(): c.split("|")[1]
                        for c in rows.splitlines() if c.strip()}

            reference = columns_of(DB_CHINOOK, "track")     # 官方 PG 版 = 答案
            migrated = columns_of(DB_TARGET, "track")       # 平台迁出来的
            check("跨方言迁移建出了 track 表且列数一致",
                  len(migrated) == len(reference) and len(reference) > 0,
                  f"平台 {len(migrated)} 列 vs 官方 {len(reference)} 列")

            if migrated:
                # varchar 的长度必须保住。丢了长度(退化成 text)不会有任何报错,
                # 但目标表从此接受任意长的字符串 —— 源端的约束被悄悄取消了。
                check("varchar(200) 的长度保住了(没退化成 text)",
                      migrated.get("name", "").startswith("character varying(200)"),
                      f"track.name → {migrated.get('name')!r},官方 {reference.get('name')!r}")

                # decimal(10,2) 的精度与标度必须保住。退化成 numeric 无精度还算好,
                # 退化成 double precision 就是金额字段从此带浮点误差。
                check("decimal(10,2) 的精度与标度保住了",
                      migrated.get("unitprice", "").startswith("numeric(10,2)"),
                      f"track.unit_price → {migrated.get('unitprice')!r},"
                      f"官方 {reference.get('unitprice')!r}")

                check("int 映射成 integer",
                      migrated.get("trackid", "").startswith("integer"),
                      f"track.track_id → {migrated.get('trackid')!r}")

                # 整表逐列比对官方答案。上面三条是重点抽查,这条是兜底 ——
                # 漏掉一列的类型退化,下游要到生产上才发现
                diffs = {k: (reference[k], migrated.get(k, "缺列"))
                         for k in reference if migrated.get(k) != reference[k]}
                check("track 表全部 9 列的类型与官方 PG 版逐列一致",
                      not diffs,
                      "; ".join(f"{k}: 官方 {a} vs 平台 {b}"
                                for k, (a, b) in list(diffs.items())[:3])
                      or f"{len(reference)} 列全对")

            # ── lowercaseNames 必须把列名也一起规范化 ─────────────────────
            # PostgreSQL 里不加引号的标识符会折成小写,所以一个叫 "Name" 的列
            # 从此每次都得写引号:SELECT name FROM genre 直接报错。
            # 表名转了、列名没转,是最难受的一种半套 —— 用户连约定都猜不出来,
            # 而且平台自己的字段映射、清洗规则也全都要跟着写引号。
            migrated_cols = psql(
                "SELECT string_agg(column_name, ',' ORDER BY ordinal_position)"
                " FROM information_schema.columns"
                " WHERE table_schema='public' AND table_name='genre'", DB_TARGET)
            uppercased = [c for c in migrated_cols.split(",") if c != c.lower()]
            check("lowercaseNames 也把列名转成小写(否则每次查询都得加引号)",
                  not uppercased,
                  f"仍是大小写混排: {', '.join(uppercased)}" if uppercased
                  else migrated_cols)

            # 内容抽样:跨方言最容易在字符集上出岔子。
            # 列名按实际建出来的取并加引号 —— 上面那条断言是红是绿,
            # 都不该影响"内容有没有被改坏"这个独立的结论。
            name_col = psql(
                "SELECT column_name FROM information_schema.columns"
                " WHERE table_schema='public' AND table_name='genre'"
                " AND lower(column_name)='name'", DB_TARGET)
            id_col = psql(
                "SELECT column_name FROM information_schema.columns"
                " WHERE table_schema='public' AND table_name='genre'"
                " AND lower(column_name)='genreid'", DB_TARGET)
            src_genres = mysql(f"SELECT GROUP_CONCAT(Name ORDER BY GenreId SEPARATOR '|') "
                               f"FROM `{MYSQL_DB}`.`Genre`")
            dst_genres = psql(
                f'SELECT string_agg("{name_col}", \'|\' ORDER BY "{id_col}") FROM genre',
                DB_TARGET) if name_col and id_col else ""
            check("抽样比对内容一致(跨方言的字符集没把内容改坏)",
                  src_genres == dst_genres and bool(src_genres),
                  f"{src_genres[:44]}…" if src_genres == dst_genres
                  else f"源 {src_genres[:30]!r} vs 目标 {dst_genres[:30]!r}")


# ═══ 五、文件解析入库:中文 + GBK + 脱敏 ═══════════════════════════════
print("\n【五】文件解析入库(功能 12)+ 脱敏(序号 35)—— GBK 中文健康数据")

ftp_up = False
try:
    import socket
    with socket.create_connection((FTP_HOST, FTP_PORT), timeout=3):
        ftp_up = True
except OSError:
    pass

if not ftp_up:
    skip("GBK 中文文件解析 + 脱敏", f"本地 FTP {FTP_HOST}:{FTP_PORT} 没起,"
         "先跑 ./scripts/seed-public-datasets.sh ftp start")
else:
    ftp_ds = call("POST", "/datasources", {
        "name": f"P6-健康数据文件-{RUN}", "type": "FTP",
        "host": FTP_HOST, "port": FTP_PORT, "databaseName": "/", "username": FTP_USER,
        "inlineSecret": {"authType": "PASSWORD", "username": FTP_USER, "secret": FTP_PASSWORD},
    })["data"]["id"]
    created_datasources.append(ftp_ds)
    test = call("POST", f"/datasources/{ftp_ds}/test")["data"]
    check("FTP 数据源连通", test["success"] is True, test.get("message"))

    listing = call("GET", f"/datasources/{ftp_ds}/catalog?path=/health")["data"]
    file_names = [f["name"] for f in listing.get("files", [])]
    check("能列出目录里的文件", "patients_cn_gbk.csv" in file_names,
          f"{len(file_names)} 个: {', '.join(file_names[:4])}")

    target_table = f"p6_health_{RUN}"
    psql(f"""CREATE TABLE public.{target_table} (
                 name text, gender text, id_card text, phone text,
                 hospital text, visit_date text, diagnosis text)""", DB_TARGET)
    call("GET", f"/datasources/{target_ds}/catalog?database={DB_TARGET}"
                f"&schema=public&table={target_table}&refresh=true")

    mask_id_card = call("POST", "/rules", {
        "name": f"P6-身份证脱敏-{RUN}", "kind": "MASK",
        "params": {"mode": "PARTIAL", "keepPrefix": "6", "keepSuffix": "4"},
    })["data"]["id"]
    mask_phone = call("POST", "/rules", {
        "name": f"P6-手机号脱敏-{RUN}", "kind": "MASK",
        "params": {"mode": "PARTIAL", "keepPrefix": "3", "keepSuffix": "4"},
    })["data"]["id"]
    created_rules += [mask_id_card, mask_phone]

    # path 指目录 + filePattern 筛文件。指到文件本身的那种写法另有一条断言,
    # 见本节末尾 —— 它现在是红的,所以主链路不用它,免得一个缺陷把后面
    # GBK 解码、脱敏是否生效这些独立的结论一起带下水
    parse = call("POST", "/jobs", {
        "name": f"P6-GBK健康数据入库-{RUN}", "jobType": "FILE_PARSE",
        "config": {
            "sourceDataSourceId": ftp_ds, "path": "/health",
            "filePattern": "patients_cn_gbk.csv",
            "format": "CSV", "charset": "GBK", "hasHeader": True,
            "targetDataSourceId": target_ds, "targetDatabase": DB_TARGET,
            "targetSchema": "public", "targetTable": target_table,
            "fieldMappings": {
                "姓名": "name", "性别": "gender", "身份证号": "id_card",
                "手机号": "phone", "就诊机构": "hospital",
                "就诊日期": "visit_date", "诊断": "diagnosis",
            },
            "fieldRules": {"身份证号": [mask_id_card], "手机号": [mask_phone]},
            "writeMode": "APPEND", "batchSize": 100,
        },
        "timeoutMs": 120000,
    })["data"]
    created_jobs.append(parse["id"])

    compiled = call("POST", f"/jobs/{parse['id']}/compile")["data"]
    check("中文列名的字段映射能编译通过", compiled["succeeded"], compiled["summary"])

    if compiled["succeeded"]:
        call("POST", f"/jobs/{parse['id']}/publish")
        final = run_job(parse["id"], seconds=180)
        ex = final["execution"]
        check("GBK 文件解析执行成功", ex["status"] == "SUCCEEDED",
              f"{ex['status']} {ex.get('errorMessage', '') or ''}"[:90])

        loaded = int(psql(f"SELECT count(*) FROM public.{target_table}", DB_TARGET))
        check("行数与文件一致", loaded == 200, f"{loaded} 行")

        # 数据全落进去了、状态却是 FAILED,是最坏的一种组合:值班的人看到失败
        # 会重跑,而重跑会把这 200 行再写一遍。重试策略 maxAttempts>1 时平台
        # 自己就会干这件事
        check("执行状态与实际结果一致(数据写进去了就不该报失败)",
              not (loaded == 200 and ex["status"] == "FAILED"),
              f"{ex['status']},目标表 {loaded} 行")

    # 下面这一组断言全是"表里的内容对不对"。<b>一行都没落进来时它们会全部
    # 空转通过</b> —— 「没有明文身份证」在空表上永远成立,而那正是最不该
    # 给出的一个安心结论。所以先要求有数据,再谈内容
    loaded = int(psql(f"SELECT count(*) FROM public.{target_table}", DB_TARGET))
    if loaded == 0:
        skip("GBK 解码 / 脱敏生效 / 区分度(共 5 条)", "目标表是空的,内容断言会空转通过")
    else:
        # 编码没生效的话中文会变成替换字符。这条比"能不能跑通"更重要:
        # 一个把中文写成乱码的任务,状态照样是 SUCCEEDED
        garbled = int(psql(
            f"SELECT count(*) FROM public.{target_table} "
            f"WHERE hospital LIKE '%�%' OR name LIKE '%�%'", DB_TARGET))
        sample = psql(f"SELECT hospital FROM public.{target_table} LIMIT 1", DB_TARGET)
        check("GBK 中文解码正确,没有替换字符", garbled == 0,
              f"样例 {sample!r}" if garbled == 0 else f"{garbled} 行含乱码")

        check("中文诊断名完整落库(不是被截断的半个汉字)",
              int(psql(f"SELECT count(*) FROM public.{target_table} "
                       f"WHERE diagnosis = '原发性高血压'", DB_TARGET)) > 0,
              psql(f"SELECT string_agg(DISTINCT diagnosis, ' / ') FROM public.{target_table}",
                   DB_TARGET)[:60])

        # 明文泄露检查:目标表里不该有任何一个完整的 18 位身份证号。
        # 这是整条脱敏链路唯一算数的判据 —— 规则建了、映射配了、任务成功了,
        # 都不能证明脱敏真的生效
        plaintext = int(psql(
            f"SELECT count(*) FROM public.{target_table} "
            f"WHERE id_card ~ '^[0-9]{{17}}[0-9Xx]$'", DB_TARGET))
        masked_sample = psql(f"SELECT id_card FROM public.{target_table} LIMIT 1", DB_TARGET)
        check("目标表里没有任何一个完整的 18 位身份证号",
              plaintext == 0,
              f"样例 {masked_sample!r}" if plaintext == 0 else f"{plaintext} 行是明文")

        check("手机号也被脱敏",
              int(psql(f"SELECT count(*) FROM public.{target_table} "
                       f"WHERE phone ~ '^1[0-9]{{10}}$'", DB_TARGET)) == 0,
              psql(f"SELECT phone FROM public.{target_table} LIMIT 1", DB_TARGET))

        # 脱敏不能把列变成一堆一模一样的星号 —— 那样数据虽然安全了,
        # 但也失去了作为数据的价值,而这种退化不会有任何报错
        distinct = int(psql(f"SELECT count(DISTINCT id_card) FROM public.{target_table}",
                            DB_TARGET))
        check("脱敏后仍保留区分度(没退化成同一个值)", distinct > 100,
              f"{distinct} 个不同值 / {loaded} 行")

    # path 直接指向一个文件 —— 编译器的配置说明写的是「path 文件或目录路径」,
    # 所以这是被承诺支持的写法。用真实 FTP 打一遍才看得见:FTP 对一个文件
    # 执行 LIST 会返回该文件自己那一条,平台把它当成目录清单,于是拼出
    # /health/x.csv/x.csv 这样的路径。本地文件系统和 mock 都测不出这个。
    direct = call("POST", "/jobs", {
        "name": f"P6-path直指文件-{RUN}", "jobType": "FILE_PARSE",
        "config": {
            "sourceDataSourceId": ftp_ds, "path": "/health/patients_cn_utf8.csv",
            "format": "CSV", "charset": "UTF-8", "hasHeader": True,
            "targetDataSourceId": target_ds, "targetDatabase": DB_TARGET,
            "targetSchema": "public", "targetTable": target_table,
            "fieldMappings": {"姓名": "name"},
            "writeMode": "APPEND", "batchSize": 100,
        },
        "timeoutMs": 120000,
    })["data"]
    created_jobs.append(direct["id"])
    direct_c = call("POST", f"/jobs/{direct['id']}/compile")["data"]
    if direct_c["succeeded"]:
        call("POST", f"/jobs/{direct['id']}/publish")
        dex = run_job(direct["id"], seconds=120)["execution"]
        check("path 直接指向一个文件时也能解析(编译器说明承诺支持这种写法)",
              dex["status"] == "SUCCEEDED",
              f"{dex['status']} {dex.get('errorMessage', '') or ''}"[:96])
    else:
        check("path 直接指向一个文件时也能解析(编译器说明承诺支持这种写法)",
              False, direct_c.get("summary", "")[:60])

    # 拼错键名在离线同步里会被 warnUnknownKeys 拦住(P5 加的)。
    # 文件解析走的是另一个编译器 —— 同一个错误在这条路径上是否也拦得住?
    typo = call("POST", "/jobs", {
        "name": f"P6-文件解析键名拼错-{RUN}", "jobType": "FILE_PARSE",
        "config": {
            "sourceDataSourceId": ftp_ds, "path": "/health/patients_cn_gbk.csv",
            "format": "CSV", "charset": "GBK", "hasHeader": True,
            "targetDataSourceId": target_ds, "targetDatabase": DB_TARGET,
            "targetSchema": "public", "targetTable": target_table,
            "fieldMappings": {"身份证号": "id_card"},
            "rulesByColumn": {"身份证号": [mask_id_card]},   # 拼错:应当是 fieldRules
            "writeMode": "APPEND",
        },
    })["data"]
    created_jobs.append(typo["id"])
    typo_c = call("POST", f"/jobs/{typo['id']}/compile")["data"]
    diagnostics = json.dumps(typo_c.get("diagnostics", []), ensure_ascii=False)
    check("文件解析里拼错的配置键也被报出来(否则脱敏静默失效,明文直接落库)",
          "rulesByColumn" in diagnostics,
          typo_c.get("summary", "")[:60])


# ═══ 清理 ═════════════════════════════════════════════════════════════
print("\n清理…")
for job_id in created_jobs:
    call("DELETE", f"/jobs/{job_id}", raw=True)
for rule_id in created_rules:
    call("DELETE", f"/rules/{rule_id}", raw=True)
for ds_id in created_datasources:
    call("DELETE", f"/datasources/{ds_id}", raw=True)
# 迁移目标库整个重置,下一次跑才有干净的对照基准
try:
    psql("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;", DB_TARGET)
except Exception as e:
    print(f"  目标库重置失败(不影响本次结论): {e}")

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
print(" 结果:P6 全部通过")
print("=" * 78)
