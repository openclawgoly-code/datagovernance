## 1. 数据模型与权限

- [ ] 1.1 V30：`gv_access_request`、`gv_access_grant`、状态/到期索引
- [ ] 1.2 种入 `metadata:access:request`、`metadata:access:approve` 并授权内置角色
- [ ] 1.3 新增 AccessRequest/Grant entity、mapper、DTO
- [ ] 1.4 AuditInterceptor 增加 `/access-requests` → `ACCESS_REQUEST/GOVERNANCE`
- [ ] 1.5 菜单/路由：访问申请页面入口

## 2. 后端流程

- [ ] 2.1 申请创建、参数校验、本人取消
- [ ] 2.2 审批/拒绝/自审批禁止/状态机
- [ ] 2.3 批准创建 grant、到期时间、撤销
- [ ] 2.4 active grants 查询（惰性过期）
- [ ] 2.5 `SensitiveColumnView` 增加 category；grant 覆盖过滤
- [ ] 2.6 Query Gateway 接入 grant 过滤，审计 detail 记录 grant IDs
- [ ] 2.7 端点与权限：request、pending、approve/reject/cancel、grants
- [ ] 2.8 单元测试：自审批、状态机、到期、类别/级别覆盖、导出同链路

## 3. 前端

- [ ] 3.1 API/类型：申请、审批、grant
- [ ] 3.2 `/governance/access-requests` 页面：我的申请、待审批、grant 状态
- [ ] 3.3 申请表单：类别、最高级别、时长、原因
- [ ] 3.4 资产详情敏感 Tab “申请临时访问”入口与预填
- [ ] 3.5 审批抽屉/操作；无 approve 不显示
- [ ] 3.6 过期/撤销显示；无 request 路由 403

## 4. 验证

- [ ] 4.1 提交申请 → 审批 → grant → 问数 L2 PII 不脱敏、L4 仍脱敏
- [ ] 4.2 自审批 403；非 PENDING 再审批 409
- [ ] 4.3 到期后 grant 失效；撤销立即失效
- [ ] 4.4 CSV/XLSX 导出与页面脱敏一致
- [ ] 4.5 查询历史显示 grant 解释
- [ ] 4.6 审计 ACCESS_REQUEST/UPDATE 与查询 detail
- [ ] 4.7 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.8 `mvn -pl backend/dg-app -am test` 通过
