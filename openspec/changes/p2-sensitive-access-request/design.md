## Context

敏感标签由 `SensitivityService.sensitiveColumnNames()` 提供给 Query Gateway，`InsightMasking` 按字段名/别名做兜底脱敏。当前没有“某个用户临时看明文”的合规通道。

P2 先做**敏感类别 + 最高级别**的 workspace 级临时授权；资产级/行级 ACL 需要更完整的策略引擎，留到 P3。这个范围能直接接入现有脱敏链路，而不是只做一个没有执行效果的审批表。

## Goals / Non-Goals

**Goals:**

- 申请人填写原因、类别、最高级别、时长；
- 有审批权限的用户审批/拒绝；
- 审批通过后产生带到期时间的 grant；
- Query Gateway 对有效 grant 覆盖的敏感列不再脱敏；
- 申请、审批、grant 使用可审计；
- 过期后自动失效（惰性判断）。

**Non-Goals:**

- 资产级/行级/列级 ACL；
- 外部 IdP/统一审批中心；
- 多人会签/委托审批；
- 永久授权；
- 自动续期；
- 对 SQL Console/文件下载等其他出口做 grant 接入（P3 统一 Policy Enforcement Point）。

## Decisions

### 1. 申请与 grant 分表

```text
gv_access_request
  id, workspace_id, requester,
  category, max_level, reason, duration_hours,
  status PENDING/APPROVED/REJECTED/CANCELLED,
  approver, decided_at, decision_note,
  created_at, updated_at

gv_access_grant
  id, workspace_id, request_id, username,
  category, max_level, expires_at,
  revoked_at, revoked_by, created_at
```

- 一个 APPROVED 请求创建一条 grant；
- 到期不删 grant，读取时按 `expires_at` 惰性判断，列表显示 EXPIRED；
- 撤销写 `revoked_at/revoked_by`，不物理删除。

### 2. 审批约束

- 申请人必须有 `metadata:access:request`；
- 审批人必须有 `metadata:access:approve`；
- 禁止审批自己的申请；
- 审批人必须是当前 workspace 成员；
- 状态只能从 `PENDING` 进入 APPROVED/REJECTED/CANCELLED；
- duration 1–168 小时；reason 10–500 字；level L1–L4；category 必须是 PII/FINANCIAL/MEDICAL/CREDENTIAL。

### 3. 脱敏链路

在 `InsightQueryService.ask` 中：

1. 取当前用户的 active grants；
2. 取敏感列（扩展 `SensitiveColumnView` 带 category/level）；
3. 过滤掉被 grant 覆盖的列：
   - `grant.category == column.category` 且 `levelRank(column.level) <= levelRank(grant.maxLevel)`；
4. 剩余敏感列交给 `InsightMasking`。

这样明文访问经过同一条 Query Gateway，导出 CSV/XLSX 也复用 ask，不会出现“页面能看、导出却明文”的旁路。

**限制必须明示**：P2 grant 是 workspace 内同类敏感数据的临时明文权限，不绑定单个资产；真正的资产级授权在 P3。

### 4. 查询审计

- 查询事实仍记录 `maskedColumns`（实际脱敏列）；
- 在审计 detail 增加 `grants=[grantId...]`，让“为什么这次没脱敏”可解释；
- grant 使用不单独写一行审计，避免每次查询放大成两条；申请/审批/撤销各自写审计。

### 5. API 与权限

```text
POST /metadata/access-requests                       access:request
GET  /metadata/access-requests?scope=mine|pending    request / approve
POST /metadata/access-requests/{id}/approve          access:approve
POST /metadata/access-requests/{id}/reject           access:approve
POST /metadata/access-requests/{id}/cancel           request（仅本人、PENDING）
GET  /metadata/access-grants                         access:request（本人）或 approve
```

新增权限：

- `metadata:access:request`：admin/developer/viewer
- `metadata:access:approve`：admin/developer

### 6. 前端

- `/governance/access-requests`：
  - 我的申请：状态、类别/级别、原因、有效期、grant 状态；
  - 待审批（有 approve）：申请人、原因、时长、批准/拒绝；
  - 申请表单：类别、最高级别、时长、原因；
- 资产详情敏感 Tab 的“申请临时访问”预填当前标签的 category/level；
- 过期/撤销的 grant 显示为不可用；
- 只读用户无审批按钮；无 request 权限路由 403。

### 7. 审计映射

`/access-requests` → `ACCESS_REQUEST/GOVERNANCE`；approve/reject/cancel 记 UPDATE，创建记 CREATE。

## Risks / Trade-offs

- [workspace 级 grant 范围过宽] → UI 明示范围，P3 做资产/行级 ACL。
- [grant 与字段名匹配的既有限制] → 沿用 P1 的列名/别名匹配；不新增“误放行”风险，但需在文档说明。
- [审批人自己批自己] → 服务端禁止，即使平台管理员也不允许。
- [过期任务没有调度] → 读取时惰性过期；P3 可加定时清理/提醒。
- [导出绕过] → 导出复用 ask，与页面同一路径。
- [审批积压] → 页面按 pending 排序，P3 接通知。

## Migration Plan

1. V30 建 request/grant 表、权限；
2. 后端发布申请/审批端点；
3. Query Gateway 接入 active grant 过滤与审计 detail；
4. 前端发布资产入口和申请页；
5. 回滚前端隐藏入口；已有 grant 仍会在后端生效，需要运维撤销。

## Open Questions

- P3 是否改为资产/列级 grant；
- 是否需要审批到期提醒；
- 是否需要与 OA/统一审批集成。
