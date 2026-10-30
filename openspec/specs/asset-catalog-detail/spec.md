# asset-catalog-detail Specification

## Purpose
TBD - created by archiving change asset-detail-page. Update Purpose after archive.
## Requirements
### Requirement: 资产详情路由

系统 SHALL 提供 `/metadata/assets/:id` 页面，只有拥有 `metadata:asset:read` 的用户可以访问。

#### Scenario: 从目录打开详情

- **WHEN** 用户在数据目录点击某个数据集或“打开详情”
- **THEN** 系统跳转 `/metadata/assets/{id}`
- **AND** 页面显示该数据集的 qualifiedName、来源、格式和摘要指标

#### Scenario: 无权限访问

- **WHEN** 用户没有 `metadata:asset:read`
- **THEN** 系统跳转 `/403`
- **AND** 不请求资产详情 API

#### Scenario: 资产不存在

- **WHEN** 数据集 ID 不存在或属于其他空间
- **THEN** 页面显示明确的“资产不存在”状态
- **AND** 提供返回数据目录的入口

### Requirement: 资产详情 Tabs

详情页 SHALL 提供概览、字段、血缘、敏感、分区五个 Tab，Tab 状态同步到 URL 查询参数。

#### Scenario: 刷新保持 Tab

- **WHEN** 用户位于 `?tab=schema` 并刷新页面
- **THEN** 页面仍显示字段 Tab

### Requirement: 字段视图

字段 Tab SHALL 展示字段名、原始类型、规范类型、约束、注释和敏感级别。

#### Scenario: 打开字段抽屉

- **WHEN** 用户点击字段行
- **THEN** 显示字段元数据抽屉
- **AND** 提供敏感复核、字段血缘和复制字段 ID 的入口

### Requirement: 敏感视图

敏感 Tab SHALL 展示该数据集字段的敏感标签、级别、状态和证据，并在有权限时提供复核入口。

#### Scenario: 有复核权限

- **WHEN** 用户拥有 `metadata:sensitivity:review`
- **THEN** 每条标签显示复核按钮
- **AND** 提交后刷新当前列表并提示审计已记录

### Requirement: 血缘预览

血缘 Tab SHALL 以列级血缘展示当前数据集的上游/下游预览，并允许跳转到完整血缘工作台。

#### Scenario: 查看字段血缘

- **WHEN** 用户选择一个字段并打开血缘 Tab
- **THEN** 页面展示该字段的上游和下游节点
- **AND** 提供“打开完整血缘”链接

### Requirement: 分区视图

分区 Tab SHALL 展示 Parquet 数据集的分区路径、值、文件数、大小和行数；非 Parquet 数据集显示空状态。

#### Scenario: JDBC 数据集

- **WHEN** 用户查看 JDBC 表的分区 Tab
- **THEN** 页面显示“该数据集没有分区信息”
- **AND** 不发起不必要的分区请求
