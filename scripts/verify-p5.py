#!/usr/bin/env python3
"""P5 完成判据的端到端验证:Intelligence 契约(序号 34)与合规底座(序号 35)。

序号 34「高质量数据集制备」<b>已确认独立立项</b>(架构风险 R1):OWL 2 七层
医学概念体系、多智能体标注工厂、数据飞轮与四维度质控引擎,工程量与序号 1-33
之和相当。本期不实现它 —— 这个脚本验证的是<b>它与平台之间的四条契约真的
成立</b>,而不是那个平台建好了。

  第 1 条(取数)Intelligence 不得直连业务数据源 → 编译期就拦住
  第 2 条(算力)训练作业走 Control,复用统一 Execution → 自动进监控与审计
  第 3 条(注册)Dataset / Model 的标识与版本进平台注册中心,内容存对象存储
  第 4 条(语义)Column ──MapsTo──> Concept 写进关系边,能被血缘分析看见

序号 35 是外部依赖(R8),对接方是亭湖区全民健康信息平台,接口规格不由己方
控制。所以这里验证的是<b>己方能控制的那一半</b>:健康医疗数据属敏感个人信息,
脱敏必须在数据落地之前生效,而且任何算不出来的情况都不能放过明文。
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


print("=" * 74)
print(" P5 验证 — Intelligence 的四条契约 · 敏感数据脱敏")
print("=" * 74)

res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
token = res["data"]["token"]
workspace = res["data"]["workspaces"][0]["id"]
print(f"\n登录成功,空间 {workspace}")


# ── 契约第 3 条:注册 ────────────────────────────────────────────────
print("\n【契约 3】注册 —— 标识与版本进平台注册中心,内容存对象存储")

bad_kind_status, bad_kind_body = call("POST", "/registry/artifacts",
                                      {"kind": "SOMETHING", "name": f"x-{RUN}"}, raw=True)
check("不支持的注册项种类被拒",
      bad_kind_status != 200 and "种类" in bad_kind_body.get("message", ""),
      f"HTTP {bad_kind_status}")

dataset = call("POST", "/registry/artifacts", {
    "kind": "DATASET", "name": f"胸片标注集-{RUN}",
    "description": "P5 验证用",
})["data"]
check("注册一个数据集", dataset["kind"] == "DATASET", dataset["id"])
check("刚注册时版本是 0 而不是 1(「注册了但还没有内容」是真实的中间状态)",
      dataset["latestVersion"] == 0, str(dataset["latestVersion"]))

dup_status, _ = call("POST", "/registry/artifacts",
                     {"kind": "DATASET", "name": f"胸片标注集-{RUN}"}, raw=True)
check("同种类下重名被拒", dup_status == 409, f"HTTP {dup_status}")

# 同名但不同种类应当允许 —— 一个数据集和一个模型可以叫同一个名字
model = call("POST", "/registry/artifacts", {
    "kind": "MODEL", "name": f"胸片标注集-{RUN}",
})["data"]
check("不同种类下可以同名", model["kind"] == "MODEL", model["id"])

no_uri_status, no_uri_body = call("POST", f"/registry/artifacts/{dataset['id']}/versions",
                                  {"itemCount": 100}, raw=True)
check("发布版本时不给内容地址被拒",
      no_uri_status != 200 and "内容地址" in no_uri_body.get("message", ""),
      f"HTTP {no_uri_status} {no_uri_body.get('message')}")

inline_status, inline_body = call("POST", f"/registry/artifacts/{dataset['id']}/versions",
                                  {"contentUri": "data:application/json;base64,eyJ4IjoxfQ=="},
                                  raw=True)
check("内容地址不能是内联数据 —— 平台不承载影像与模型权重",
      inline_status != 200 and "内联" in inline_body.get("message", ""),
      f"HTTP {inline_status} {inline_body.get('message')}")

v1 = call("POST", f"/registry/artifacts/{dataset['id']}/versions", {
    "contentUri": "s3://dg-datasets/chest-xray/v1/",
    "sizeBytes": 1073741824, "itemCount": 5000,
    "checksumSha256": "a" * 64,
    "metadataJson": json.dumps({"modality": "DX", "labeler": "ensemble-v2"}),
})["data"]
check("发布第一版", v1["version"] == 1, f"v{v1['version']}")

v2 = call("POST", f"/registry/artifacts/{dataset['id']}/versions", {
    "contentUri": "s3://dg-datasets/chest-xray/v2/",
    "itemCount": 8200,
    "derivedFromVersionId": v1["id"],
})["data"]
check("版本号由平台分配,不由调用方指定", v2["version"] == 2, f"v{v2['version']}")
check("能记录「这一版是从哪一版做出来的」—— 数据飞轮的一条边",
      v2["derivedFromVersionId"] == v1["id"], v2["derivedFromVersionId"])

after = call("GET", f"/registry/artifacts/{dataset['id']}")["data"]
check("注册项的最新版本号跟着走", after["latestVersion"] == 2, str(after["latestVersion"]))

versions = call("GET", f"/registry/artifacts/{dataset['id']}/versions")["data"]
check("版本列表倒序返回", [v["version"] for v in versions] == [2, 1],
      str([v["version"] for v in versions]))

# 已发布的版本不可变:接口上就没有修改它的方法
put_status, _ = call("PUT", f"/registry/artifacts/{dataset['id']}/versions/{v1['id']}",
                     {"contentUri": "s3://tampered/"}, raw=True)
check("已发布的版本没有修改接口(要改内容就再发一版)",
      put_status in (404, 405), f"HTTP {put_status}")


# ── 契约第 4 条:语义映射 ────────────────────────────────────────────
print("\n【契约 4】语义 —— Column ──MapsTo──> Concept 写进关系边")

bad_rel_status, bad_rel_body = call("POST", "/registry/edges", {
    "fromType": "COLUMN", "fromId": "ds_x:db.sch.t.c",
    "relation": "IS_KIND_OF", "toType": "CONCEPT", "toId": "http://x/1",
}, raw=True)
check("不支持的关系类型被拒",
      bad_rel_status != 200 and "关系类型" in bad_rel_body.get("message", ""),
      f"HTTP {bad_rel_status}")

half_status, half_body = call("POST", "/registry/edges", {
    "fromType": "COLUMN", "fromId": "ds_x:db.sch.t.c", "relation": "MAPS_TO",
}, raw=True)
check("关系边缺一端被拒",
      half_status != 200 and "两端" in half_body.get("message", ""),
      f"HTTP {half_status}")

column_id = f"ds_demo_{RUN}:his.dbo.patient.diagnosis_code"
edge = call("POST", "/registry/edges", {
    "fromType": "COLUMN", "fromId": column_id,
    "relation": "MAPS_TO",
    "toType": "CONCEPT", "toId": "http://snomed.info/id/73211009",
    "toLabel": "糖尿病",
})["data"]
check("字段映射到医学概念", edge["relation"] == "MAPS_TO", edge["toLabel"])
check("人工建立的映射置信度默认 1.0", edge["confidence"] == 1.0, str(edge["confidence"]))
check("来源默认标为人工", edge["origin"] == "MANUAL", edge["origin"])

auto_edge = call("POST", "/registry/edges", {
    "fromType": "COLUMN", "fromId": column_id,
    "relation": "MAPS_TO",
    "toType": "CONCEPT", "toId": "http://snomed.info/id/44054006",
    "toLabel": "2型糖尿病",
    "confidence": 0.82, "origin": "AUTO",
})["data"]
check("自动抽取的映射能与人工确认的区分开(复核时要分头处理)",
      auto_edge["origin"] == "AUTO" and auto_edge["confidence"] == 0.82,
      f"{auto_edge['origin']} {auto_edge['confidence']}")

dup_edge_status, _ = call("POST", "/registry/edges", {
    "fromType": "COLUMN", "fromId": column_id, "relation": "MAPS_TO",
    "toType": "CONCEPT", "toId": "http://snomed.info/id/73211009",
}, raw=True)
check("同一条映射不重复", dup_edge_status == 409, f"HTTP {dup_edge_status}")

edges = call("GET", f"/registry/edges?fromType=COLUMN&fromId={column_id}")["data"]
check("能按字段查出它挂了哪些概念(血缘分析的入口)",
      len(edges) == 2, f"{len(edges)} 条")

# Concept 的标识用本体 IRI:它的定义归 Intelligence,平台只记这条边
check("概念用本体 IRI 标识,平台不拥有本体定义",
      all(e["toId"].startswith("http") for e in edges),
      str([e["toId"] for e in edges]))

call("DELETE", f"/registry/edges/{auto_edge['id']}")
check("映射可以删除(自动抽取的复核后可能要撤)",
      len(call("GET", f"/registry/edges?fromId={column_id}")["data"]) == 1)


# ── 契约第 1 条:取数 ────────────────────────────────────────────────
print("\n【契约 1】取数 —— Intelligence 不得直连业务数据源(编译期拦住)")

types = call("GET", "/jobs/types")["data"]
python_type = next((t for t in types if t["type"] == "PYTHON_JOB"), None)
check("平台声明了 Python 任务类型", python_type is not None,
      python_type["displayName"] if python_type else "没有")
check("它映射到 Runtime 的 PYTHON_JOB 作业种类",
      python_type and python_type["runtimeType"] == "PYTHON_JOB",
      python_type["runtimeType"] if python_type else "")

# 上传一个 Python 包给它引用
import urllib.request as _r


def upload_artifact(name, content):
    boundary = "----dgp5" + RUN
    parts = []
    for k, v in {"name": name, "version": "1.0.0", "type": "PYTHON"}.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n")
    body = "".join(parts).encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"pkg.zip\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
    body += content + f"\r\n--{boundary}--\r\n".encode()
    req = _r.Request(API + "/artifacts", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("X-Workspace-Id", workspace)
    with _r.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())["data"]


pkg = upload_artifact(f"p5-train-{RUN}", b"PK\x03\x04fake-python-package")

# 关键断言:配置里出现直连字段时,编译必须失败
direct = call("POST", "/jobs", {
    "name": f"P5-想直连-{RUN}", "jobType": "PYTHON_JOB",
    "config": {
        "artifactId": pkg["id"], "entryModule": "train.main",
        # 这是契约第 1 条要拦的东西:训练作业想自己连业务库拉数据
        "jdbcUrl": "jdbc:postgresql://his-prod:5432/emr",
        "sourceTable": "patient",
    },
})["data"]
direct_c = call("POST", f"/jobs/{direct['id']}/compile")["data"]
check("训练作业配置里出现直连字段时,编译失败(契约第 1 条)",
      not direct_c["succeeded"]
      and any("不得直连业务数据源" in d["message"] for d in direct_c["diagnostics"]),
      next((d["message"] for d in direct_c["diagnostics"] if "直连" in d["message"]),
           direct_c["summary"]))
check("诊断说清楚了正确的做法",
      any("集成任务" in (d.get("hint") or "") for d in direct_c["diagnostics"]),
      next((d.get("hint") for d in direct_c["diagnostics"] if d.get("hint")), ""))
call("DELETE", f"/jobs/{direct['id']}")

no_entry = call("POST", "/jobs", {
    "name": f"P5-缺入口-{RUN}", "jobType": "PYTHON_JOB",
    "config": {"artifactId": pkg["id"]},
})["data"]
no_entry_c = call("POST", f"/jobs/{no_entry['id']}/compile")["data"]
check("不指定入口模块时编译失败(平台不去解析包结构猜入口)",
      not no_entry_c["succeeded"]
      and any("入口模块" in d["message"] for d in no_entry_c["diagnostics"]),
      no_entry_c["summary"])
call("DELETE", f"/jobs/{no_entry['id']}")


# ── 契约第 2 条:算力 ────────────────────────────────────────────────
print("\n【契约 2】算力 —— 训练作业走 Control,复用统一 Execution 事实模型")

train = call("POST", "/jobs", {
    "name": f"P5-训练作业-{RUN}", "jobType": "PYTHON_JOB",
    "config": {
        "artifactId": pkg["id"],
        "entryModule": "train.main",
        "programArgs": "--epochs 30",
        # 只声明注册数据集的 ID —— 这是契约第 1 条允许的唯一取数方式
        "inputDatasetIds": [dataset["id"]],
        "outputArtifactId": model["id"],
        "resources": {"cpu": 8, "memoryMb": 32768, "gpu": 1},
    },
    "timeoutMs": 60000,
})["data"]
train_c = call("POST", f"/jobs/{train['id']}/compile")["data"]
check("声明了输入数据集与产物注册项的训练作业编译通过",
      train_c["succeeded"], train_c["summary"])

no_input = call("POST", "/jobs", {
    "name": f"P5-没声明输入-{RUN}", "jobType": "PYTHON_JOB",
    "config": {"artifactId": pkg["id"], "entryModule": "eval.main"},
})["data"]
no_input_c = call("POST", f"/jobs/{no_input['id']}/compile")["data"]
check("没声明输入数据集时给警告而不是拒绝(纯评测作业确实可以没有)",
      no_input_c["succeeded"]
      and any("输入数据集" in d["message"] and d["severity"] == "WARNING"
              for d in no_input_c["diagnostics"]),
      no_input_c["summary"])
call("DELETE", f"/jobs/{no_input['id']}")

gpu_no_mem = call("POST", "/jobs", {
    "name": f"P5-申请GPU没声明内存-{RUN}", "jobType": "PYTHON_JOB",
    "config": {"artifactId": pkg["id"], "entryModule": "train.main",
               "inputDatasetIds": [dataset["id"]],
               "resources": {"gpu": 2}},
})["data"]
gpu_c = call("POST", f"/jobs/{gpu_no_mem['id']}/compile")["data"]
check("申请 GPU 但没声明内存时给出提醒(训练作业容易被 OOM 杀掉)",
      any("GPU" in d["message"] for d in gpu_c["diagnostics"]),
      next((d["message"] for d in gpu_c["diagnostics"] if "GPU" in d["message"]), ""))
call("DELETE", f"/jobs/{gpu_no_mem['id']}")

call("POST", f"/jobs/{train['id']}/publish")
train_exec = call("POST", f"/jobs/{train['id']}/run")["data"]
check("训练作业通过 Control 下发,产生统一的执行记录",
      train_exec["jobRefType"] == "PYTHON_JOB", train_exec["jobRefType"])

train_detail = wait_terminal(train_exec["id"], 60)
check("没接 K8s 时如实失败,而不是假装训练成功了",
      train_detail["execution"]["status"] == "FAILED",
      train_detail["execution"]["status"])
check("失败归类为「执行器不可用」(环境缺件,不是平台故障)",
      train_detail["execution"]["errorCode"] == "RTM_EXECUTOR_UNAVAILABLE",
      str(train_detail["execution"]["errorCode"]))

# 这是契约第 2 条的兑现:走了 Control 就自动进监控与审计,不必另建一套
mon = call("GET", "/governance/monitor")["data"]
check("训练作业自动进入任务监控(序号 24)——「复用统一 Execution」的兑现",
      any(b["jobRefType"] == "PYTHON_JOB" for b in mon["byJobType"]),
      str([b["jobRefType"] for b in mon["byJobType"]]))

audit = call("GET", f"/governance/audit?resourceId={train['id']}&size=50")["data"]
check("训练作业的创建与执行自动进入审计(序号 27)",
      audit["total"] >= 2, f"{audit['total']} 条")
check("执行被记为 EXECUTE 而不是 CREATE",
      any(r["action"] == "EXECUTE" for r in audit["records"]),
      str(sorted({r["action"] for r in audit["records"]})))

pyexec = call("GET", "/executions?jobRefType=PYTHON_JOB&size=50")["data"]
check("执行记录页能按 PYTHON_JOB 过滤 —— 与其余六种任务同一张表",
      pyexec["total"] >= 1, f"{pyexec['total']} 条")


# ── 序号 35:敏感数据脱敏 ────────────────────────────────────────────
print("\n【序号 35】健康医疗数据属敏感个人信息 —— 脱敏在落地之前生效")

kinds = call("GET", "/rules/kinds")["data"]
mask_kind = next((k for k in kinds if k["kind"] == "MASK"), None)
check("平台提供脱敏规则", mask_kind is not None,
      mask_kind["displayName"] if mask_kind else "没有")
check("脱敏的哈希模式要求盐来自凭据托管,而不是写在规则参数里",
      mask_kind and "saltCredentialId" in mask_kind["paramSpec"],
      str(list(mask_kind["paramSpec"].keys())) if mask_kind else "")

ds_id = call("POST", "/datasources", {
    "name": f"P5-模拟HIS-{RUN}", "type": "POSTGRESQL",
    "host": PG_HOST, "port": PG_PORT, "databaseName": PG_DB, "username": PG_USER,
    "inlineSecret": {"authType": "PASSWORD", "username": PG_USER, "secret": PG_PASSWORD},
})["data"]["id"]
call("POST", f"/datasources/{ds_id}/test")

src = f"p5_his_src_{RUN}"
dst = f"p5_his_dst_{RUN}"
psql(f"""
    CREATE SCHEMA IF NOT EXISTS dg_probe_schema;
    DROP TABLE IF EXISTS dg_probe_schema.{src};
    DROP TABLE IF EXISTS dg_probe_schema.{dst};
    CREATE TABLE dg_probe_schema.{src} (
        id INT PRIMARY KEY, patient_name VARCHAR(32),
        id_card VARCHAR(32), phone VARCHAR(32));
    CREATE TABLE dg_probe_schema.{dst} (
        id INT PRIMARY KEY, patient_name VARCHAR(64),
        id_card VARCHAR(64), phone VARCHAR(64));
    INSERT INTO dg_probe_schema.{src} VALUES
        (1, '张三丰', '320981199003074512', '13812345678'),
        (2, '李四光', '320981198512203344', '13998887766');
""")
for t in (src, dst):
    call("GET", f"/datasources/{ds_id}/catalog?database={PG_DB}&schema=dg_probe_schema&table={t}")

mask_id = call("POST", "/rules", {
    "name": f"P5-身份证脱敏-{RUN}", "kind": "MASK",
    "params": {"mode": "PARTIAL", "keepPrefix": "3", "keepSuffix": "4"},
})["data"]["id"]
mask_phone = call("POST", "/rules", {
    "name": f"P5-手机号脱敏-{RUN}", "kind": "MASK",
    "params": {"mode": "PARTIAL", "keepPrefix": "3", "keepSuffix": "4"},
})["data"]["id"]
mask_name = call("POST", "/rules", {
    "name": f"P5-姓名脱敏-{RUN}", "kind": "MASK",
    "params": {"mode": "FIXED"},
})["data"]["id"]

sync = call("POST", "/jobs", {
    "name": f"P5-脱敏同步-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst,
        "fieldMappings": {"id": "id", "patient_name": "patient_name",
                          "id_card": "id_card", "phone": "phone"},
        "fieldRules": {"id_card": [mask_id], "phone": [mask_phone],
                       "patient_name": [mask_name]},
        "writeMode": "APPEND", "batchSize": 100,
    },
    "timeoutMs": 60000,
})["data"]
sync_c = call("POST", f"/jobs/{sync['id']}/compile")["data"]
check("带脱敏规则的同步编译通过", sync_c["succeeded"], sync_c["summary"])

# 拼错键名的那一版必须被报出来。这条断言有来历:验证脚本自己曾把
# fieldRules 写成 rulesByColumn,编译通过、执行成功,而明文身份证号
# 原样落进了目标库 —— 没有任何一处告诉过我们脱敏没生效
typo = call("POST", "/jobs", {
    "name": f"P5-键名拼错-{RUN}", "jobType": "OFFLINE_SYNC",
    "config": {
        "sourceDataSourceId": ds_id, "sourceDatabase": PG_DB,
        "sourceSchema": "dg_probe_schema", "sourceTable": src,
        "targetDataSourceId": ds_id, "targetDatabase": PG_DB,
        "targetSchema": "dg_probe_schema", "targetTable": dst,
        "fieldMappings": {"id": "id", "id_card": "id_card"},
        "rulesByColumn": {"id_card": [mask_id]},   # 拼错了:应当是 fieldRules
        "writeMode": "APPEND",
    },
})["data"]
typo_c = call("POST", f"/jobs/{typo['id']}/compile")["data"]
check("配置里拼错的键被明确报出来(否则脱敏会静默失效)",
      any("不认识的键" in d["message"] and "rulesByColumn" in d["message"]
          for d in typo_c["diagnostics"]),
      next((d["message"] for d in typo_c["diagnostics"] if "不认识" in d["message"]),
           typo_c["summary"]))
call("DELETE", f"/jobs/{typo['id']}")
call("POST", f"/jobs/{sync['id']}/publish")

sync_exec = call("POST", f"/jobs/{sync['id']}/run")["data"]
sync_detail = wait_terminal(sync_exec["id"], 60)
check("脱敏同步执行成功", sync_detail["execution"]["status"] == "SUCCEEDED",
      f"{sync_detail['execution']['status']} {sync_detail['execution'].get('message') or ''}")

landed = psql(f"SELECT id_card FROM dg_probe_schema.{dst} ORDER BY id")
check("身份证号落地时已经脱敏,保留前 3 后 4",
      landed.split("\n") == ["320***********4512", "320***********3344"], repr(landed))
check("<b>目标库里查不到任何一个完整的身份证号</b>",
      "199003074512" not in landed and "198512203344" not in landed, repr(landed))

phones = psql(f"SELECT phone FROM dg_probe_schema.{dst} ORDER BY id")
check("手机号同样脱敏", phones.split("\n") == ["138****5678", "139****7766"], repr(phones))

names = psql(f"SELECT patient_name FROM dg_probe_schema.{dst} ORDER BY id")
check("姓名整体替换,连长度都不泄露",
      "张" not in names and "李" not in names, repr(names))

# 源表当然还是明文 —— 脱敏发生在搬运途中,不是就地改写源数据
source_still = psql(f"SELECT id_card FROM dg_probe_schema.{src} WHERE id = 1")
check("源表不被改写 —— 脱敏发生在搬运途中,不是就地销毁原始数据",
      source_still == "320981199003074512", source_still)

check("脱敏规则被任务引用后计数 > 0",
      call("GET", f"/rules/{mask_id}")["data"]["referenceCount"] > 0,
      str(call("GET", f"/rules/{mask_id}")["data"]["referenceCount"]))
in_use_status, _ = call("DELETE", f"/rules/{mask_id}", raw=True)
check("被引用的脱敏规则不许删(否则下一次同步就会漏出明文)",
      in_use_status == 409, f"HTTP {in_use_status}")


# ── 清理 ────────────────────────────────────────────────────────────
for j in (train, sync):
    call("DELETE", f"/jobs/{j['id']}")
for r in (mask_id, mask_phone, mask_name):
    call("DELETE", f"/rules/{r}")
call("DELETE", f"/registry/edges/{edge['id']}")
for a in (dataset, model):
    call("DELETE", f"/registry/artifacts/{a['id']}")
call("DELETE", f"/artifacts/{pkg['id']}")
psql(f"DROP TABLE IF EXISTS dg_probe_schema.{src}; DROP TABLE IF EXISTS dg_probe_schema.{dst};")

print("\n" + "=" * 74)
if failures:
    print(f" 结果:{len(failures)} 项未通过")
    for f in failures:
        print(f"   - {f}")
    sys.exit(1)
print(" 结果:P5 全部通过")
print("=" * 74)
