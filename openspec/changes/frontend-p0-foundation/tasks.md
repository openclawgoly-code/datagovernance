## 1. 菜单与路由

- [ ] 1.1 新增 `V25__frontend_p0_menu.sql`，种入血缘、敏感、查询历史菜单与角色授权
- [ ] 1.2 在 `router/index.ts` 注册 `/metadata/assets/:id`、`/metadata/lineage`、`/metadata/sensitivity`、`/insight/queries`
- [ ] 1.3 为每条路由声明 `meta.title` 与 `meta.permission`
- [ ] 1.4 验证四个菜单在登录后由后端下发且可点击

## 2. 共享组件

- [ ] 2.1 新建 `PageHeader`、`FilterBar`、`EmptyState`
- [ ] 2.2 新建 `EntityLink`、`StatusTag`、`SensitivityBadge`
- [ ] 2.3 新建 `MetadataPanel`、`DetailTabs`、`QuerySqlBlock`
- [ ] 2.4 所有组件使用 `var(--dg-*)` 与 Element Plus 语义色
- [ ] 2.5 为组件补最小单元测试或类型使用示例

## 3. API 与类型

- [ ] 3.1 补 `api/catalog-assets.ts` 的 dataset detail/columns/partitions 类型
- [ ] 3.2 新建 `api/lineage.ts`，封装 upstream/downstream/impact/node
- [ ] 3.3 新建/补齐 `api/sensitivity.ts`，封装 rules/columns/scan/review
- [ ] 3.4 补 `api/insight.ts` 的查询历史、看板 ACL/versions/order 方法
- [ ] 3.5 统一错误处理，不在组件内直接调用 axios

## 4. 验证

- [ ] 4.1 `pnpm type-check` 通过
- [ ] 4.2 `pnpm build` 通过
- [ ] 4.3 逐项验证菜单权限：admin/developer/viewer 可见，无权限用户 403
