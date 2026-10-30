## Why

P0 血缘工作台只到列级。数据管家评估“改这张表会影响哪些表”时，需要先逐字段查看上下游，无法快速做表级影响判断。

本变更在现有血缘工作台上增加表级总览，不改列级能力。

## What Changes

- 新增表级血缘聚合端点：
  - `GET /api/v1/metadata/lineage/datasets/{id}/graph?direction=upstream|downstream|both&depth=1..5`（页面控制 1–3，影响清单用 5）
  - 将列级节点按 `catalogDatasetId` 聚合为数据集节点，列级边聚合为数据集边
  - 边上保留列级边数量，节点详情可回到资产详情
- `/metadata/lineage` 增加“字段级 / 表级”模式切换
  - `?mode=dataset` 进入表级总览
  - 表级模式复用数据集搜索、图、节点详情与影响清单
- 表级模式使用 `metadata:lineage:read`，不新增权限码
- 表级图默认深度 2，支持收敛提示

## Capabilities

### New Capabilities

- `lineage-table-overview`: 数据集粒度的血缘聚合、表级影响与资产跳转。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/LineageView.vue`、`api/lineage.ts`、`types/lineage.ts`
- 后端：`LineageService`、`LineageController`，需要新增数据集聚合查询；不修改表结构
- 依赖：`lineage-workspace`、`frontend-p0-foundation`、`asset-catalog-detail`
- 非目标：不做表级人工补录、不做跨空间血缘、不替换列级工作台、不做图布局持久化
