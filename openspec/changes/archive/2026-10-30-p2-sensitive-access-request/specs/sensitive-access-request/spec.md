## ADDED Requirements

### Requirement: 敏感数据临时访问申请

系统 SHALL 允许有 `metadata:access:request` 的用户提交带原因、敏感类别、最高级别和时长的访问申请。

#### Scenario: 提交申请

- **WHEN** 用户为 `PII` 类别、最高 `L3`、8 小时、填写原因提交申请
- **THEN** 生成 `PENDING` 申请
- **AND** 记录申请人和创建时间

#### Scenario: 非法时长或原因

- **WHEN** duration 不在 1–168 小时或 reason 少于 10 字
- **THEN** 返回 400
- **AND** 不创建申请

#### Scenario: 取消自己的待审批申请

- **WHEN** 申请人在 PENDING 状态取消
- **THEN** 状态变为 `CANCELLED`
- **AND** 其他用户不能取消该申请

### Requirement: 审批与授权

系统 SHALL 允许有 `metadata:access:approve` 的用户审批/拒绝 PENDING 申请；批准时创建带到期时间的 grant。

#### Scenario: 批准

- **WHEN** 审批人批准申请
- **THEN** 申请变为 `APPROVED`
- **AND** 创建 grant，`expiresAt = now + durationHours`
- **AND** 记录审批人和时间

#### Scenario: 拒绝

- **WHEN** 审批人拒绝申请
- **THEN** 状态变为 `REJECTED`
- **AND** 记录 decisionNote（可选）
- **AND** 不创建 grant

#### Scenario: 自审批

- **WHEN** 申请人尝试审批自己的申请
- **THEN** 返回 403
- **AND** 状态保持 PENDING

#### Scenario: 非 PENDING 状态

- **WHEN** 对已批准/已拒绝/已取消的申请再次审批
- **THEN** 返回 409
- **AND** 不重复创建 grant

### Requirement: Grant 有效期

Grant SHALL 在 `expiresAt` 后失效；系统 SHALL 惰性判断过期，不依赖定时任务。

#### Scenario: 过期 grant

- **WHEN** 当前时间超过 grant.expiresAt
- **THEN** grant 不再参与脱敏放行
- **AND** 列表显示 `EXPIRED`

#### Scenario: 撤销 grant

- **WHEN** 有审批权限的用户撤销未过期 grant
- **THEN** 写入 revokedAt/revokedBy
- **AND** 后续查询立即不再使用该 grant

### Requirement: Grant 与问数脱敏

Query Gateway SHALL 只对有效 grant 覆盖的敏感列跳过脱敏，其他敏感列仍按原策略脱敏。

#### Scenario: 覆盖的列

- **WHEN** 用户有 PII/L3 的有效 grant，查询结果含 L2 PII 列
- **THEN** 该列不脱敏
- **AND** L4 PII 列仍脱敏
- **AND** FINANCIAL 等其他类别仍脱敏

#### Scenario: 没有 grant

- **WHEN** 用户没有有效 grant
- **THEN** 所有敏感列按 P1 策略脱敏

#### Scenario: 导出

- **WHEN** 用户使用 CSV/XLSX 导出
- **THEN** 导出复用同一 ask 链路和 grant 判断
- **AND** 不出现页面脱敏、导出明文的旁路

### Requirement: Grant 审计与权限

系统 SHALL 记录申请、审批、拒绝、取消、撤销的审计，并在查询审计中解释使用了哪些 grant。

#### Scenario: 审批审计

- **WHEN** 审批人批准申请
- **THEN** 审计资源类型为 `ACCESS_REQUEST`
- **AND** 动作为 `UPDATE`

#### Scenario: 查询使用 grant

- **WHEN** 一次问数因 grant 未脱敏部分列
- **THEN** 查询审计 detail 包含 grant ID
- **AND** `maskedColumns` 只记录实际脱敏列

#### Scenario: 无权限

- **WHEN** 用户没有 `metadata:access:request` 提交申请
- **THEN** 返回 403
- **WHEN** 用户没有 `metadata:access:approve` 审批
- **THEN** 返回 403
