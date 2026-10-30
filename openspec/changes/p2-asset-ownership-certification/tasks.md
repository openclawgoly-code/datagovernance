## 1. 数据模型与权限

- [ ] 1.1 新增 V27：`md_business_domain`、`md_asset_governance`、索引与唯一约束
- [ ] 1.2 种入 `metadata:asset:govern`，授权 admin/developer，viewer 仅读
- [ ] 1.3 新增 Domain/Governance entity、mapper、DTO
- [ ] 1.4 `DatasetView` 增加 domain/owner/certification 摘要字段
- [ ] 1.5 `AuditInterceptor` 增加 `/catalog` → `ASSET_GOVERNANCE/METADATA`，PUT 记 UPDATE

## 2. 后端服务

- [ ] 2.1 `AssetGovernanceService`：读取/更新治理记录，采集刷新不触碰治理表
- [ ] 2.2 owner/steward 校验：存在、ACTIVE、当前 workspace 成员
- [ ] 2.3 认证状态机：CERTIFIED 记录认证人/时间，DEPRECATED 必须有说明
- [ ] 2.4 Domain CRUD：code 唯一，被引用时禁止删除
- [ ] 2.5 Catalog 列表增加 domain/owner/certification 可选过滤
- [ ] 2.6 新增治理/域端点，读取用 asset:read，写入用 asset:govern
- [ ] 2.7 单元测试：未配置、成员校验、认证状态、删除保护、过滤

## 3. 前端

- [ ] 3.1 API/类型：治理读写、域列表/维护、Catalog 摘要与过滤
- [ ] 3.2 资产详情概览增加治理面板与认证徽标
- [ ] 3.3 治理编辑抽屉：domain、owner/steward 成员选择、认证状态、说明、复审日期
- [ ] 3.4 只读用户隐藏编辑入口；成员失效显示“已不在当前空间”
- [ ] 3.5 Catalog 列表增加 owner/认证列与 domain/owner/certification 筛选
- [ ] 3.6 认证到期显示“待复审”，不阻断问数

## 4. 验证

- [ ] 4.1 给资产配置 domain/owner，刷新 Catalog 后值保持
- [ ] 4.2 非成员 owner 返回 400；viewer 写入 403
- [ ] 4.3 CERTIFIED/DEPRECATED 徽标和说明校验正确
- [ ] 4.4 列表过滤与分页总数一致
- [ ] 4.5 审计出现 ASSET_GOVERNANCE/UPDATE
- [ ] 4.6 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.7 `mvn -pl backend/dg-app -am test` 通过
