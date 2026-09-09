#!/usr/bin/env python3
"""P4 完成判据的端到端验证:任务监控(24)、告警规则(25)、告警信息(26)、
审计日志(27)、告警渠道(33)。

这个脚本的核心断言不是"接口能调通",而是<b>三条架构主张成立</b>:
  · 序号 24/25/27 都能跨数据集成与数据开发聚合 —— 因为执行事实只有一张表(R4)
  · 「告警频率」是抑制窗口:窗口内的同源告警仍然记录,只是不推送
  · 审计不可变、只追加,而且是拦截器自动记的 —— 不靠每个接口记得调一行
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


def call(method, path, body=None, raw=False, timeout=60):
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
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    result = subprocess.run(
        ["psql", "-h", PG_HOST, "-p", str(PG_PORT), "-U", PG_USER, "-d", db, "-tAc", sql],
        capture_output=True, text=True, env=env, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(f"psql 失败: {result.stderr.strip()}")
    return result.stdout.strip()


def wait_terminal(execution_id, seconds=90):
    detail = None
    for _ in range(seconds):
        detail = call("GET", f"/executions/{execution_id}")["data"]
        if detail["execution"]["status"] in ("SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT"):
            return detail
        time.sleep(1)
    return detail


def wait_alert(predicate, seconds=30):
    """等一条满足条件的告警出现 —— 告警是事件驱动的,不是同步返回的。"""
    for _ in range(seconds):
        page = call("GET", "/governance/alerts?size=100")["data"]
        found = next((a for a in page["records"] if predicate(a)), None)
        if found:
            return found
        time.sleep(1)
    return None


print("=" * 74)
print(" P4 验证 — 任务监控 · 告警规则与信息 · 审计日志 · 告警渠道")
print("=" * 74)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,空间 {workspace}")


# ── 功能 24:任务监控 ────────────────────────────────────────────────
print("\n【功能 24】任务监控 —— 跨数据集成与数据开发聚合(R4 的第一条证据)")

dash = call("GET", "/governance/monitor")["data"]
for field in ("totalExecutions", "failedExecutions", "todayRowsExtracted",
              "totalRowsExtracted", "avgLatencyMs"):
    check(f"面板包含需求口径「{field}」", field in dash, str(dash.get(field)))

check("失败率算得出来且不是 NaN",
      isinstance(dash["failureRatePercent"], (int, float)),
      f"{dash['failureRatePercent']}%")

check("按作业种类拆分 —— 让「哪一类在拖后腿」一眼可见",
      isinstance(dash["byJobType"], list) and len(dash["byJobType"]) > 0,
      str([b["displayName"] for b in dash["byJobType"]]))

# 这是 R4 的关键断言:一次查询同时覆盖数据集成与数据开发两个子系统
kinds = {b["jobRefType"] for b in dash["byJobType"]}
integration = kinds & {"OFFLINE_SYNC", "MIGRATION", "FILE_PARSE", "API_PARSE"}
development = kinds & {"BATCH_DEV", "STREAMING_DEV", "WORKFLOW_NODE"}
check("同一次聚合里既有数据集成也有数据开发的执行(架构约束 R4)",
      len(integration) > 0 and len(development) > 0,
      f"集成 {sorted(integration)} / 开发 {sorted(development)}")

check("工作流父执行不计入总数(它自己不跑任何东西)",
      "WORKFLOW" not in kinds, str(sorted(kinds)))

windowed = call("GET", "/governance/monitor?days=1")["data"]
check("支持按时间窗口统计",
      windowed["since"] is not None
      and windowed["totalExecutions"] <= dash["totalExecutions"],
      f"最近 1 天 {windowed['totalExecutions']} 条 / 全量 {dash['totalExecutions']} 条")

check("最近失败列表可直接点进去",
      isinstance(dash["recentFailures"], list)
      and all("executionId" in f for f in dash["recentFailures"]),
      f"{len(dash['recentFailures'])} 条")


# ── 功能 33:告警渠道 ────────────────────────────────────────────────
print("\n【功能 33】告警渠道 —— 归 Governance 而非配置模块(R2)")

bad_status, bad_body = call("POST", "/governance/channels",
                            {"name": f"P4-坏邮件-{RUN}", "type": "EMAIL",
                             "config": {}}, raw=True)
check("邮件渠道不配收件人被拒",
      bad_status != 200 and "收件人" in bad_body.get("message", ""),
      f"HTTP {bad_status} {bad_body.get('message')}")

bad_url_status, bad_url_body = call("POST", "/governance/channels",
                                    {"name": f"P4-坏地址-{RUN}", "type": "WEBHOOK",
                                     "config": {"url": "ftp://x"}}, raw=True)
check("Webhook 地址协议不对被拒",
      bad_url_status != 200 and "http" in bad_url_body.get("message", ""),
      f"HTTP {bad_url_status} {bad_url_body.get('message')}")

# 起一个本地 HTTP 桩当 Webhook 目标 —— 连通性测试要真的发出去
import http.server, socketserver, threading

received = []


class _Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        received.append(json.loads(self.rfile.read(length) or b"{}"))
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


class _HookServer(socketserver.TCPServer):
    allow_reuse_address = True


hook = _HookServer(("127.0.0.1", 0), _Hook)
HOOK_PORT = hook.server_address[1]
threading.Thread(target=hook.serve_forever, daemon=True).start()

channel = call("POST", "/governance/channels", {
    "name": f"P4-Webhook-{RUN}", "type": "WEBHOOK",
    "config": {"url": f"http://127.0.0.1:{HOOK_PORT}/hook"},
})["data"]
check("新建 Webhook 渠道", channel["status"] == "ACTIVE", channel["status"])
# 响应全局省略 null 字段(application.yml 的 non_null),所以"没测过"=键不存在
check("新建的渠道「从未测试过」而不是「已验证可用」",
      channel.get("lastTestedAt") is None, str(channel.get("lastTestedAt")))
check("目标地址脱敏显示(不回显完整 URL 的查询串)",
      "127.0.0.1" in channel["target"], channel["target"])

test = call("POST", f"/governance/channels/{channel['id']}/test")["data"]
check("连通性测试真的发出一条消息", test["succeeded"] and len(received) == 1,
      f"{test['message']} / 桩收到 {len(received)} 条")
check("测试消息带标题与内容",
      received and "title" in received[0] and "content" in received[0],
      str(list(received[0].keys())) if received else "")

dead = call("POST", "/governance/channels", {
    "name": f"P4-断链-{RUN}", "type": "WEBHOOK",
    "config": {"url": "http://127.0.0.1:1/nope"},
})["data"]
dead_test = call("POST", f"/governance/channels/{dead['id']}/test")["data"]
check("测试不通的渠道被标记为不可达,而不是只弹个提示框就算了",
      not dead_test["succeeded"], dead_test["message"])
dead_after = next(c for c in call("GET", "/governance/channels")["data"]
                  if c["id"] == dead["id"])
check("不可达状态显示在渠道列表上",
      dead_after["status"] == "UNREACHABLE", dead_after["status"])

email = call("POST", "/governance/channels", {
    "name": f"P4-邮件-{RUN}", "type": "EMAIL",
    "config": {"recipients": ["ops@example.invalid"]},
})["data"]
email_test = call("POST", f"/governance/channels/{email['id']}/test")["data"]
check("没配 SMTP 时邮件渠道明确失败,而不是静默成功",
      not email_test["succeeded"] and "SMTP" in email_test["message"],
      email_test["message"])


# ── 功能 25:告警规则 ────────────────────────────────────────────────
print("\n【功能 25】告警规则 —— 范围 / 触发方式 / 渠道 / 告警频率")

triggers = call("GET", "/governance/alert-rules/trigger-types")["data"]
check("触发方式由后端下发", len(triggers) >= 4, str([t["type"] for t in triggers]))
check("需要阈值的触发方式被标出来(UI 据此决定显不显示输入框)",
      any(t["needsThreshold"] for t in triggers),
      str([t["type"] for t in triggers if t["needsThreshold"]]))

no_target_status, no_target_body = call("POST", "/governance/alert-rules", {
    "name": f"P4-缺目标-{RUN}", "triggerType": "EXECUTION_FAILED",
    "scope": "SPECIFIC", "targetJobIds": [],
}, raw=True)
check("范围选「指定任务」但没选任务时被拒",
      no_target_status != 200 and "没有选择任何任务" in no_target_body.get("message", ""),
      f"HTTP {no_target_status}")

no_threshold_status, no_threshold_body = call("POST", "/governance/alert-rules", {
    "name": f"P4-缺阈值-{RUN}", "triggerType": "EXECUTION_SLOW", "scope": "ALL",
}, raw=True)
check("耗时告警不设阈值时被拒",
      no_threshold_status != 200 and "阈值" in no_threshold_body.get("message", ""),
      f"HTTP {no_threshold_status}")

neg_status, neg_body = call("POST", "/governance/alert-rules", {
    "name": f"P4-负窗口-{RUN}", "triggerType": "EXECUTION_FAILED", "scope": "ALL",
    "suppressWindowSeconds": -1,
}, raw=True)
check("告警频率为负数被拒",
      neg_status != 200 and "负数" in neg_body.get("message", ""),
      f"HTTP {neg_status}")

rule = call("POST", "/governance/alert-rules", {
    "name": f"P4-失败告警-{RUN}",
    "description": "P4 验证用",
    "triggerType": "EXECUTION_FAILED",
    "scope": "ALL",
    "channelIds": [channel["id"]],
    # 先设 0 —— 第一轮要验证"每次都推送",抑制在下一段单独验
    "suppressWindowSeconds": 0,
})["data"]
check("新建告警规则", rule["enabled"] and rule["triggerType"] == "EXECUTION_FAILED",
      f"{rule['triggerDisplayName']} / 窗口 {rule['suppressWindowSeconds']}s")

dup_status, dup_body = call("POST", "/governance/alert-rules", {
    "name": f"P4-失败告警-{RUN}", "triggerType": "EXECUTION_FAILED", "scope": "ALL",
}, raw=True)
check("规则重名被拒", dup_status == 409, f"HTTP {dup_status}")


# ── 功能 26:告警信息 —— 真的触发一次 ────────────────────────────────
print("\n【功能 26】告警信息 —— 让一个任务真的失败,看告警是否被触发并推送")

ds_id = call("POST", "/datasources", {
    "name": f"P4-库-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})["data"]["id"]
call("POST", f"/datasources/{ds_id}/test")

# 一个必定失败的批作业:往不存在的表里写
fail_job = call("POST", "/jobs", {
    "name": f"P4-必失败-{RUN}", "jobType": "BATCH",
    "config": {"sourceKind": "SQL", "dataSourceId": ds_id,
               "sql": "INSERT INTO dg_probe_schema.table_that_does_not_exist VALUES (1)"},
    "timeoutMs": 30000,
})["data"]
call("POST", f"/jobs/{fail_job['id']}/compile")
call("POST", f"/jobs/{fail_job['id']}/publish")

before_hook = len(received)
exec1 = call("POST", f"/jobs/{fail_job['id']}/run")["data"]
detail1 = wait_terminal(exec1["id"], 60)
check("任务如期失败(告警的触发源)", detail1["execution"]["status"] == "FAILED",
      detail1["execution"]["status"])

# 认告警要<b>连规则一起认</b>,不能只认执行 ID。同一次失败可以触发多条规则 ——
# 工作区里但凡还有一条「任务失败就通知」的规则(比如 seed-demo-data.py 灌的演示
# 规则,它指着一个不可达的地址),就会为同一个执行再产生一条推送失败的告警。
# 只按 executionId 挑,挑到哪一条要看列表顺序:这个脚本因此红过一次,而被测的
# 那条规则其实好好的。下面那条 scoped_alert 一开始就是这么写的,这里补齐。
alert1 = wait_alert(
    lambda a: a["ruleId"] == rule["id"] and a.get("executionId") == exec1["id"])
check("执行失败触发了告警", alert1 is not None,
      alert1["title"] if alert1 else "30 秒内没等到")
check("告警状态是「已通知」", alert1 and alert1["status"] == "NOTIFIED",
      alert1["statusDisplayName"] if alert1 else "")
check("告警关联了触发它的执行与任务",
      alert1 and alert1.get("jobRefId") == fail_job["id"],
      alert1.get("jobName") if alert1 else "")
check("告警内容说清楚了发生了什么",
      alert1 and fail_job["name"] in (alert1.get("content") or ""),
      (alert1.get("content") or "")[:60] if alert1 else "")

# 等推送真的到达桩服务
for _ in range(15):
    if len(received) > before_hook:
        break
    time.sleep(1)
check("告警真的通过 Webhook 推送出去了", len(received) > before_hook,
      f"桩收到 {len(received) - before_hook} 条新消息")


# ── 「告警频率」= 抑制窗口 ──────────────────────────────────────────
print("\n【功能 25】「告警频率」的实现:抑制窗口内仍记录、不推送")

# 上面那条(alert1)刚刚推送成功,状态是 NOTIFIED。现在把窗口拉到一小时 ——
# 它就成了后续同源告警的"压制者"。这正是「告警频率」要表达的:
# 同一个任务连续失败时,只有第一条会打扰人。
call("PUT", f"/governance/alert-rules/{rule['id']}", {
    "name": rule["name"], "triggerType": "EXECUTION_FAILED", "scope": "ALL",
    "channelIds": [channel["id"]],
    "suppressWindowSeconds": 3600,
})
check("窗口拉大之前已经有一条推送成功的同源告警(它将成为压制者)",
      alert1 is not None and alert1["status"] == "NOTIFIED",
      alert1["id"] if alert1 else "")

before_hook2 = len(received)
exec2 = call("POST", f"/jobs/{fail_job['id']}/run")["data"]
wait_terminal(exec2["id"], 60)
alert2 = wait_alert(
    lambda a: a["ruleId"] == rule["id"] and a.get("executionId") == exec2["id"])
check("窗口内的同源告警<b>仍然被记录</b> —— 没有静默丢弃",
      alert2 is not None, alert2["id"] if alert2 else "没等到")
check("但它的状态是「已抑制」,不是「已通知」",
      alert2 and alert2["status"] == "SUPPRESSED",
      alert2["statusDisplayName"] if alert2 else "")
check("被抑制的告警指向压住它的那一条(排查时能找到源头)",
      alert2 and alert2.get("suppressedBy") is not None,
      alert2.get("suppressedBy") if alert2 else "")

time.sleep(3)
check("被抑制的告警没有推送出去",
      len(received) == before_hook2,
      f"桩新增 {len(received) - before_hook2} 条(期望 0)")

summary = call("GET", "/governance/alerts/summary")["data"]
check("概览把「今日被抑制数」单独给出来 —— 告诉值班的人实际发生次数远不止收到的",
      summary["suppressedTodayCount"] >= 1, str(summary))


# ── 范围过滤:指定任务 ──────────────────────────────────────────────
print("\n【功能 25】范围「指定任务」只盯选中的那几个")

other_job = call("POST", "/jobs", {
    "name": f"P4-另一个必失败-{RUN}", "jobType": "BATCH",
    "config": {"sourceKind": "SQL", "dataSourceId": ds_id,
               "sql": "INSERT INTO dg_probe_schema.also_missing VALUES (1)"},
    "timeoutMs": 30000,
})["data"]
call("POST", f"/jobs/{other_job['id']}/compile")
call("POST", f"/jobs/{other_job['id']}/publish")

# 先把"全部任务"那条规则停掉,否则它会把这一段的告警也一起触发
call("POST", f"/governance/alert-rules/{rule['id']}/disable")
scoped = call("POST", "/governance/alert-rules", {
    "name": f"P4-只盯一个-{RUN}", "triggerType": "EXECUTION_FAILED",
    "scope": "SPECIFIC", "targetJobIds": [fail_job["id"]],
    "suppressWindowSeconds": 0,
})["data"]

exec_other = call("POST", f"/jobs/{other_job['id']}/run")["data"]
wait_terminal(exec_other["id"], 60)
time.sleep(3)
page = call("GET", "/governance/alerts?size=200")["data"]
check("不在范围内的任务失败时不触发该规则",
      not any(a["ruleId"] == scoped["id"] and a.get("executionId") == exec_other["id"]
              for a in page["records"]),
      f"共 {page['total']} 条告警")

exec_in = call("POST", f"/jobs/{fail_job['id']}/run")["data"]
wait_terminal(exec_in["id"], 60)
scoped_alert = wait_alert(
    lambda a: a["ruleId"] == scoped["id"] and a.get("executionId") == exec_in["id"])
check("范围内的任务失败时正常触发", scoped_alert is not None,
      scoped_alert["title"] if scoped_alert else "没等到")


# ── 告警处理 ────────────────────────────────────────────────────────
print("\n【功能 26】告警的认领与关闭")

target = alert1 or scoped_alert
acked = call("POST", f"/governance/alerts/{target['id']}/acknowledge")["data"]
check("认领告警", acked["status"] == "ACKNOWLEDGED" and acked.get("acknowledgedBy"),
      f"{acked['statusDisplayName']} by {acked.get('acknowledgedBy')}")

resolved = call("POST", f"/governance/alerts/{target['id']}/resolve",
                {"note": "P4 验证:已确认是预期的失败"})["data"]
check("关闭告警并留下处置说明",
      resolved["status"] == "RESOLVED" and resolved.get("resolveNote"),
      resolved.get("resolveNote"))

again_status, _ = call("POST", f"/governance/alerts/{target['id']}/acknowledge", raw=True)
check("已关闭的告警不能再改状态(已解决是终态)", again_status == 409,
      f"HTTP {again_status}")

today = call("GET", "/governance/alerts?today=true&size=100")["data"]
check("「今日告警」页(序号 26)按当天过滤", today["total"] >= 1, f"{today['total']} 条")
history = call("GET", "/governance/alerts?size=100")["data"]
check("「历史告警」页返回全部", history["total"] >= today["total"],
      f"历史 {history['total']} ≥ 今日 {today['total']}")

by_status = call("GET", "/governance/alerts?status=SUPPRESSED&size=100")["data"]
check("可按状态过滤 —— 「哪些被抑制了」是一个要单独看的问题",
      all(a["status"] == "SUPPRESSED" for a in by_status["records"]),
      f"{by_status['total']} 条")


# ── 功能 27:审计日志 ────────────────────────────────────────────────
print("\n【功能 27】审计日志 —— 不可变、只追加,由拦截器自动记录")

audit = call("GET", "/governance/audit?size=200")["data"]
check("审计日志有记录", audit["total"] > 0, f"{audit['total']} 条")

records = audit["records"]
check("记下了谁在什么时候做的",
      all(r.get("username") and r.get("occurredAt") for r in records[:20]),
      records[0].get("username") if records else "")
check("记下了客户端 IP",
      any(r.get("clientIp") for r in records[:20]),
      next((r.get("clientIp") for r in records if r.get("clientIp")), ""))

# 关键:同一张审计表同时覆盖数据集成与数据开发(R4 的第三条证据)
spaces = {r.get("ownerSpace") for r in records if r.get("ownerSpace")}
check("同一张审计表覆盖多个 Space(架构约束 R4 的第三条证据)",
      len(spaces) >= 3, str(sorted(spaces)))

resource_types = {r["resourceType"] for r in records}
check("认得出资源类型(任务、数据源、告警规则……)",
      len(resource_types) >= 3, str(sorted(resource_types)))

check("刚创建的任务被自动审计了 —— 没有在业务代码里手写那一行",
      any(r.get("resourceId") == fail_job["id"] and r["action"] == "CREATE"
          for r in records),
      "")
check("执行操作被记为 EXECUTE 而不是 CREATE(「谁建的」与「谁跑的」是两个问题)",
      any(r.get("resourceId") == fail_job["id"] and r["action"] == "EXECUTE"
          for r in records),
      "")

by_action = call("GET", "/governance/audit?action=CREATE&size=50")["data"]
check("可按操作类型检索",
      all(r["action"] == "CREATE" for r in by_action["records"]),
      f"{by_action['total']} 条 CREATE")

by_resource = call("GET", f"/governance/audit?resourceId={fail_job['id']}&size=50")["data"]
check("可按资源检索 —— 「这个任务被谁动过」",
      by_resource["total"] >= 2,
      f"{by_resource['total']} 条针对该任务的操作")

# 失败的操作同样要审计:那往往才是要查的
call("POST", "/governance/alert-rules", {"name": "", "triggerType": "EXECUTION_FAILED"},
     raw=True)
time.sleep(1)
failed_audit = call("GET", "/governance/audit?succeeded=false&size=50")["data"]
check("失败的操作同样留痕(那往往才是要查的)",
      failed_audit["total"] >= 1, f"{failed_audit['total']} 条失败操作")

# 审计只追加:既没有 PUT 也没有 DELETE。这里直接试一次,靠 404/405 证明它不存在
audit_id = records[0]["id"]
del_status, _ = call("DELETE", f"/governance/audit/{audit_id}", raw=True)
put_status, _ = call("PUT", f"/governance/audit/{audit_id}", {"detail": "篡改"}, raw=True)
check("审计接口没有删除 —— 一条能被删掉的审计记录不是审计记录",
      del_status in (404, 405), f"HTTP {del_status}")
check("审计接口没有编辑",
      put_status in (404, 405), f"HTTP {put_status}")


# ── 清理 ────────────────────────────────────────────────────────────
hook.shutdown()
for r in (rule, scoped):
    call("DELETE", f"/governance/alert-rules/{r['id']}")
for c in (channel, dead, email):
    call("DELETE", f"/governance/channels/{c['id']}")
for j in (fail_job, other_job):
    call("DELETE", f"/jobs/{j['id']}")

print("\n" + "=" * 74)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P4 全部通过")
print("=" * 74)
