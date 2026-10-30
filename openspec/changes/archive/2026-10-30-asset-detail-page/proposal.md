## Why

数据目录当前以“来源 → 数据集列表 → 抽屉”为主。业务分析人员和数据管家需要在同一条资产上同时看到字段、敏感标签、血缘入口和分区信息，抽屉无法承载多个 Tab，也不能分享 URL。

本变更把资产详情提升为独立路由页，作为目录、血缘、敏感和问数之间的枢纽。

## What Changes

- 新增 `/metadata/assets/:id` 路由与权限 `metadata:asset:read`
- 详情页 Header：数据集名、来源、格式、行数、字段数、更新时间、快速动作
- 详情页 Tab：
  - 概览
  - 字段（含字段抽屉）
  - 血缘（列级预览 + 跳完整血缘）
  - 敏感（字段标签 + 复核入口）
  - 分区
- Catalog 列表抽屉保留为快速预览，增加“打开详情”
- Tab 状态同步到 `?tab=`

## Capabilities

### New Capabilities

- `asset-catalog-detail`: 数据资产的独立详情页与字段/敏感/血缘/分区视图。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/AssetDetailView.vue`、路由、Catalog 列表
- 后端：只消费现有 Catalog/Sensitivity/Lineage API，不新增接口
- 依赖：`frontend-p0-foundation`
