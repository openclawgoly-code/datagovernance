## ADDED Requirements

### Requirement: 查询历史的 grant 解释

查询历史 SHALL 保持 `maskedColumns` 为实际脱敏列；当问数使用了临时 access grant 时，详情 SHALL 能解释使用了哪些 grant。

#### Scenario: 使用 grant 的问数

- **WHEN** 用户有有效 grant，问数结果部分列未脱敏
- **THEN** 历史详情显示使用的 grant ID/类别/级别
- **AND** 不把未脱敏列误写成脱敏列

#### Scenario: 无 grant

- **WHEN** 问数没有使用 grant
- **THEN** 历史详情不显示 grant 区块
- **AND** 脱敏列与 P1 一致
