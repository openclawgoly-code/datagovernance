## 1. 后端批量复核

- [ ] 1.1 增加 `BatchReviewRequest` / `BatchReviewResult` / `BatchFailure` DTO，限制 ids 非空、去重后 ≤200
- [ ] 1.2 `SensitivityService` 一次查询 workspace 内的标签，逐条更新状态/级别/复核人/时间
- [ ] 1.3 缺失或无权的 ID 写入 failures，不因单条业务失败回滚整批
- [ ] 1.4 新增 `POST /metadata/sensitivity/columns/batch-review`，受 `metadata:sensitivity:review` 保护
- [ ] 1.5 `AuditInterceptor` 将 `batch-review` 映射为 `UPDATE`，资源类型 `SENSITIVITY`
- [ ] 1.6 单元测试：全成功、部分失败、非法状态/级别、超过 200 条

## 2. 前端批量操作

- [ ] 2.1 `api/sensitivity.ts` 和 `types/sensitivity.ts` 增加批量复核类型
- [ ] 2.2 标签表格增加当前页多选与“仅当前页选择”提示
- [ ] 2.3 批量操作条：批量确认、批量拒绝、统一级别
- [ ] 2.4 确认弹窗展示数量、目标状态和级别；提交后展示成功/失败摘要
- [ ] 2.5 翻页、筛选、刷新时清空选择；无 `metadata:sensitivity:review` 时隐藏多选与批量操作
- [ ] 2.6 部分失败时展示成功数、失败数和前 5 条失败原因

## 3. 验证

- [ ] 3.1 单页多选 10 条批量确认，状态全部变 `CONFIRMED`
- [ ] 3.2 批量拒绝不改变级别
- [ ] 3.3 指定 L3 批量确认后级别全部为 L3
- [ ] 3.4 部分失败响应 `succeeded/failed` 正确，列表刷新
- [ ] 3.5 审计表出现一条 `UPDATE SENSITIVITY`
- [ ] 3.6 `pnpm type-check`、`pnpm build` 通过
- [ ] 3.7 `mvn -pl backend/dg-app -am test` 通过
