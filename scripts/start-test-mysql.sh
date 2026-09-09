#!/usr/bin/env bash
#
# 启动一个供测试使用的本地 MySQL 兼容实例(MariaDB)。
#
# 为什么需要它:整库迁移(功能 9)的正确性<b>只有跨方言才验得到</b>。同一个
# PostgreSQL 迁到另一个 PostgreSQL,DialectDdl 与 TypeMappers 走的是恒等映射,
# 错了也看不出来。而 Chinook 官方同时提供 MySQL 版与 PostgreSQL 版 —— 把 MySQL 版
# 迁过去,再拿官方 PG 版逐表对答案,差出来的就是映射的 bug。这是本项目里
# 唯一有"参照答案"的迁移验证形态。
#
# 装的是 MariaDB 而不是 Oracle MySQL:Ubuntu 源里 default-mysql-server 指向的
# 就是它,而两者的线路协议与 information_schema 对本项目要验的东西没有差别 ——
# MySqlConnector 用的是 MySQL JDBC 驱动,照样连得上。<b>这一点要如实记着</b>:
# 若将来发现某个 bug 只在 Oracle MySQL 上出现,这套夹具是看不见的。
#
# 用法:
#   ./scripts/start-test-mysql.sh          启动(已在运行则跳过)
#   ./scripts/start-test-mysql.sh stop     停止
#   ./scripts/start-test-mysql.sh status   查看状态
#
# 参数走环境变量,脚本里不写死口令 —— 它要进版本库。
#   DG_SEED_MYSQL_PORT      默认 33306(避开系统上可能已有的 3306)
#   DG_SEED_MYSQL_PASSWORD  root 口令,默认 mysql
#   DG_TEST_MYSQL_DATADIR   数据目录,默认 /var/lib/mysql

set -euo pipefail

PORT="${DG_SEED_MYSQL_PORT:-33306}"
PASSWORD="${DG_SEED_MYSQL_PASSWORD:-mysql}"
DATADIR="${DG_TEST_MYSQL_DATADIR:-/var/lib/mysql}"
SOCKET="/run/mysqld/mysqld.sock"
PIDFILE="/run/mysqld/mysqld.pid"
LOGFILE="/var/log/mysql/dgtest.log"

running() {
    [ -S "$SOCKET" ] && mariadb --socket="$SOCKET" -u root -e 'SELECT 1' >/dev/null 2>&1
}

case "${1:-start}" in
  status)
    running && echo "MariaDB 运行中(端口 $PORT,socket $SOCKET)" || echo "MariaDB 未运行"
    exit 0
    ;;
  stop)
    if [ -f "$PIDFILE" ]; then
        kill "$(cat "$PIDFILE")" 2>/dev/null || true
        echo "已停止"
    else
        echo "未在运行"
    fi
    exit 0
    ;;
esac

if running; then
    echo "MariaDB 已在运行(端口 $PORT)"
    exit 0
fi

mkdir -p /run/mysqld /var/log/mysql
chown -R mysql:mysql /run/mysqld /var/log/mysql "$DATADIR" 2>/dev/null || true

if [ ! -d "$DATADIR/mysql" ]; then
    echo "初始化数据目录 $DATADIR ..."
    mariadb-install-db --user=mysql --datadir="$DATADIR" >/dev/null 2>&1
fi

echo "启动 MariaDB(端口 $PORT)..."
# 只监听回环。这是个一次性测试实例,不该暴露到本机之外。
nohup /usr/sbin/mariadbd \
    --user=mysql --datadir="$DATADIR" \
    --port="$PORT" --bind-address=127.0.0.1 \
    --socket="$SOCKET" --pid-file="$PIDFILE" \
    >"$LOGFILE" 2>&1 &

for _ in $(seq 1 40); do
    running && break
    sleep 0.5
done

if ! running; then
    echo "启动失败,日志: $LOGFILE" >&2
    tail -5 "$LOGFILE" >&2
    exit 1
fi

# root 走 TCP 时要有口令 —— seed 脚本与验证脚本都通过 TCP 连,
# 而 unix_socket 认证只对本地 socket 生效
mariadb --socket="$SOCKET" -u root <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('$PASSWORD');
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$PASSWORD';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL

echo
echo "就绪:"
echo "  jdbc:mysql://127.0.0.1:$PORT/"
echo "  账号 root / $PASSWORD"
echo
echo "接着灌 Chinook:"
echo "  DG_SEED_MYSQL_HOST=127.0.0.1 DG_SEED_MYSQL_PORT=$PORT \\"
echo "  DG_SEED_MYSQL_PASSWORD=$PASSWORD ./scripts/seed-public-datasets.sh load"
