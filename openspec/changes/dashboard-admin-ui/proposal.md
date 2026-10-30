## Why

后端已经支持看板版本、ACL 和问题项排序/布局字段，但前端只展示列表和版本号；数据管家无法在看板内管理权限或调整问题顺序。

本变更补齐 P0 的看板治理 UI。

## What Changes

- 看板详情增加 ACL 抽屉：
  - 列表 READ/MANAGE
  - 添加/移除用户
- 问题项支持上移/下移（调用 PATCH）
- 版本弹窗展示版本快照摘要
- 不做版本回滚（后端未实现）

## Capabilities

### New Capabilities

- `insight-dashboard-admin`: 看板 ACL、版本查看与问题项排序。

### Modified Capabilities

无。

## Impact

- 前端：`views/insight/DashboardView.vue`、`api/insight.ts`
- 后端：消费现有 `/api/v1/insight/dashboards/{id}/acl|versions|items`
- 依赖：`frontend-p0-foundation`
