## ADDED Requirements

### Requirement: 表级血缘入口

系统 SHALL 在 `/metadata/lineage` 支持 `mode=dataset` 的表级模式；默认 `mode=column` 保持列级工作台不变。

#### Scenario: 从列级切换到表级

- **WHEN** 用户已在列级模式选中数据集，并切换到表级模式
- **THEN** URL 更新为 `?mode=dataset&datasetId={datasetId}`
- **AND** 画布以该数据集为中心加载表级上下游

#### Scenario: 表级深链

- **WHEN** 用户打开 `/metadata/lineage?mode=dataset&datasetId={datasetId}`
- **THEN** 页面直接加载表级血缘
- **AND** 不要求用户先选字段

### Requirement: 列级事实聚合为表级图

系统 SHALL 将列级血缘按 `catalogDatasetId` 聚合为数据集节点，并按 `fromDataset -> toDataset` 聚合成表级边。

#### Scenario: 同表多字段血缘

- **WHEN** 源表和目标表之间存在 5 条列级血缘边
- **THEN** 表级图只显示一条源表到目标表的边
- **AND** 边上显示“5 条列级血缘”

#### Scenario: 缺少数据集归属

- **WHEN** 某条 ACTIVE 列级边的任一端点没有 `catalogDatasetId`
- **THEN** 该边不进入表级图
- **AND** 页面提示部分历史边未计入表级视图

#### Scenario: 没有表级血缘

- **WHEN** 起点数据集没有任何可归属的列级边
- **THEN** 表级图返回空节点和空边
- **AND** 页面显示“该数据集暂无表级血缘”

### Requirement: 表级方向、深度与影响

系统 SHALL 支持表级上游、下游、双向和 1–3 层查询，并 SHALL 支持对表级下游最多 5 层生成影响清单。

#### Scenario: 查看下游

- **WHEN** 用户选择表级下游和深度 2
- **THEN** 图只展示从起点出发 1–2 层下游数据集
- **AND** 边方向从起点指向下游

#### Scenario: 生成表级影响清单

- **WHEN** 用户点击“影响分析”
- **THEN** 系统查询起点数据集下游 5 层
- **AND** 清单按层级展示数据集名称、限定名和列级边数量

#### Scenario: 大图收敛

- **WHEN** 表级图超过 100 个节点或查询深度大于 2
- **THEN** 页面沿用默认 2 层/100 节点收敛
- **AND** 用户可展开全部或调低深度

### Requirement: 表级节点详情与资产跳转

系统 SHALL 在点击表级节点时展示数据集名称、限定名、数据集 ID 和聚合边数量，并提供资产详情入口。

#### Scenario: 打开数据集

- **WHEN** 用户点击表级节点并选择“打开资产详情”
- **THEN** 跳转 `/metadata/assets/{datasetId}`
- **AND** 目标资产属于当前 workspace

#### Scenario: 切回字段级

- **WHEN** 用户在表级节点详情点击“查看字段级血缘”
- **THEN** 页面切回 `mode=column`
- **AND** 保留当前数据集，并选择该数据集的第一个字段

### Requirement: 表级权限隔离

系统 SHALL 使用 `metadata:lineage:read` 保护表级端点，且 SHALL 只返回当前 workspace 可读的数据集与血缘。

#### Scenario: 无权限访问

- **WHEN** 用户没有 `metadata:lineage:read` 请求表级端点
- **THEN** 返回 403
- **AND** 不泄露数据集名称或血缘关系
