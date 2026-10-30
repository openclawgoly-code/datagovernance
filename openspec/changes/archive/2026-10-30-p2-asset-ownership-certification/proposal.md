## Why

P0 资产详情只能显示“owner/domain/认证状态未配置”。业务分析人员无法判断一张表由谁负责、属于哪个业务域、是否已认证，数据管家也无法把“能不能用”的结论沉淀到资产上。

本变更补上资产治理元数据，不改变采集事实。

## What Changes

- 新增业务域表 `md_business_domain`
- 新增资产治理表 `md_asset_governance`：
  - domain、owner、steward
  - certificationStatus = NONE / CERTIFIED / DEPRECATED
  - certifiedBy/At、certificationNote、reviewDueAt
- 新增端点：
  - `GET /api/v1/metadata/catalog/datasets/{id}/governance`
  - `PUT /api/v1/metadata/catalog/datasets/{id}/governance`
  - `GET/POST/PUT /api/v1/metadata/domains`
- Catalog 列表增加 domain/owner/certification 过滤，DatasetView 增加治理摘要字段
- 资产详情增加“治理”面板和编辑抽屉；目录列表显示 owner/认证徽标
- 新增 `metadata:asset:govern` 权限；读取复用 `metadata:asset:read`
- 治理更新写审计

## Capabilities

### New Capabilities

- `asset-governance`: 资产业务域、负责人、管家与认证状态。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/AssetDetailView.vue`、`CatalogAssetView.vue`、新增治理编辑组件、`api/catalog-assets.ts`、类型
- 后端：`CatalogAssetService`、`CatalogAssetController`、新增 Domain/Governance mapper、V27 迁移
- 依赖：`asset-catalog-detail`、`frontend-p0-foundation`
- 非目标：业务术语表、质量规则、访问申请、自动从 LDAP 同步负责人、多负责人、External Catalog
