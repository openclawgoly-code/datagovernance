## ADDED Requirements

### Requirement: 从敏感标签发起访问申请

敏感工作台 SHALL 在敏感标签详情中为有 `metadata:access:request` 的用户提供“申请临时访问”入口，并预填标签的类别和级别。

#### Scenario: 从标签发起

- **WHEN** 用户在某条 L3 PII 标签详情点击“申请临时访问”
- **THEN** 打开申请表单，category=PII、maxLevel=L3 已预填
- **AND** 用户填写原因和时长后提交

#### Scenario: 无申请权限

- **WHEN** 用户没有 `metadata:access:request`
- **THEN** 不显示申请入口
- **AND** 仍可查看标签详情

#### Scenario: 标签没有类别/级别

- **WHEN** 标签缺少 category 或 level
- **THEN** 入口禁用并提示“标签信息不完整”
