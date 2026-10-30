## ADDED Requirements

### Requirement: P0 导航与权限入口

前端 SHALL 为资产详情、血缘、敏感数据、查询历史提供静态路由，并在后端菜单种子中提供对应菜单与权限码。

#### Scenario: 有权限用户看到菜单

- **WHEN** 用户登录后拥有 `metadata:lineage:read`、`metadata:sensitivity:read` 或 `insight:read`
- **THEN** 侧边栏显示对应菜单项
- **AND** 点击菜单进入对应路由

#### Scenario: 无权限直接访问路由

- **WHEN** 用户直接输入 `/metadata/lineage` 但没有 `metadata:lineage:read`
- **THEN** 路由守卫跳转 `/403`
- **AND** 不发起页面数据请求

### Requirement: 共享页面组件

前端 SHALL 提供 P0 共享组件：`PageHeader`、`FilterBar`、`EntityLink`、`StatusTag`、`SensitivityBadge`、`MetadataPanel`、`DetailTabs`、`QuerySqlBlock`、`EmptyState`。

#### Scenario: 状态色统一

- **WHEN** 页面展示成功、警告、危险、中性状态
- **THEN** 使用 `StatusTag` 的语义色
- **AND** 不直接写死颜色值

#### Scenario: 实体跳转统一

- **WHEN** 页面展示数据集、字段、任务、看板或报告链接
- **THEN** 使用 `EntityLink` 生成可点击跳转
- **AND** 目标路由由组件集中维护

### Requirement: P0 API 客户端

前端 SHALL 为 Catalog、Lineage、Sensitivity、Insight、Dashboard 提供类型化 API 客户端，统一使用 `api/request.ts` 的 HTTP 封装。

#### Scenario: 请求错误统一处理

- **WHEN** 任一 P0 API 返回非成功信封
- **THEN** 请求拦截器展示后端 `message`
- **AND** 页面保留可恢复的错误状态

### Requirement: 设计 token 使用

P0 页面 SHALL 使用 `frontend/src/styles/tokens.css` 中的 `var(--dg-*)`，不新增颜色字面量。

#### Scenario: 审查新页面

- **WHEN** 新增 P0 页面或组件
- **THEN** 颜色、间距、字体引用 token
- **AND** `rg '#[0-9a-fA-F]{3,6}' frontend/src/views/insight` 不出现新的硬编码颜色
