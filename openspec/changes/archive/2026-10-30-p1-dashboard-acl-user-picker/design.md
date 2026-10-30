## Context

P0 的 ACL 抽屉只有一个用户名文本框，调用 `POST /dashboards/{id}/acl`。`GET /users` 是平台级用户列表，受 `platform:user:read` 保护，返回跨空间用户；直接拿它做看板授权既容易拼错，也会把非本空间用户带进候选。

P1 需要把“授权对象”限定在当前 workspace 的有效成员，并在选择前排除已授权用户。

## Goals / Non-Goals

**Goals:**

- 远程搜索当前空间成员；
- 按用户名/显示名匹配，最多 20 条；
- 已授权用户不出现在候选；
- 只有 `insight:dashboard:manage` 能搜索和授权；
- 保持现有 ACL 表和添加/移除接口。

**Non-Goals:**

- 不做角色继承 ACL；
- 不做跨空间授权；
- 不做全局用户选择器；
- 不做版本回滚；
- 不改变看板可见性判定。

## Decisions

### 1. 新增看板上下文候选端点

```text
GET /api/v1/insight/dashboards/{id}/acl/candidates?keyword=ali
```

返回：

```json
[
  { "username": "alice", "displayName": "Alice", "status": "ACTIVE" }
]
```

服务端：

1. `requireManageable(id)` 校验当前用户可管理该看板；
2. 取当前 workspace 成员 ID；
3. 用 `UserService.list(keyword)` 取匹配用户，过滤成员、ACTIVE、未在 ACL 中；
4. 排序后截断 20 条。

### 2. 不复用全局 `/users`

`/users` 需要 `platform:user:read`，且没有空间边界。看板 ACL 是 workspace 内治理动作，必须有独立候选端点。候选端点复用 `WorkspaceService.listMemberIds` 与 `UserService`，不新增表。

### 3. 扩展用户搜索到显示名

现有 `UserService.list(keyword)` 只匹配 username。P1 将条件改为分组：

```text
(username LIKE ? OR display_name LIKE ?)
```

候选端点与用户管理页共用，属于可接受的搜索增强。

### 4. 前端远程选择器

ACL 抽屉：

- `el-select` 远程搜索，300ms 防抖；
- 选项展示 `displayName (@username)`；
- 选中后选择 READ/MANAGE，再点“添加授权”；
- 添加成功清空选择并刷新候选和 ACL；
- 已授权用户由后端排除，前端不重复拼用户名。

### 5. 错误与空态

- 无候选：显示“没有可添加的空间成员”
- 候选接口失败：ACL 列表仍可看，候选区显示错误
- 用户名不再允许自由输入，避免绕过成员约束

## Risks / Trade-offs

- [空间成员很多] → 关键词过滤 + 最多 20 条；空关键词只返回前 20 个成员。
- [用户被移出空间后仍有 ACL] → 后端 ACL 仍保留历史行；候选只影响新增，后续可做清理任务。
- [跨模块依赖] → `InsightDashboardService` 注入 `WorkspaceService/UserService`，属于 dg-app 内组合，不改变 Space 边界。
- [搜索显示名改变用户管理行为] → 这是增强，不改变返回结构。

## Migration Plan

无数据库迁移。先发布候选端点，再发布前端选择器；回滚前端仍可调用旧添加 ACL 接口。

## Open Questions

- P2 是否需要在移除成员时级联清理 ACL；
- 是否需要显示用户邮箱/部门帮助区分同名用户。
