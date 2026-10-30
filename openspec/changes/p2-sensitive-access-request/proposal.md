## Why

P0/P1 的敏感数据默认脱敏是 workspace 级硬策略，没有“业务确需看明文一段时间”的合规通道。数据管家只能在系统外审批，然后临时改代码/规则，审计链断裂。

本变更提供带原因、时限、审批和审计的临时敏感数据访问申请。P2 的授权粒度是敏感类别 + 最高级别（workspace 内），不做行级/资产级 ACL。

## What Changes

- 新增 `gv_access_request`：申请人、类别、最高级别、原因、时长、状态、审批人
- 新增 `gv_access_grant`：审批通过后的临时明文访问授权
- 新增端点：
  - `POST /api/v1/metadata/access-requests`
  - `GET /api/v1/metadata/access-requests?scope=mine|pending`
  - `POST /api/v1/metadata/access-requests/{id}/approve|reject|cancel`
  - `GET /api/v1/metadata/access-grants`
- 查询网关脱敏前检查当前用户的有效 grant；被 grant 覆盖的敏感列不再脱敏
- 新增 `/governance/access-requests` 页面：我的申请、待审批、有效期
- 资产详情敏感 Tab 增加“申请临时访问”入口，预填类别/级别
- 新增 `metadata:access:request`、`metadata:access:approve` 权限
- 申请、审批、拒绝、取消、授权使用写审计

## Capabilities

### New Capabilities

- `sensitive-access-request`: 敏感数据临时访问申请、审批、授权与到期。

### Modified Capabilities

- `sensitivity-workbench`: 增加从敏感标签发起访问申请的入口（不改变复核语义）。
- `insight-query-history`: 查询脱敏结果可能因有效 grant 减少，审计需可解释（不改变历史结构）。

## Impact

- 前端：新增访问申请页、资产详情入口、菜单、API/类型
- 后端：新增 AccessRequest/Grant entity/mapper/service/controller、V30 迁移；修改 Query Gateway 脱敏前过滤
- 依赖：`sensitivity-workbench`、`query-history-page`
- 非目标：行级/资产级 ACL、外部 IdP、多人审批、永久授权、委托审批、自动续期
