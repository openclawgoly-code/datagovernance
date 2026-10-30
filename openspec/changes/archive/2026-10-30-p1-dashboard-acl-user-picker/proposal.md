## Why

P0 看板 ACL 靠手输用户名，拼错只能在保存后才发现；平台用户列表又是全局的，直接复用会暴露非当前空间用户。

P1 需要可搜索、只限当前空间成员的用户选择器。

## What Changes

- 新增看板 ACL 候选端点：
  - `GET /api/v1/insight/dashboards/acl-candidates?keyword=`
  - 只返回当前 workspace 成员、状态 ACTIVE 的用户
  - 按用户名或显示名模糊搜索，最多 20 条
- ACL 抽屉将用户名输入替换为远程搜索选择器
- 选择用户后选择 READ/MANAGE 并添加；已授权用户不出现在候选中
- 仍使用 `insight:dashboard:manage` 权限；不新增权限码

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `insight-dashboard-admin`: 看板 ACL 添加从手输用户名改为当前空间成员搜索选择，并新增候选端点约束。

## Impact

- 前端：`views/insight/DashboardView.vue`、`api/insight.ts`、`types/insight.ts`
- 后端：`InsightDashboardController`、`InsightDashboardService`，注入 Workspace/User 服务
- 数据库：无迁移
- 依赖：`dashboard-admin-ui`
- 非目标：不做角色继承 ACL、不做跨空间授权、不做版本回滚、不做平台全局用户选择器
