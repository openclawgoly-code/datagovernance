## Context

P0 工作台已经能按权限加载失败执行、未处理告警和待复核敏感标签，但只有三张摘要卡。用户无法统一排序、按类型/严重度筛选，也不能把已经看过的条目暂时移出视野。

三个来源都已经有各自的查询和状态模型：

- 执行：`ExecutionService.list`，状态 `FAILED/TIMEOUT`
- 告警：`AlertService.list`，未处理状态
- 敏感标签：`SensitivityService.list`，`status=SUGGESTED`

P1 不应复制这些对象，也不应把“从收件箱移除”说成“解决告警”或“完成复核”。

## Goals / Non-Goals

**Goals:**

- 一个按权限过滤、按严重度和时间排序的跨来源收件箱；
- 可按来源、状态、严重度、关键词筛选并分页；
- “移出收件箱/恢复”状态持久化；
- 点击条目回到来源页面处理；
- 工作台能进入收件箱。

**Non-Goals:**

- 不做工单流转、SLA、负责人分派；
- 不做通知订阅；
- 不改变执行/告警/敏感标签的来源状态；
- 不做跨空间收件箱；
- 不复制来源事实表；
- 不做自动解决/自动复核。

## Decisions

### 1. 来源聚合 + 状态覆盖

收件箱条目由现有服务实时聚合，不新增来源副本。新增 `gv_inbox_state` 只保存：

```text
workspace_id, source_type, source_id,
state (OPEN/DISMISSED), note, updated_by, updated_at
```

主键 `(workspace_id, source_type, source_id)`。默认无记录视为 `OPEN`。

### 2. 来源与权限

`GET /api/v1/governance/inbox` 受 `governance:inbox:read` 保护，并逐来源检查：

- `EXECUTION`：`runtime:execution:read`
- `ALERT`：`governance:alert:read`
- `SENSITIVITY`：`metadata:sensitivity:read`

没有来源权限的条目既不返回也不计入总数。工作台入口同样按 `governance:inbox:read` 显示。

### 3. 聚合快照与排序

P1 每个来源取最近 200 条，合并后：

- 统一字段：`sourceType/sourceId/title/summary/severity/occurredAt/sourceState/route`
- 严重度映射：
  - 执行 `FAILED` → HIGH，`TIMEOUT` → MEDIUM
  - 告警保留 `CRITICAL/WARNING/INFO`
  - 敏感 L4 → CRITICAL，L3 → HIGH，L2 → MEDIUM，L1 → LOW
- 排序：严重度降序，再按 `occurredAt` 降序
- 先覆盖 dismiss 状态，再筛选 `sourceType/state/severity/keyword`，最后内存分页

P1 的 200 条/来源是明确上限；响应带 `truncated=true` 时页面提示“仅展示最近 200 条/来源”。P2 改为物化收件箱表或跨源 SQL。

### 4. 移除/恢复只改收件箱状态

```text
POST /api/v1/governance/inbox/{sourceType}/{sourceId}/dismiss
POST /api/v1/governance/inbox/{sourceType}/{sourceId}/reopen
```

- 需要 `governance:inbox:manage` 与对应来源读权限；
- 只 upsert `gv_inbox_state`，不调用告警 resolve、不改变执行状态、不改变敏感标签；
- 默认列表 `state=OPEN`，选择 `DISMISSED` 可查看并恢复；
- 来源对象已消失时，状态行保留但不展示。

### 5. 审计

- `AuditInterceptor` 增加 `/inbox` → `INBOX/GOVERNANCE`；
- `dismiss` / `reopen` 记为 `UPDATE`；
- 资源 ID 使用 `{sourceType}:{sourceId}`，请求摘要保留路径。

### 6. 前端

- 新增 `/workbench/inbox`，权限 `governance:inbox:read`；
- 工作台“需要处理”标题旁增加“打开收件箱”；有权限时快捷入口也加入口；
- 列表展示来源标签、严重度、标题、摘要、时间、来源状态；
- 行操作：打开来源详情、移出收件箱/恢复（有 manage 权限）；
- 筛选：来源、状态、严重度、关键词；分页；
- 来源链接：
  - 执行 `/ops/executions`
  - 告警 `/ops/alerts`
  - 敏感 `/metadata/sensitivity?datasetId=...&reviewId=...`

## Risks / Trade-offs

- [跨源分页只能基于 3×200 快照] → 明确提示 truncated；P2 物化或服务端 union。
- [dismiss 被误解为解决] → UI 文案用“移出收件箱”，来源状态仍显示；帮助文案说明不会改变来源。
- [来源状态变化后 dismissed 行残留] → 列表按来源实时查询，残留状态不展示。
- [权限组合复杂] → 后端逐来源检查，前端只显示允许的来源筛选。
- [同一来源重复条目] → 以 `sourceType + sourceId` 去重。

## Migration Plan

1. V26 建 `gv_inbox_state`，种权限并授权内置角色；
2. 发布后端收件箱端点；
3. 发布前端路由与工作台入口；
4. 回滚前端隐藏入口，状态表可保留。

## Open Questions

- P2 是否需要负责人/截止时间/评论；
- 是否需要批量移出；
- 是否需要将收件箱摘要替换工作台三张卡。
