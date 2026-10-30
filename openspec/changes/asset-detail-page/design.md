## Context

Catalog 列表已经有数据集摘要，但详情只在抽屉里显示字段/分区。P0 需要支持业务分析人员“找到资产后判断能不能用”，以及数据管家“看敏感/血缘/字段”。

## Goals / Non-Goals

**Goals:**

- 一条 URL 可打开的资产详情；
- 五个稳定 Tab，URL 可分享；
- 复用现有 API，不新增后端；
- Catalog 列表平滑跳转。

**Non-Goals:**

- 不做资产编辑/认证；
- 不做质量/可观测；
- 不做表级血缘图；
- 不替换 Catalog 列表页。

## Decisions

### 1. 路由页而不是放大抽屉

详情包含多类数据，抽屉会遮住列表上下文且不能分享。`/metadata/assets/:id?tab=schema` 是稳定入口。

### 2. 数据按 Tab 懒加载

首屏并行加载 dataset + columns；sensitivity、lineage、partitions 在 Tab 激活时加载。
原因：1000 表环境下列级血缘和分区块可能很重。

### 3. 血缘只做列级预览

后端当前只有 `/columns/{id}/upstream|downstream`。详情页默认取第一个字段，提供字段选择器；完整交互交给 `lineage-workspace`。

### 4. Catalog 抽屉降级为快速预览

抽屉保留摘要、前几个字段和“打开详情”；不再扩展完整 Tab，避免两份实现。

## Risks / Trade-offs

- [首个字段不代表表级血缘] → 页面明确写“列级血缘”，并给字段选择器。
- [1000 表环境下数据集不存在] → 统一 404 状态和返回目录入口。
- [Tab 状态与浏览器后退不一致] → 用 query 参数驱动 Tab，不用组件内部 state。

## Migration Plan

新增页面和路由即可；旧抽屉保留，回滚不影响 Catalog 列表。

## Open Questions

- 字段抽屉是否需要直接展示敏感复核？P0 只给入口，复核统一在敏感工作台。
