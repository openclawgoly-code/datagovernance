# insight-query-history Specification

## Purpose
TBD - created by archiving change query-history-page. Update Purpose after archive.
## Requirements
### Requirement: 查询历史页面

系统 SHALL 提供 `/insight/queries` 页面，只有拥有 `insight:read` 的用户可以访问。

#### Scenario: 无权限

- **WHEN** 用户没有 `insight:read`
- **THEN** 跳转 `/403`

### Requirement: 历史列表

页面 SHALL 展示问题、用户、状态、行数、耗时、脱敏列和时间。

#### Scenario: 查看失败记录

- **WHEN** 历史中存在失败问数
- **THEN** 列表显示失败状态
- **AND** 详情抽屉显示错误信息

### Requirement: 历史详情

页面 SHALL 提供详情抽屉展示完整 SQL、数据集 ID、脱敏列和错误。

#### Scenario: 打开详情

- **WHEN** 用户点击某条历史
- **THEN** 抽屉显示 SQL 和脱敏信息
- **AND** 不展示结果数据快照

### Requirement: 重新执行

页面 SHALL 在用户拥有 `insight:ask` 时提供重新执行。

#### Scenario: 重新执行

- **WHEN** 用户点击重新执行
- **THEN** 使用原问题与数据集调用 `/api/v1/insight/ask`
- **AND** 新结果经过脱敏和审计

#### Scenario: 无 ask 权限

- **WHEN** 用户只有 `insight:read`
- **THEN** 不显示重新执行按钮
