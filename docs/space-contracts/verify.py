#!/usr/bin/env python3
"""Space Contract 一致性校验。

用法: python3 docs/space-contracts/verify.py
检查五件事:
  1. 8 份 YAML 语法合法且字段齐全
  2. depends_on / consumed_by 双向互为镜像
  3. 同步依赖子图无环(异步事件边允许成环 —— 那正是事件用来解环的原因)
  4. 每个被消费的事件都有生产者
  5. 35 项功能全部有且仅有一个 Owner Space
"""
import sys, pathlib, yaml

HERE = pathlib.Path(__file__).parent
NAMES = ["ui", "metadata", "control", "runtime", "data", "governance", "platform", "intelligence"]
REQUIRED = ["name", "purpose", "category", "core_objects", "states", "events_produced",
            "events_consumed", "storage", "runtime", "depends_on", "depends_on_sync",
            "consumed_by", "must_not_do", "api_boundary", "consistency", "versioning"]

errors, warnings = [], []
spaces = {}

# --- 1. 加载与字段完整性 ---
for n in NAMES:
    f = HERE / f"{n}.yaml"
    if not f.exists():
        errors.append(f"[1] 缺少契约文件 {n}.yaml"); continue
    try:
        spaces[n] = yaml.safe_load(f.read_text(encoding="utf-8"))["space"]
    except Exception as e:
        errors.append(f"[1] {n}.yaml 解析失败: {e}"); continue
    for k in REQUIRED:
        if k not in spaces[n]:
            errors.append(f"[1] {n}.yaml 缺少必填字段 '{k}'")

# --- 2. depends_on / consumed_by 互为镜像 ---
for n, sp in spaces.items():
    for dep in sp.get("depends_on") or []:
        if dep not in spaces:
            errors.append(f"[2] {n}.depends_on 指向未知 Space '{dep}'"); continue
        if n not in (spaces[dep].get("consumed_by") or []):
            errors.append(f"[2] 单边声明: {n} depends_on {dep},但 {dep}.consumed_by 不含 {n}")
    for c in sp.get("consumed_by") or []:
        if c not in spaces:
            errors.append(f"[2] {n}.consumed_by 含未知 Space '{c}'"); continue
        if n not in (spaces[c].get("depends_on") or []):
            errors.append(f"[2] 单边声明: {n}.consumed_by 含 {c},但 {c}.depends_on 不含 {n}")

# --- 3. 同步依赖子图无环 ---
for n, sp in spaces.items():
    extra = set(sp.get("depends_on_sync") or []) - set(sp.get("depends_on") or [])
    if extra:
        errors.append(f"[3] {n}.depends_on_sync 含不在 depends_on 中的项: {sorted(extra)}")

WHITE, GREY, BLACK = 0, 1, 2
color = {n: WHITE for n in spaces}
cycles = []
def dfs(n, path):
    color[n] = GREY
    for d in spaces[n].get("depends_on_sync") or []:
        if d not in spaces: continue
        if color[d] == GREY:
            cycles.append(" -> ".join(path + [d]))
        elif color[d] == WHITE:
            dfs(d, path + [d])
    color[n] = BLACK
for n in spaces:
    if color[n] == WHITE:
        dfs(n, [n])
for c in cycles:
    errors.append(f"[3] 同步依赖环: {c}")

# 拓扑构建顺序(同步子图)
if not cycles:
    indeg = {n: 0 for n in spaces}
    for n, sp in spaces.items():
        for d in sp.get("depends_on_sync") or []:
            if d in indeg: indeg[n] += 1
    order, remaining = [], dict(indeg)
    while remaining:
        ready = sorted([n for n, v in remaining.items() if v == 0])
        if not ready: break
        order.append(ready)
        for n in ready: del remaining[n]
        for n in list(remaining):
            remaining[n] = sum(1 for d in (spaces[n].get("depends_on_sync") or [])
                               if d in remaining)
    build_order = order
else:
    build_order = None

# --- 4. 事件生产者存在性 ---
produced = set()
for sp in spaces.values():
    produced |= set(sp.get("events_produced") or [])
for n, sp in spaces.items():
    for e in sp.get("events_consumed") or []:
        if e not in produced:
            errors.append(f"[4] {n} 消费的事件 '{e}' 没有任何 Space 生产")
orphan = produced - {e for sp in spaces.values() for e in (sp.get("events_consumed") or [])}
if orphan:
    warnings.append(f"[4] 无消费者的事件({len(orphan)} 个,允许存在,通常由 UI 订阅或留作扩展): "
                    + ", ".join(sorted(orphan)))

# --- 5. 35 项功能 Owner 覆盖 ---
owner = {}
for n, sp in spaces.items():
    for fid in sp.get("owns_features") or []:
        if isinstance(fid, int):
            owner.setdefault(fid, []).append(n)
missing = [i for i in range(1, 36) if i not in owner]
multi = {k: v for k, v in owner.items() if len(v) > 1}
if missing:
    errors.append(f"[5] 无 Owner 的功能项: {missing}")
for k, v in multi.items():
    if k == 17:
        warnings.append(f"[5] 功能项 17 有 2 个 Owner {v} —— 刻意的双栖设计(规则定义/规则执行),已在文档 R6 说明")
    else:
        errors.append(f"[5] 功能项 {k} 有多个 Owner: {v}")

# --- 输出 ---
print(f"已加载 {len(spaces)}/8 份契约")
if build_order:
    print("同步子图构建顺序: " + "  ->  ".join("[" + ", ".join(g) + "]" for g in build_order))
print(f"功能项覆盖: {len(owner)}/35   事件类型: 生产 {len(produced)} 种")
for w in warnings: print("WARN ", w)
for e in errors:   print("ERROR", e)
print()
print("FAILED" if errors else "PASSED")
sys.exit(1 if errors else 0)
