## 1. 后端候选端点

- [x] 1.1 扩展 `UserService.list` 支持 username/display_name 分组模糊搜索
- [x] 1.2 `InsightDashboardService` 注入 `WorkspaceService`、`UserService`，实现 `aclCandidates(id, keyword)`
- [x] 1.3 候选过滤：当前 workspace 成员、ACTIVE、未在 ACL、`requireManageable`
- [x] 1.4 新增 `GET /insight/dashboards/{id}/acl/candidates`，受 `insight:dashboard:manage` 保护
- [x] 1.5 返回 `AclCandidateView(username, displayName, status)`，最多 20 条
- [x] 1.6 单元/手工验证：非成员排除、已授权排除、无权限 403

## 2. 前端选择器

- [x] 2.1 `api/insight.ts`、`types/insight.ts` 增加候选类型与方法
- [x] 2.2 ACL 抽屉用户名输入替换为远程搜索 `el-select`
- [x] 2.3 选项展示显示名和用户名，300ms 防抖，加载/空态/错误态
- [x] 2.4 添加成功后清空选择，刷新候选与 ACL
- [x] 2.5 无 `insight:dashboard:manage` 时保持只读 ACL 列表，不显示候选搜索

## 3. 验证

- [x] 3.1 搜索 `p0` 能找到 p0viewer，添加 READ 后候选消失
- [x] 3.2 非空间用户不出现在候选
- [x] 3.3 viewer 调用候选端点 403
- [x] 3.4 添加/移除 ACL 后列表可见性仍正确
- [x] 3.5 `pnpm type-check`、`pnpm build` 通过
- [x] 3.6 `mvn -pl backend/dg-app -am test` 通过
