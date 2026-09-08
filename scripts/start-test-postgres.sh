#!/usr/bin/env bash
#
# 启动一个供测试使用的本地 PostgreSQL 实例。
#
# 为什么需要它:连接器的端到端测试(PostgreSqlConnectorLiveIT)要打一个真实的
# PostgreSQL —— 只有真实的 DatabaseMetaData 才能验证结构探测与类型映射,mock 不行。
#
# 为什么不用 Testcontainers 作为主路径:本项目的开发容器允许运行 Docker 守护进程,
# 但镜像仓库被网络策略封禁(拉取返回 403)。Testcontainers 版本的测试仍然保留
# (PostgreSqlConnectorContainerIT),在有 Docker 的 CI 上会跑,在这里自动跳过。
#
# 用法:
#   ./scripts/start-test-postgres.sh          启动(已在运行则跳过)
#   ./scripts/start-test-postgres.sh stop     停止
#   ./scripts/start-test-postgres.sh status    查看状态
#
# 之后 mvn test 会自动连上去。要改连接参数,用 -Ddg.test.pg.* 或环境变量 DG_TEST_PG_*。

set -euo pipefail

PGPORT="${DG_TEST_PG_PORT:-55432}"
PGDATA="${DG_TEST_PGDATA:-/var/lib/postgresql/dgtest}"
PGUSER_OS="postgres"
PGPASSWORD_VALUE="${DG_TEST_PG_PASSWORD:-postgres}"
# 平台元数据库 + 连接器探测目标库
DATABASES=("datagovernance" "dg_probe")

find_pgbin() {
    for dir in /usr/lib/postgresql/*/bin; do
        if [ -x "$dir/initdb" ]; then echo "$dir"; return 0; fi
    done
    echo "找不到 PostgreSQL 服务端程序。请先安装 postgresql-16(或更高)。" >&2
    return 1
}

PGBIN="$(find_pgbin)"

case "${1:-start}" in
  status)
    su "$PGUSER_OS" -c "$PGBIN/pg_ctl -D $PGDATA status" || true
    exit 0
    ;;
  stop)
    su "$PGUSER_OS" -c "$PGBIN/pg_ctl -D $PGDATA -m fast stop" || true
    exit 0
    ;;
esac

# 已在运行就什么都不做 —— 这个脚本必须可以反复执行
if su "$PGUSER_OS" -c "$PGBIN/pg_ctl -D $PGDATA status" >/dev/null 2>&1; then
    echo "PostgreSQL 已在运行(端口 $PGPORT)"
    exit 0
fi

mkdir -p "$PGDATA" /var/run/postgresql /var/log/postgresql
chown -R "$PGUSER_OS:$PGUSER_OS" "$PGDATA" /var/run/postgresql /var/log/postgresql

if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "初始化数据目录 $PGDATA ..."
    # 本地回环 + 仅测试用途,因此 host 认证用 md5 而非 scram 也可接受;
    # 这个实例不应暴露到回环地址之外。
    su "$PGUSER_OS" -c "$PGBIN/initdb -D $PGDATA -U postgres --auth-local=trust --auth-host=md5 -E UTF8" >/dev/null
fi

echo "启动 PostgreSQL(端口 $PGPORT)..."
su "$PGUSER_OS" -c "$PGBIN/pg_ctl -D $PGDATA -o '-p $PGPORT -c listen_addresses=127.0.0.1' -l /var/log/postgresql/dgtest.log -w start"

su "$PGUSER_OS" -c "psql -p $PGPORT -v ON_ERROR_STOP=1 -c \"ALTER USER postgres WITH PASSWORD '$PGPASSWORD_VALUE';\"" >/dev/null

for db in "${DATABASES[@]}"; do
    if ! su "$PGUSER_OS" -c "psql -p $PGPORT -lqt" | cut -d'|' -f1 | grep -qw "$db"; then
        su "$PGUSER_OS" -c "psql -p $PGPORT -v ON_ERROR_STOP=1 -c 'CREATE DATABASE $db;'" >/dev/null
        echo "  已创建数据库 $db"
    fi
done

echo
echo "就绪:"
echo "  平台元数据库   jdbc:postgresql://127.0.0.1:$PGPORT/datagovernance"
echo "  连接器探测目标 jdbc:postgresql://127.0.0.1:$PGPORT/dg_probe"
echo "  账号 postgres / $PGPASSWORD_VALUE"
