## ADDED Requirements

### Requirement: 血缘工作台入口

系统 SHALL 提供 `/metadata/lineage` 页面，只有拥有 `metadata:lineage:read` 的用户可以访问。

#### Scenario: 从资产详情进入

- **WHEN** 用户在资产详情点击“打开完整血缘”
- **THEN** 跳转 `/metadata/lineage?columnId={id}`
- **AND** 页面自动选中该字段

#### Scenario: 无权限

- **WHEN** 用户没有 `metadata:lineage:read`
- **THEN** 跳转 `/403`

### Requirement: 字段选择与方向

页面 SHALL 允许选择数据集/字段，并切换上游、下游、双向。

#### Scenario: 查看下游

- **WHEN** 用户选择字段并点击“下游”
- **THEN** 画布展示该字段的直接/间接下游节点
- **AND** 边方向从当前字段指向下游

### Requirement: 节点交互

页面 SHALL 在点击节点时展示节点详情，并允许跳转资产详情。

#### Scenario: 点击节点

- **WHEN** 用户点击画布中的节点
- **THEN** 右侧面板显示节点名称、类型、父表、字段
- **AND** 提供“打开资产详情”链接

### Requirement: 影响分析

页面 SHALL 提供影响列表，展示下游节点和层级。

#### Scenario: 生成影响清单

- **WHEN** 用户点击“影响分析”
- **THEN** 调用 impact API 并展示下游节点列表
- **AND** 支持复制影响清单文本

### Requirement: 大图收敛与空状态

页面 SHALL 在节点过多时收敛展示，并在没有血缘时显示空状态。

#### Scenario: 没有血缘

- **WHEN** 当前字段没有上游/下游边
- **THEN** 画布显示“该字段暂无血缘”
- **AND** 不显示空白图表

#### Scenario: 节点超过限制

- **WHEN** 返回节点超过 100 或深度超过 2
- **THEN** 页面提示已收敛
- **AND** 用户可手动展开或调整深度
