## Context

P0 血缘工作台只消费列级节点和边。`md_lineage_node` 同时保存 `catalog_dataset_id` 与 `catalog_column_id`，但后端没有表级聚合端点，前端只能逐字段看上下游。

数据管家需要回答的是“这张表的下游有哪些表、影响多少列”，而不是先看完所有字段。表级视图应是列级事实的聚合，不引入新的血缘事实表。

## Goals / Non-Goals

**Goals:**

- 从数据集出发查看表级上游/下游/双向；
- 聚合边保留列级边数量，能回到资产详情；
- 复用现有列级工作台的图、节点详情、影响清单和收敛规则；
- 不改变列级行为，不新增表结构。

**Non-Goals:**

- 不做表级人工补录；
- 不做跨空间全局血缘；
- 不替换列级工作台；
- 不做图布局持久化；
- 不在 P1 引入新的血缘事实表或 OpenLineage 写入格式。

## Decisions

### 1. 后端聚合，前端不拉全量列级图

表级视图如果由前端先拉列级图再聚合，在大表/多层血缘下会把大量列级节点带进浏览器。后端在 BFS 后按 `catalogDatasetId` 分组，返回数据集节点和聚合边。

实现上复用 `LineageService` 的列级 BFS：

1. 用 `CatalogDatasetMapper` 校验数据集属于当前 workspace；
2. 找到 `catalogDatasetId = 起点数据集` 的列级节点；
3. 按 direction 做最多 3 层列级 BFS；
4. 将访问到的列级节点按数据集分组；
5. 对每条 ACTIVE 列级边，若两端都有 `catalogDatasetId`，累计 `fromDataset -> toDataset` 的边数；
6. 用 `CatalogDataset` 补齐数据集名和限定名。

起点数据集没有任何列级边时返回空 `nodes/edges`，前端展示“该数据集暂无表级血缘”，而不是只显示一个孤立节点。

### 2. 新端点而不是扩展现有列级响应

新增：

```text
GET /api/v1/metadata/lineage/datasets/{id}/graph
  ?direction=upstream|downstream|both&depth=1..3
```

响应复用 `GraphView` 结构：

- 节点：`nodeType=DATASET`，`id=catalogDatasetId`，`catalogDatasetId` 有值，`catalogColumnId=null`
- 边：`id=dsedge:{from}->{to}`，`relation=DERIVE`，`transformExpr="{n} 条列级血缘"`，`origin=AGGREGATED`
- 只有两端都能解析到 catalog dataset 的列级边才进入表级边

不把表级节点混进现有 `/columns/{id}/upstream`，避免列级调用方拿到非列节点。

影响清单复用同一端点 `direction=downstream&depth=5`，不在 P1 增加独立 impact API。

### 3. 复用前端血缘图和布局

`LineageGraph` 与 `buildLineageLayout` 只依赖 `LineageNode/LineageEdge` 的字段，不依赖节点类型。表级模式直接把 DATASET 节点传给现有组件：

- 表级节点显示数据集名；
- 节点详情显示限定名、数据集 ID、列级边数量；
- “打开资产详情”跳 `/metadata/assets/{datasetId}`；
- “以该数据集为中心”重新加载表级图。

节点类型过滤在表级模式只显示“数据集”，不展示无意义的 COLUMN/TABLE 选项。

### 4. 用 URL mode 而不是新路由

`/metadata/lineage?mode=dataset&datasetId=...` 进入表级模式，默认 `mode=column` 保持 P0 行为。理由：

- 两个模式共享选择器、筛选条和图容器；
- 资产详情和未来工作台可以直接深链；
- 避免新增菜单项和重复权限声明。

模式切换保留数据集；从表级切回字段级时，如果当前数据集已有列则选第一列，否则提示选择字段。

### 5. 收敛复用 P0 规则

表级图仍受 100 节点/2 层默认收敛限制；用户可展开。表级节点通常少于列级节点，但保留规则可防止“全表血缘”一次性铺满画布。

## Risks / Trade-offs

- [列级节点缺少 catalogDatasetId] → 该边不进入表级聚合，页面提示“部分历史列级边缺少数据集归属，未计入表级视图”。
- [同一对表之间多种 relation] → P1 聚合为单条 DERIVE 边并给出列级边数量，不区分 JOIN/TRANSFORM 类型；关系细分留到 P2。
- [数据集被逻辑删除] → 跳过无法解析的数据集节点和边，不返回悬空表级边。
- [前端需要 Catalog 搜索权限] → 表级选择器复用现有 Catalog 接口，内置角色均有 `metadata:asset:read`；仅有血缘只读权限的用户可通过资产详情深链进入。
- [表级边数量可能被误解为字段数量] → 边标签明确“n 条列级血缘”，详情面板展示来源/去向列数。

## Migration Plan

1. 先发布后端 additive 端点，不影响现有列级 API；
2. 前端发布 `mode=dataset`，旧 URL 默认列级；
3. 回滚前端即隐藏表级入口，后端端点可保留。

无数据库迁移。

## Open Questions

- P2 是否需要区分 JOIN / FILTER / AGGREGATE 等表级关系类型；
- 是否需要在表级图上显示列级样本，当前只显示数量。
