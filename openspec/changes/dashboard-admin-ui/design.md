## Context

`InsightDashboardService` 已支持创建者/平台管理员/ACL 访问控制、版本快照和问题项 `sortOrder`/`layoutJson`。前端已有看板列表、详情、刷新和版本弹窗，但 ACL 与排序没有交互。

## Goals / Non-Goals

**Goals:**

- 看板所有者能添加 READ/MANAGE 用户；
- 列表过滤由后端保证，非授权用户看不到看板；
- 问题项可调整顺序；
- 版本历史可查看。

**Non-Goals:**

- 不做版本回滚；
- 不做自由拖拽布局；
- 不做用户搜索组件（P0 输入用户名）；
- 不做 ACL 继承/角色。

## Decisions

### 1. ACL 用抽屉，不新页面

看板详情上下文足够，抽屉可保留问题项列表；操作后刷新 ACL 和看板列表。

### 2. 用户名输入而不是用户选择器

P0 不引入全局用户选择器；输入用户名并调用 saveMapping 风格的 API。
P1 可替换为可搜索用户选择。

### 3. 排序用上移/下移

后端只有 `sortOrder` 数值；前端交换相邻项并 PATCH，避免实现拖拽状态机。

## Risks / Trade-offs

- [用户名输错] → 后端/前端校验空值，提示“用户不存在”时由后端返回。
- [ACL 与 Workspace 权限叠加] → 页面明确：Space 权限决定能否使用看板，ACL 决定看哪一个。
- [上移/下移并发] → P0 单用户操作，提交后刷新详情。

## Migration Plan

纯前端；无数据库迁移。回滚移除 ACL/排序 UI 即可。

## Open Questions

- 是否需要“仅创建者可管理”之外的规则？当前后端已定义，前端不重复。
