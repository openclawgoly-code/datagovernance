## Why

当前 Catalog 只采集 JDBC 与 Parquet 目录。StarRocks `FILES()` 内表不是 External Catalog；真正接入客户既有 Hive Metastore / AWS Glue 仍缺失，P0 一直明确不宣称支持。

本变更为 Catalog 增加只读 HMS/Glue Provider，先解决“把外部湖表目录采进来”，不做数据查询。

## What Changes

- Catalog Source 新增 `HIVE_METASTORE`、`GLUE` 两种类型
- 新增 `credential_id` 到 `md_catalog_source`，HMS/Glue 不要求 `data_source_id`
- 新增 `HmsCatalogProvider`、`GlueCatalogProvider`，实现 `CatalogProvider`
- 复用现有 source create/get/list/refresh 端点，返回外部目录的 database/table/view/column/partition
- 前端新建来源支持 HMS/Glue，展示 External Catalog 标识与连接/采集错误
- 仍使用 `metadata:asset:read/manage` 权限
- 增加 `/catalog` 审计映射（如 ownership change 已加则复用）

## Capabilities

### New Capabilities

- `external-catalog-hms-glue`: Hive Metastore / AWS Glue 的只读目录采集。

### Modified Capabilities

无。

## Impact

- 前端：`CatalogAssetView.vue` 来源对话框、来源列表、API/类型
- 后端：CatalogSourceType、CatalogAssetService source 校验、DataSource 可空、V31 迁移、两个 CatalogProvider 及依赖
- 依赖：`asset-catalog-detail`、`asset-governance`（外部来源的 owner/认证可复用治理）
- 非目标：通过 StarRocks 查询外部表、写入外部表、Hive ACID、Iceberg REST、自动建湖、跨账号 Glue Catalog 写
