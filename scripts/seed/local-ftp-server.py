#!/usr/bin/env python3
"""一个只读的本地 FTP 服务,给文件数据源(功能 2)和文件解析入库(功能 12)当靶子。

<b>为什么是 FTP 而不是 SFTP</b>:SFTP 需要一个 SSH 服务端 —— 容器里通常没有
sshd,而 Docker 镜像仓库在本项目的网络策略下是封禁的(拉取返回 403)。FTP 则
可以用纯 Python 起一个,不依赖任何系统服务。平台的 FtpConnector 与
SftpConnector 走的是同一个 FileCatalogReader 契约(listEntries + openFile),
所以用 FTP 验证契约,与用 SFTP 验证是等价的;真要验 SSH 那一段传输,
另说 —— 那验的是 JSch 而不是本平台的代码。

<b>只读是刻意的</b>:平台目前只从文件数据源读。开一个可写的 FTP 意味着一次
路径拼接的疏忽就能覆盖掉宿主机上的文件,而这个能力现在没有任何地方需要。

监听固定绑在 127.0.0.1 —— 匿名可登录的 FTP 不该出现在回环地址之外。

用法:
    local-ftp-server.py --root DIR [--port 2121] [--user dgseed] [--password dgseed]
                        [--pidfile PATH]
"""
import argparse
import logging
import os
import sys

try:
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.servers import FTPServer
except ImportError:
    sys.exit("缺少 pyftpdlib。安装:pip3 install pyftpdlib")

# 只给读权限:e=进目录 l=列目录 r=下载。写相关的 a/d/f/m/w/M 一个都不给。
READ_ONLY_PERM = "elr"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", required=True, help="对外暴露的根目录")
    parser.add_argument("--port", type=int, default=int(os.environ.get("DG_SEED_FTP_PORT", "2121")))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--user", default=os.environ.get("DG_SEED_FTP_USER", "dgseed"))
    parser.add_argument("--password", default=os.environ.get("DG_SEED_FTP_PASSWORD", "dgseed"))
    parser.add_argument("--pidfile")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        sys.exit(f"根目录不存在: {root}")

    authorizer = DummyAuthorizer()
    authorizer.add_user(args.user, args.password, root, perm=READ_ONLY_PERM)
    # 也允许匿名 —— FtpConnector 在没配用户名时会退回 anonymous 登录,
    # 那条分支同样需要能被测到
    authorizer.add_anonymous(root, perm=READ_ONLY_PERM)

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.banner = "datagovernance seed ftp (read-only)"
    # 被动模式端口范围必须显式指定并绑在回环 —— 不指定的话内核随机取端口,
    # 一旦将来有防火墙规则,被动连接会以"卡住不动"的形式失败,极难排查
    handler.passive_ports = range(args.port + 1, args.port + 21)
    handler.masquerade_address = args.host

    logging.basicConfig(level=logging.WARNING if args.quiet else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")

    server = FTPServer((args.host, args.port), handler)
    server.max_cons = 64

    if args.pidfile:
        with open(args.pidfile, "w") as f:
            f.write(str(os.getpid()))

    if not args.quiet:
        print(f"FTP 已就绪  ftp://{args.host}:{args.port}  root={root}  "
              f"账号 {args.user}/{args.password}(或匿名),只读", flush=True)
    try:
        server.serve_forever()
    finally:
        if args.pidfile and os.path.exists(args.pidfile):
            os.unlink(args.pidfile)


if __name__ == "__main__":
    main()
