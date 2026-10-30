## 1. 数据与权限

- [ ] 1.1 新增 `V26__governance_inbox.sql`：`gv_inbox_state` 表、`governance:inbox:read/manage` 权限
- [ ] 1.2 内置角色授权：admin/developer read+manage，viewer read
- [ ] 1.3 新增 `InboxState` entity/mapper（dg-governance）
- [ ] 1.4 `AuditInterceptor` 增加 `/inbox` 资源映射，`dismiss/reopen` 记为 UPDATE

## 2. 后端聚合

- [ ] 2.1 `InboxService` 按权限查询执行（FAILED/TIMEOUT）、未处理告警、SUGGESTED 标签，各取最近 200 条
- [ ] 2.2 映射统一条目：sourceType/sourceId/title/summary/severity/occurredAt/sourceState/route
- [ ] 2.3 覆盖 `gv_inbox_state`，按 sourceType/state/severity/keyword 过滤，按严重度和时间排序，内存分页
- [ ] 2.4 返回 `truncated`；无来源权限时不查询对应服务
- [ ] 2.5 新增 `GET /governance/inbox`
- [ ] 2.6 新增 `POST /governance/inbox/{sourceType}/{sourceId}/dismiss|reopen`，校验 inbox manage + 来源 read
- [ ] 2.7 单元测试：权限过滤、severity 排序、dismiss/reopen、来源状态不变

## 3. 前端收件箱

- [ ] 3.1 新增 `/workbench/inbox` 路由、`governance:inbox:read` 权限、类型与 API
- [ ] 3.2 来源/状态/严重度/关键词筛选与分页
- [ ] 3.3 列表展示来源标签、严重度、标题、摘要、时间、来源状态
- [ ] 3.4 移出/恢复操作（manage 权限）与二次确认
- [ ] 3.5 执行/告警/敏感来源跳转；敏感带 datasetId+reviewId
- [ ] 3.6 工作台“需要处理”入口与快捷入口按权限显示
- [ ] 3.7 空态、错误态、truncated 提示、无 manage 时只读

## 4. 验证

- [ ] 4.1 三类来源都能进入收件箱并正确排序
- [ ] 4.2 缺少敏感读权限时收件箱不返回敏感条目且不请求敏感接口
- [ ] 4.3 移出告警后告警本身仍为原状态，收件箱默认隐藏
- [ ] 4.4 恢复后条目回到默认列表
- [ ] 4.5 来源跳转正确，审计记录 INBOX/UPDATE
- [ ] 4.6 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.7 `mvn -pl backend/dg-app -am test` 通过
