# sensitivity-workbench Specification

## Purpose
TBD - created by archiving change sensitivity-workbench. Update Purpose after archive.
## Requirements
### Requirement: 敏感数据工作台

系统 SHALL 提供 `/metadata/sensitivity` 页面，只有拥有 `metadata:sensitivity:read` 的用户可以访问。

#### Scenario: 无权限访问

- **WHEN** 用户没有 `metadata:sensitivity:read`
- **THEN** 跳转 `/403`

### Requirement: 规则查看

页面 SHALL 展示敏感规则的名称、类别、级别、检测方式、优先级和启用状态。

#### Scenario: 查看内置规则

- **WHEN** 用户打开规则 Tab
- **THEN** 显示当前空间可用的内置规则和自定义规则

### Requirement: 字段标签筛选

页面 SHALL 支持按级别、状态和数据集筛选字段标签。

#### Scenario: 筛选待复核

- **WHEN** 用户选择状态 `SUGGESTED`
- **THEN** 只显示待复核标签
- **AND** 分页信息与筛选条件一致

### Requirement: 人工复核

页面 SHALL 允许有 `metadata:sensitivity:review` 权限的用户确认、拒绝或调整标签级别。

#### Scenario: 确认标签

- **WHEN** 用户点击确认并提交
- **THEN** 标签状态变为 `CONFIRMED`
- **AND** 列表刷新
- **AND** 提示审计已记录

#### Scenario: 无复核权限

- **WHEN** 用户只有 `metadata:sensitivity:read`
- **THEN** 不显示复核操作

### Requirement: 发起扫描

页面 SHALL 允许有 `metadata:sensitivity:scan` 权限的用户发起全工作区或指定数据集的扫描。

#### Scenario: 扫描完成

- **WHEN** 用户发起扫描且后端成功
- **THEN** 页面显示扫描字段数、命中数和耗时
- **AND** 刷新标签列表
