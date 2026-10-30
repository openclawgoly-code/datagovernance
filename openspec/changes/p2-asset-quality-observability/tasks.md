## 1. 数据模型与权限

- [ ] 1.1 V29：`md_quality_rule`、`md_quality_run`、索引、状态约束
- [ ] 1.2 种入 `metadata:quality:read/manage`，admin/developer manage，viewer read
- [ ] 1.3 新增 Quality entity/mapper/DTO
- [ ] 1.4 AuditInterceptor 增加 `/quality` → `QUALITY/GOVERNANCE`
- [ ] 1.5 增加菜单/路由条目（如采用独立质量总览菜单）

## 2. 后端

- [ ] 2.1 规则 CRUD、启停、配置校验（类型/字段/阈值）
- [ ] 2.2 `QualityRunner`：ROW_COUNT_RANGE、NOT_NULL、UNIQUE、FRESHNESS、SCHEMA_DRIFT
- [ ] 2.3 复用 DataAccessGateway 执行只读聚合，严格引用标识符，设置超时/最大耗时
- [ ] 2.4 单规则运行、数据集运行；单规则失败不阻断其他规则
- [ ] 2.5 结果历史与最新摘要、加权质量分数
- [ ] 2.6 端点与权限：rules、run、summary、runs
- [ ] 2.7 单元测试：配置校验、不支持数据源、部分失败、分数计算、权限

## 3. 前端

- [ ] 3.1 API/类型：规则、运行、摘要、历史
- [ ] 3.2 资产详情“质量”Tab：分数、规则明细、最新状态、运行/历史
- [ ] 3.3 `/metadata/quality` 总览：失败资产、最近运行、筛选
- [ ] 3.4 规则编辑抽屉按 rule_type 渲染配置字段
- [ ] 3.5 权限控制：无 manage 隐藏编辑/运行；无 read 路由 403
- [ ] 3.6 未评估/未运行/失败/超时/不支持数据源的空态与错误态

## 4. 验证

- [ ] 4.1 ROW_COUNT_RANGE 在阈值内/外分别通过/失败
- [ ] 4.2 NOT_NULL、UNIQUE、FRESHNESS 使用合成数据验证
- [ ] 4.3 SCHEMA_DRIFT 在当前/漂移 schema 下结果正确
- [ ] 4.4 数据集运行 3 条规则，其中 1 条失败不阻断其余
- [ ] 4.5 分数与严重度权重一致；无运行显示未评估
- [ ] 4.6 不支持的数据源/超时返回 FAILED 并写原因
- [ ] 4.7 审计 QUALITY/EXECUTE；无权限 403
- [ ] 4.8 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.9 `mvn -pl backend/dg-app -am test` 通过
