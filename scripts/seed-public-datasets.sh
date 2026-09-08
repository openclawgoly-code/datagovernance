#!/usr/bin/env bash
#
# 把公开数据集灌进本地测试环境,供端到端验证使用。
#
# ── 为什么需要它 ──────────────────────────────────────────────────────
# verify-p1 到 verify-p5 用的都是脚本自己造的数据:表是现建的,列是挑好的,
# 编码一律 UTF-8,没有分区、没有自定义类型、没有中文列名。这类数据能验证
# "功能通不通",验证不了"遇到真实的库会不会塌" —— 而结构探测、类型映射、
# 整库迁移这三件事,恰恰只有真实的库才能证伪。
#
# 本脚本准备四组素材,每一组都对着一个具体的验证目标:
#
#   Pagila     16 张表 + 55 个分区 + 8 个视图 + ENUM/DOMAIN/数组/tsvector,
#              还有一个带土耳其无点 ı 的标识符。专治结构探测与类型映射。
#   Chinook    同一份数据模型官方提供 PG / MySQL / Oracle / SQLServer 四套脚本 ——
#              这是整库迁移(风险 R7)唯一能有"参照答案"的验证形态。
#   Northwind  小而经典,当冒烟基线。
#   健康样本   文件解析入库 + 脱敏规则:中文表头、GBK 编码、合法校验位的
#              合成身份证号。详见 scripts/seed/gen-health-samples.py 的说明。
#
# ── 网络 ──────────────────────────────────────────────────────────────
# 三个数据库样本都托管在 raw.githubusercontent.com,本项目的开发容器可以访问。
# Synthea 官方样本在 synthea.mitre.org,该域名被网络策略封禁(CONNECT 返回 403),
# 容器里下不到 —— 此时脚本会生成一份同结构的替身,并在 PROVENANCE.txt 里
# 写明它是替身。在你自己的机器上跑,官方样本会正常下载。
#
# 用法:
#   ./scripts/seed-public-datasets.sh              下载 + 灌库 + 备素材(默认)
#   ./scripts/seed-public-datasets.sh download     只下载到缓存
#   ./scripts/seed-public-datasets.sh load         只灌库(要求缓存已就绪)
#   ./scripts/seed-public-datasets.sh files        只准备文件素材
#   ./scripts/seed-public-datasets.sh ftp start    起本地只读 FTP(功能 12 要用)
#   ./scripts/seed-public-datasets.sh ftp stop
#   ./scripts/seed-public-datasets.sh status
#   ./scripts/seed-public-datasets.sh clean        删掉灌出来的库与缓存
#
# 连接参数一律走环境变量,脚本里不写死任何口令 —— 它要进版本库。
#   DG_TEST_PG_HOST/PORT/USER/PASSWORD   本地 PostgreSQL(默认 127.0.0.1:55432/postgres)
#   DG_SEED_MYSQL_HOST/PORT/USER/PASSWORD  可选;配了才灌 MySQL 版 Chinook
#   DG_SEED_CACHE                        下载缓存目录(默认 <repo>/.seed-cache)
#   DG_SEED_FTP_PORT/USER/PASSWORD       本地 FTP(默认 2121 / dgseed / dgseed)
#   DG_SEED_ROWS                         健康样本条数(默认 200)
#   SYNTHEA_SAMPLE_URL                   Synthea 样本 zip 地址,留空用官方默认

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${DG_SEED_CACHE:-$REPO_ROOT/.seed-cache}"
SQL_DIR="$CACHE/sql"
FILE_ROOT="$CACHE/files"
RUN_DIR="$CACHE/run"

PGHOST="${DG_TEST_PG_HOST:-127.0.0.1}"
PGPORT="${DG_TEST_PG_PORT:-55432}"
PGUSER="${DG_TEST_PG_USER:-postgres}"
PGPASS="${DG_TEST_PG_PASSWORD:-postgres}"

FTP_PORT="${DG_SEED_FTP_PORT:-2121}"
FTP_USER="${DG_SEED_FTP_USER:-dgseed}"
FTP_PASS="${DG_SEED_FTP_PASSWORD:-dgseed}"
FTP_PID="$RUN_DIR/ftp.pid"
FTP_LOG="$RUN_DIR/ftp.log"

ROWS="${DG_SEED_ROWS:-200}"
SYNTHEA_URL="${SYNTHEA_SAMPLE_URL:-https://synthetichealth.github.io/synthea-sample-data/downloads/latest/synthea_sample_data_csv_latest.zip}"

PG_DB_PAGILA="dg_pagila"
PG_DB_NORTHWIND="dg_northwind"
PG_DB_CHINOOK="dg_chinook"
PG_DB_TARGET="dg_migrate_target"

RAW="https://raw.githubusercontent.com"
CHINOOK_BASE="$RAW/lerocha/chinook-database/master/ChinookDatabase/DataSources"

# name|url|最小可接受字节数(小于它说明下到的是错误页而不是数据)
DOWNLOADS=(
  "pagila-schema.sql|$RAW/devrimgunduz/pagila/master/pagila-schema.sql|50000"
  "pagila-data.sql|$RAW/devrimgunduz/pagila/master/pagila-data.sql|1000000"
  "northwind.sql|$RAW/pthom/northwind_psql/master/northwind.sql|100000"
  "Chinook_PostgreSql.sql|$CHINOOK_BASE/Chinook_PostgreSql.sql|400000"
  "Chinook_MySql.sql|$CHINOOK_BASE/Chinook_MySql.sql|400000"
)

# ── 输出 ──────────────────────────────────────────────────────────────
c_ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
c_skip() { printf '  \033[33m-\033[0m %s\n' "$*"; }
c_err()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; }
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

psql_run() {
    PGPASSWORD="$PGPASS" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$@"
}

pg_reachable() {
    PGPASSWORD="$PGPASS" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" \
        -d postgres -tAc 'SELECT 1' >/dev/null 2>&1
}

db_exists() {
    [ "$(psql_run -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$1'" 2>/dev/null)" = "1" ]
}

# 判断一个库是不是已经灌过了:数表比"库存在吗"可靠 ——
# 上一次灌到一半失败,库是在的,里面是空的
db_table_count() {
    psql_run -d "$1" -tAc \
        "SELECT count(*) FROM information_schema.tables
         WHERE table_schema NOT IN ('pg_catalog','information_schema')" 2>/dev/null || echo 0
}

# ── download ──────────────────────────────────────────────────────────
do_download() {
    section "下载数据集脚本 → $SQL_DIR"
    mkdir -p "$SQL_DIR"
    local failed=0
    for entry in "${DOWNLOADS[@]}"; do
        IFS='|' read -r name url minsize <<< "$entry"
        local dest="$SQL_DIR/$name"
        if [ -f "$dest" ] && [ "$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest")" -ge "$minsize" ]; then
            c_skip "$name 已在缓存"
            continue
        fi
        # 下到临时文件再改名:中断的下载不该留下一个看起来完整的缓存文件
        if curl -fsSL --retry 3 --retry-delay 2 --max-time 300 "$url" -o "$dest.part"; then
            local size
            size="$(stat -c%s "$dest.part" 2>/dev/null || stat -f%z "$dest.part")"
            if [ "$size" -lt "$minsize" ]; then
                rm -f "$dest.part"
                c_err "$name 只有 ${size}B,小于预期 ${minsize}B —— 多半下到了错误页"
                failed=1
            else
                mv "$dest.part" "$dest"
                c_ok "$(printf '%-24s %9d B' "$name" "$size")"
            fi
        else
            rm -f "$dest.part"
            c_err "$name 下载失败: $url"
            failed=1
        fi
    done
    return "$failed"
}

# ── load ──────────────────────────────────────────────────────────────
# Chinook 官方脚本自带 DROP DATABASE / CREATE DATABASE / \c,会把库名钉死成
# chinook。这里剥掉那三行,自己控制库名 —— 一个 seed 脚本顺手 DROP 掉一个
# 不归它管的数据库,是不能接受的副作用。
strip_db_ddl_pg() {
    sed -E '/^[[:space:]]*(DROP|CREATE)[[:space:]]+DATABASE/d; /^[[:space:]]*\\c[[:space:]]/d' "$1"
}

strip_db_ddl_mysql() {
    sed -E '/^[[:space:]]*(DROP|CREATE)[[:space:]]+DATABASE/d; /^[[:space:]]*USE[[:space:]]/d' "$1"
}

create_db() {
    db_exists "$1" || psql_run -d postgres -q -c "CREATE DATABASE \"$1\""
}

pg_version_num() {
    psql_run -d postgres -tAc "SELECT current_setting('server_version_num')" 2>/dev/null || echo 0
}

# Pagila 的 master 分支现在要求 PostgreSQL 18 —— 它用了两个 18 才有的东西:
#
#   uuidv7()                        58 处。而且有 13 张表的 COPY 不带 uuid 列,
#                                   靠这个 DEFAULT 填值,剥掉 DEFAULT 就灌不进去。
#   GENERATED ... AS (...) VIRTUAL  1 处(film.length_hours)。18 之前只有 STORED。
#
# 低版本上打这两个补丁,并把打过补丁这件事写进输出。<b>不要</b>用"直接换个老版本
# Pagila"绕过去:老版本没有分区表,而分区正是这份数据集在本项目里最有价值的部分。
#
# uuidv7 补丁是真实现(48 位毫秒时间戳 + 版本位 0111 + 随机),不是拿
# gen_random_uuid() 挂个 v7 的名字糊弄 —— 一个名字说 v7、返回值是 v4 的函数,
# 会在将来某次"为什么这批 UUID 不单调"的排查里浪费掉一整天。
#
# 函数必须建在 pg_catalog,不能建在 public:pagila-schema.sql 是 pg_dump 出来的,
# 第 13 行就是 set_config('search_path', '', false) —— 搜索路径被清空,而 DEFAULT
# 子句里的 uuidv7() 不带模式名。空路径下只有 pg_catalog 仍然隐式可见(PG 18 的
# 内建 uuidv7 也正住在那儿),补丁得放到同一个地方才解析得到。
#
# 两处都只落在 seed 出来的测试库里,不碰平台元数据库,clean 时随库一起删掉。
PG18_REQUIRED=180000

pagila_prelude() {
    local out="$1"
    : > "$out"
    [ "$(pg_version_num)" -ge "$PG18_REQUIRED" ] 2>/dev/null && return 0
    cat > "$out" <<'SQL'
CREATE OR REPLACE FUNCTION pg_catalog.uuidv7() RETURNS uuid AS $uuidv7$
  SELECT encode(
    set_bit(
      set_bit(
        overlay(uuid_send(gen_random_uuid())
                placing substring(int8send((extract(epoch from clock_timestamp()) * 1000)::bigint) from 3)
                from 1 for 6),
        52, 1),
      53, 1),
    'hex')::uuid;
$uuidv7$ LANGUAGE sql VOLATILE;
SQL
}

# VIRTUAL → STORED:列值算法一模一样,差别只在存不存。对本项目要验证的东西
# (结构探测能不能认出生成列)没有影响。
#
# 结果放进全局 PAGILA_SCHEMA_FILE 而不是回显 —— 这个函数要打印一行说明,
# 用 $(...) 接返回值会把说明一起吃进路径里。
PAGILA_SCHEMA_FILE=""
PAGILA_DATA_FILE=""

has_pgvector() {
    [ "$(psql_run -d postgres -tAc \
        "SELECT 1 FROM pg_available_extensions WHERE name='vector'" 2>/dev/null)" = "1" ]
}

pagila_files_for_server() {
    local out="$CACHE/.pagila-schema.sql"
    local data_out="$CACHE/.pagila-data.sql"
    local ver; ver="$(pg_version_num)"
    local patched=0
    cp "$SQL_DIR/pagila-schema.sql" "$out"
    cp "$SQL_DIR/pagila-data.sql" "$data_out"
    PAGILA_SCHEMA_FILE="$out"
    PAGILA_DATA_FILE="$data_out"

    # transaction_timeout 是 PG 17 引入的 GUC,pg_dump 会把它写进两个文件的开头。
    # 低版本上它是个不认识的参数,而 ON_ERROR_STOP 会因此中止整个导入。
    # 直接删掉:这一行只影响导入会话自身的超时,与数据内容无关。
    if [ "$ver" -lt 170000 ] 2>/dev/null; then
        sed -i -E '/^SET transaction_timeout = /d' "$out" "$data_out"
        c_skip "PostgreSQL $ver < 17:已删掉 SET transaction_timeout"
        patched=1
    fi

    if [ "$ver" -lt "$PG18_REQUIRED" ] 2>/dev/null; then
        sed -i -E 's/(GENERATED ALWAYS AS \(.*\)) VIRTUAL/\1 STORED/' "$out"
        c_skip "PostgreSQL $ver < 18:已补 uuidv7() 与 VIRTUAL→STORED"
        patched=1
    fi

    # Pagila 现在带一张 film_embedding(vector(20) + hnsw 索引),要 pgvector。
    # 装得上就别剥 —— 向量列正是序号 34 那条线要验证的类型映射,是这份数据集
    # 里少有的、别处拿不到的东西。
    if ! has_pgvector; then
        c_skip "没装 pgvector,已剥掉 film_embedding 表"
        echo "     想留着(推荐,它是向量类型映射的唯一样本):" >&2
        echo "       Debian/Ubuntu  apt-get install postgresql-\$(pg_config --version|grep -oE '[0-9]+'|head -1)-pgvector" >&2
        echo "       macOS          brew install pgvector" >&2
        python3 - "$out" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
# pg_dump 的输出以 ";" 收尾分句。整句丢弃比按行删安全得多:按行删会把
# CREATE TABLE 的开头留下、把闭合括号删掉,产生一个语法错在别处的文件。
kept, dropped = [], 0
for stmt in text.split(";\n"):
    # (?<!ts) 是为了放过 tsvector —— film.fulltext 与 tsvector_update_trigger
    # 都是要保留的,它们跟 pgvector 没有关系
    if re.search(r"(?<!ts)vector", stmt) or "film_embedding" in stmt:
        dropped += 1
        continue
    kept.append(stmt)
open(path, "w", encoding="utf-8").write(";\n".join(kept))
print(f"     剥掉 {dropped} 条语句", file=sys.stderr)
PY
        patched=1
    fi

    [ "$patched" -eq 0 ] && c_ok "Pagila 无需兼容补丁(PostgreSQL $ver + pgvector)"
    return 0
}

load_pg_dataset() {
    local db="$1" label="$2"; shift 2
    local existing
    existing="$(db_table_count "$db")"
    if [ "$existing" -gt 0 ]; then
        c_skip "$label 已就绪($db,$existing 张表/视图)"
        return 0
    fi
    create_db "$db"
    local tmp="$CACHE/.load-$db.sql"
    : > "$tmp"
    for f in "$@"; do
        strip_db_ddl_pg "$f" >> "$tmp"
        echo >> "$tmp"
    done
    # ON_ERROR_STOP 必须开:半灌进去的库比空库更坏 —— 它看起来能用,
    # 但验证脚本的断言会以一种毫无线索的方式失败
    if PGPASSWORD="$PGPASS" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" \
         -q -v ON_ERROR_STOP=1 -f "$tmp" >"$CACHE/.load-$db.log" 2>&1; then
        rm -f "$tmp"
        c_ok "$label → $db($(db_table_count "$db") 张表/视图)"
    else
        c_err "$label 灌库失败,日志: $CACHE/.load-$db.log"
        tail -5 "$CACHE/.load-$db.log" >&2
        return 1
    fi
}

do_load() {
    section "灌进 PostgreSQL $PGHOST:$PGPORT"
    if ! pg_reachable; then
        c_err "连不上 PostgreSQL $PGHOST:$PGPORT(用户 $PGUSER)"
        echo "     先起一个:./scripts/start-test-postgres.sh" >&2
        return 1
    fi

    local prelude="$CACHE/.pagila-prelude.sql"
    pagila_prelude "$prelude"
    pagila_files_for_server
    load_pg_dataset "$PG_DB_PAGILA" "Pagila" \
        "$prelude" "$PAGILA_SCHEMA_FILE" "$PAGILA_DATA_FILE"
    load_pg_dataset "$PG_DB_NORTHWIND" "Northwind" "$SQL_DIR/northwind.sql"
    load_pg_dataset "$PG_DB_CHINOOK" "Chinook(参照答案)" "$SQL_DIR/Chinook_PostgreSql.sql"

    # 整库迁移的目标端:必须是空库。有内容就说明上一次迁移的结果还在,
    # 留着它会让下一次验证的行数对照失去意义
    create_db "$PG_DB_TARGET"
    local target_tables
    target_tables="$(db_table_count "$PG_DB_TARGET")"
    if [ "$target_tables" -gt 0 ]; then
        c_skip "迁移目标库 $PG_DB_TARGET 里已有 $target_tables 张表(验证脚本会自己清)"
    else
        c_ok "迁移目标库 $PG_DB_TARGET(空)"
    fi

    do_load_mysql
}

do_load_mysql() {
    if [ -z "${DG_SEED_MYSQL_HOST:-}" ]; then
        c_skip "未设置 DG_SEED_MYSQL_HOST,跳过 MySQL 版 Chinook"
        echo "     配上它才能做「MySQL → PostgreSQL 整库迁移,拿官方 PG 版对答案」" >&2
        return 0
    fi
    local mh="${DG_SEED_MYSQL_HOST}" mp="${DG_SEED_MYSQL_PORT:-3306}"
    local mu="${DG_SEED_MYSQL_USER:-root}" mpw="${DG_SEED_MYSQL_PASSWORD:-}"
    if ! MYSQL_PWD="$mpw" mysql -h "$mh" -P "$mp" -u "$mu" -e 'SELECT 1' >/dev/null 2>&1; then
        c_err "连不上 MySQL $mh:$mp(用户 $mu)"
        return 1
    fi
    local count
    count="$(MYSQL_PWD="$mpw" mysql -h "$mh" -P "$mp" -u "$mu" -N -B -e \
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='dg_chinook'" 2>/dev/null || echo 0)"
    if [ "$count" -gt 0 ]; then
        c_skip "MySQL 版 Chinook 已就绪(dg_chinook,$count 张表)"
        return 0
    fi
    MYSQL_PWD="$mpw" mysql -h "$mh" -P "$mp" -u "$mu" \
        -e "CREATE DATABASE IF NOT EXISTS dg_chinook DEFAULT CHARACTER SET utf8mb4"
    strip_db_ddl_mysql "$SQL_DIR/Chinook_MySql.sql" \
        | MYSQL_PWD="$mpw" mysql -h "$mh" -P "$mp" -u "$mu" dg_chinook
    c_ok "MySQL 版 Chinook → $mh:$mp/dg_chinook"
}

# ── files ─────────────────────────────────────────────────────────────
do_files() {
    section "准备文件数据源素材 → $FILE_ROOT"
    local health="$FILE_ROOT/health"
    mkdir -p "$health"

    local synthea_ok=0
    if [ -f "$health/patients.csv" ] && [ -f "$FILE_ROOT/PROVENANCE.txt" ]; then
        c_skip "素材已就绪(要重做先跑 clean)"
        grep -E '^(patients\.csv|来源)' "$FILE_ROOT/PROVENANCE.txt" 2>/dev/null | head -2 || true
        return 0
    fi

    # 先试官方样本。下不到不是错误 —— 是这台机器的出网限制,脚本要照常往下走,
    # 但必须把"你手上这份是替身"这件事写进 PROVENANCE,否则三个月后没人分得清
    local zip="$CACHE/synthea_sample.zip"
    if curl -fsSL --retry 2 --max-time 240 "$SYNTHEA_URL" -o "$zip.part" 2>/dev/null \
       && [ "$(stat -c%s "$zip.part" 2>/dev/null || stat -f%z "$zip.part")" -gt 100000 ]; then
        mv "$zip.part" "$zip"
        if unzip -o -q "$zip" -d "$CACHE/synthea" 2>/dev/null; then
            local found
            found="$(find "$CACHE/synthea" -name 'patients.csv' | head -1)"
            if [ -n "$found" ]; then
                cp "$found" "$health/patients.csv"
                synthea_ok=1
                c_ok "Synthea 官方样本 patients.csv($(wc -l < "$health/patients.csv") 行)"
            fi
        fi
    else
        rm -f "$zip.part"
    fi

    local gen_args=(--out "$health" --count "$ROWS")
    if [ "$synthea_ok" -eq 0 ]; then
        c_skip "Synthea 官方样本取不到($SYNTHEA_URL)—— 改用同结构替身"
        gen_args+=(--synthea-standin)
    fi
    python3 "$REPO_ROOT/scripts/seed/gen-health-samples.py" "${gen_args[@]}"

    {
        echo "本目录内容的来源。写这份文件是因为替身与真样本混在一起而无从分辨,"
        echo "会让后续所有基于它的结论都失去依据。"
        echo
        echo "生成时间: $(date -Iseconds)"
        echo "生成方式: scripts/seed-public-datasets.sh files"
        echo
        if [ "$synthea_ok" -eq 1 ]; then
            echo "patients.csv        Synthea 官方样本(MITRE),下载自 $SYNTHEA_URL"
            echo "                    合成病人,MITRE 声明其不受隐私与法律限制。"
        else
            echo "patients.csv        【替身,非官方样本】由 scripts/seed/gen-health-samples.py"
            echo "                    按 Synthea 的 patients.csv 列结构本地生成。"
            echo "                    官方样本在本机取不到: $SYNTHEA_URL"
        fi
        echo "patients_cn_utf8.csv 本地生成。中文表头,合成身份证号(校验位合法,"
        echo "                    号段与生日随机组合,不对应任何真人)、合成手机号。"
        echo "patients_cn_gbk.csv  同上,GBK 编码。与 utf8 版内容逐字段相同,是对照组。"
        echo "observations.json    本地生成。数组嵌在 data.items 下,用于测 jsonPath。"
        echo
        echo "本目录不含任何真实个人信息。"
    } > "$FILE_ROOT/PROVENANCE.txt"
    c_ok "PROVENANCE.txt 已写明每份文件的来源"
}

# ── ftp ───────────────────────────────────────────────────────────────
ftp_running() {
    [ -f "$FTP_PID" ] && kill -0 "$(cat "$FTP_PID")" 2>/dev/null
}

do_ftp() {
    mkdir -p "$RUN_DIR"
    case "${1:-start}" in
      stop)
        if ftp_running; then
            kill "$(cat "$FTP_PID")" && rm -f "$FTP_PID"
            c_ok "本地 FTP 已停止"
        else
            c_skip "本地 FTP 未在运行"
        fi
        ;;
      status)
        ftp_running && c_ok "本地 FTP 运行中(pid $(cat "$FTP_PID"),端口 $FTP_PORT)" \
                    || c_skip "本地 FTP 未在运行"
        ;;
      start)
        section "本地只读 FTP(文件解析入库要用)"
        if ftp_running; then
            c_skip "已在运行(pid $(cat "$FTP_PID"),端口 $FTP_PORT)"
            return 0
        fi
        if ! python3 -c 'import pyftpdlib' 2>/dev/null; then
            c_skip "缺 pyftpdlib,尝试安装..."
            pip3 install --quiet pyftpdlib || {
                c_err "装不上 pyftpdlib。手动装:pip3 install pyftpdlib"; return 1; }
        fi
        [ -d "$FILE_ROOT" ] || { c_err "文件素材还没准备,先跑:$0 files"; return 1; }
        nohup python3 "$REPO_ROOT/scripts/seed/local-ftp-server.py" \
            --root "$FILE_ROOT" --port "$FTP_PORT" \
            --user "$FTP_USER" --password "$FTP_PASS" \
            --pidfile "$FTP_PID" --quiet >"$FTP_LOG" 2>&1 &
        for _ in $(seq 1 25); do
            ftp_running && break
            sleep 0.2
        done
        if ftp_running; then
            c_ok "ftp://127.0.0.1:$FTP_PORT  账号 $FTP_USER/$FTP_PASS  只读  root=$FILE_ROOT"
        else
            c_err "FTP 起不来,日志: $FTP_LOG"
            tail -5 "$FTP_LOG" >&2
            return 1
        fi
        ;;
      *) c_err "ftp 子命令只认 start / stop / status"; return 1 ;;
    esac
}

# ── status / clean ────────────────────────────────────────────────────
do_status() {
    section "状态"
    if pg_reachable; then
        for db in "$PG_DB_PAGILA" "$PG_DB_NORTHWIND" "$PG_DB_CHINOOK" "$PG_DB_TARGET"; do
            if db_exists "$db"; then
                c_ok "$(printf '%-20s %s 张表/视图' "$db" "$(db_table_count "$db")")"
            else
                c_skip "$(printf '%-20s 不存在' "$db")"
            fi
        done
    else
        c_err "PostgreSQL $PGHOST:$PGPORT 连不上"
    fi
    [ -d "$FILE_ROOT/health" ] \
        && c_ok "文件素材 $(find "$FILE_ROOT/health" -type f | wc -l) 份 → $FILE_ROOT" \
        || c_skip "文件素材未准备"
    do_ftp status
}

do_clean() {
    section "清理"
    do_ftp stop || true
    if pg_reachable; then
        for db in "$PG_DB_PAGILA" "$PG_DB_NORTHWIND" "$PG_DB_CHINOOK" "$PG_DB_TARGET"; do
            if db_exists "$db"; then
                psql_run -d postgres -q -c "DROP DATABASE \"$db\" WITH (FORCE)" \
                    && c_ok "已删库 $db"
            fi
        done
    fi
    rm -rf "$CACHE"
    c_ok "已删缓存 $CACHE"
}

# ── 入口 ──────────────────────────────────────────────────────────────
mkdir -p "$CACHE" "$RUN_DIR"

case "${1:-all}" in
  download) do_download ;;
  load)     do_load ;;
  files)    do_files ;;
  ftp)      shift; do_ftp "${1:-start}" ;;
  status)   do_status ;;
  clean)    do_clean ;;
  all)
    do_download
    do_load
    do_files
    echo
    printf '\033[1m就绪。接下来:\033[0m\n'
    echo "  起 FTP        ./scripts/seed-public-datasets.sh ftp start"
    echo "  跑验证        DG_ADMIN_PASSWORD=… python3 scripts/verify-p6-datasets.py"
    echo
    echo "  Pagila    jdbc:postgresql://$PGHOST:$PGPORT/$PG_DB_PAGILA"
    echo "  Chinook   jdbc:postgresql://$PGHOST:$PGPORT/$PG_DB_CHINOOK"
    echo "  Northwind jdbc:postgresql://$PGHOST:$PGPORT/$PG_DB_NORTHWIND"
    echo "  迁移目标  jdbc:postgresql://$PGHOST:$PGPORT/$PG_DB_TARGET"
    ;;
  -h|--help|help)
    sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
  *) c_err "未知子命令: $1(试 $0 --help)"; exit 1 ;;
esac
