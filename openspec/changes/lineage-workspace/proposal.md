## Why

后端已经有字段级血缘与影响分析 API，但前端没有可视化入口。数据工程师在改字段、数据管家在判断影响时，只能看表结构，无法快速回答“下游还有谁”。

本变更提供 P0 的列级血缘工作台，作为资产详情和影响分析的独立页面。

## What Changes

- 新增 `/metadata/lineage` 页面与 `metadata:lineage:read` 权限
- 资产/字段选择器
- 上游/下游/双向方向切换
- 深度 1–3
- ECharts graph 画布
- 节点详情面板
- 影响列表
- 空状态与大图收敛提示

## Capabilities

### New Capabilities

- `lineage-workspace`: 列级血缘浏览、节点详情与影响分析。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/LineageView.vue`、`api/lineage.ts`、ECharts graph
- 后端：只消费现有 `/api/v1/metadata/lineage/*`，不新增表级端点
- 依赖：`frontend-p0-foundation`、`asset-detail-page`（入口链接）
