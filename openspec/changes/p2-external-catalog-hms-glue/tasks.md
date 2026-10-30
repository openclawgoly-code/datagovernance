## 1. 数据模型与来源校验

- [ ] 1.1 V31：`md_catalog_source` 增加 `credential_id`，放宽 `data_source_id` 可空
- [ ] 1.2 CatalogSourceType 增加 `HIVE_METASTORE`/`GLUE`
- [ ] 1.3 SourceRequest 增加 credentialId；HMS/Glue 分支校验 dataSourceId 为空
- [ ] 1.4 现有 JDBC/Parquet 仍强制 dataSourceId
- [ ] 1.5 AuditInterceptor `/catalog/sources` → `CATALOG_SOURCE/METADATA`

## 2. Provider

- [ ] 2.1 定义 HMS/Glue config DTO 与校验
- [ ] 2.2 `HmsCatalogProvider`：无 SASL/Kerberos keytab、database/table/view/column/partition 映射
- [ ] 2.3 `GlueCatalogProvider`：region/catalogId、静态 credential/default chain、分页
- [ ] 2.4 Provider 不读取数据，行数/大小不可得保持 null
- [ ] 2.5 刷新接线：按 sourceType 选择 Provider，失败保留旧快照
- [ ] 2.6 结果增加 warnings；单个 database/table 失败不阻断全部
- [ ] 2.7 单元测试：配置校验、映射、认证失败、部分失败

## 3. 前端

- [ ] 3.1 来源类型增加 HMS/Glue，动态表单隐藏 dataSourceId
- [ ] 3.2 credential 选择器与 endpoint/region 配置
- [ ] 3.3 来源列表 External 标识；资产详情外部标识与限制说明
- [ ] 3.4 刷新结果 warnings/错误展示
- [ ] 3.5 权限控制，无 manage 不显示创建/刷新

## 4. 验证

- [ ] 4.1 用 fake HMS/Glue 或本地 stub 采集 database/table/column/partition
- [ ] 4.2 刷新失败保留上次快照，不把 0 表当成功
- [ ] 4.3 静态 Glue 凭据不出现在 config_json/响应
- [ ] 4.4 JDBC/Parquet 来源行为不变
- [ ] 4.5 外部资产不被当作可查询 DataSource
- [ ] 4.6 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.7 `mvn -pl backend/dg-app -am test` 通过
