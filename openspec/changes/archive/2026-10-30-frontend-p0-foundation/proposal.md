## Why

P0 涉及资产详情、血缘、敏感复核、查询历史、看板治理和工作台六个页面。如果每个页面各自建路由、筛选器、状态标签和 API 类型，会重复实现同一套交互，也无法保证权限与设计元语一致。

本变更先建立 P0 的共同底座：后端菜单种子、静态路由、共享组件、API 客户端与类型。

## What Changes

- 新增 V25 菜单/权限种子：
  - `menu:metadata:lineage`
  - `menu:metadata:sensitivity`
  - `menu:insight:queries`
- 新增前端静态路由与 `meta.permission`：
  - `/metadata/assets/:id`
  - `/metadata/lineage`
  - `/metadata/sensitivity`
  - `/insight/queries`
- 新增 P0 共享组件：
  - `PageHeader`、`FilterBar`、`EntityLink`、`StatusTag`、`SensitivityBadge`、`MetadataPanel`、`DetailTabs`、`QuerySqlBlock`、`EmptyState`
- 新增/补齐 API 客户端与类型：
  - catalog dataset detail/columns/partitions
  - lineage upstream/downstream/impact
  - sensitivity rules/columns/scan/review
  - insight queries
  - dashboard acl/versions/order
- 共享组件统一使用 `frontend/DESIGN.md` 的 tokens 和 `var(--dg-*)`。

## Capabilities

### New Capabilities

- `frontend-p0-foundation`: P0 页面共用的路由、菜单、组件、API 客户端与权限边界。

### Modified Capabilities

无。

## Impact

- 前端：`frontend/src/router/index.ts`、`frontend/src/components/`、`frontend/src/api/`、`frontend/src/types/`
- 后端：新增 `V25__frontend_p0_menu.sql`
- 设计：`frontend/DESIGN.md` 已定义 tokens；本变更只消费，不新增视觉方向
- 不修改业务 API 语义，只补前端调用与菜单权限
