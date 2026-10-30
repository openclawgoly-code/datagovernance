# workbench-overview Specification

## Purpose
TBD - created by archiving change workbench-overview. Update Purpose after archive.
## Requirements
### Requirement: 权限感知的工作台

工作台 SHALL 按当前用户权限展示卡片，不请求无权限模块的数据。

#### Scenario: 业务分析人员

- **WHEN** 用户只有 `insight:ask` 与 `metadata:asset:read`
- **THEN** 工作台显示最近问数、最近资产
- **AND** 不请求告警/执行/敏感接口

#### Scenario: 数据管家

- **WHEN** 用户有 `metadata:sensitivity:read`
- **THEN** 工作台显示待复核敏感标签卡
- **AND** 点击进入敏感工作台

### Requirement: 需要处理区块

工作台 SHALL 聚合失败执行、未处理告警和待复核敏感标签。

#### Scenario: 单接口失败

- **WHEN** 告警接口失败但其他接口成功
- **THEN** 告警卡显示错误/不可用
- **AND** 其他卡片仍正常展示

### Requirement: 快捷入口

工作台 SHALL 提供智能问数、数据目录和敏感复核的快捷入口，并受权限控制。

#### Scenario: 无敏感权限

- **WHEN** 用户没有 `metadata:sensitivity:read`
- **THEN** 不显示敏感复核入口
