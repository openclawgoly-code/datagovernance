## ADDED Requirements

### Requirement: 治理收件箱入口

系统 SHALL 提供 `/workbench/inbox` 页面，只有拥有 `governance:inbox:read` 的用户可以访问；工作台 SHALL 为该权限用户显示入口。

#### Scenario: 工作台进入收件箱

- **WHEN** 用户拥有 `governance:inbox:read`
- **THEN** 工作台“需要处理”区块显示“打开收件箱”
- **AND** 点击进入 `/workbench/inbox`

#### Scenario: 无权限

- **WHEN** 用户没有 `governance:inbox:read`
- **THEN** 不显示收件箱入口
- **AND** 直接访问 `/workbench/inbox` 跳转 `/403`

### Requirement: 跨来源聚合与权限过滤

收件箱 SHALL 聚合失败/超时执行、未处理告警和待复核敏感标签，并只返回当前用户有来源读权限的条目。

#### Scenario: 有全部来源权限

- **WHEN** 用户同时拥有执行、告警、敏感读权限
- **THEN** 收件箱返回三类来源条目
- **AND** 每条包含来源类型、来源 ID、标题、摘要、严重度、时间和来源状态

#### Scenario: 只有部分来源权限

- **WHEN** 用户没有 `metadata:sensitivity:read`
- **THEN** 收件箱不返回敏感标签条目
- **AND** 来源筛选不显示敏感类型
- **AND** 请求不查询敏感服务

#### Scenario: 同一来源去重

- **WHEN** 同一执行/告警/标签被多次聚合
- **THEN** 以 `sourceType + sourceId` 只保留一条

#### Scenario: 聚合上限

- **WHEN** 某个来源超过 200 条可处理记录
- **THEN** 响应标记 `truncated=true`
- **AND** 页面提示仅展示最近 200 条/来源

### Requirement: 收件箱筛选、排序与分页

系统 SHALL 支持按来源类型、收件箱状态、严重度和关键词筛选，并按严重度、时间排序后分页。

#### Scenario: 按来源筛选

- **WHEN** 用户选择“告警”
- **THEN** 只显示 `sourceType=ALERT` 的条目
- **AND** 总数与筛选条件一致

#### Scenario: 按状态筛选

- **WHEN** 用户选择“已移出”
- **THEN** 只显示 `state=DISMISSED` 的条目

#### Scenario: 排序

- **WHEN** 收件箱返回多条
- **THEN** CRITICAL 在 WARNING 之前
- **AND** 同严重度按发生时间倒序

#### Scenario: 分页

- **WHEN** 用户翻到第 2 页
- **THEN** 当前筛选条件保持不变
- **AND** 页码与总数来自服务端响应

### Requirement: 移出与恢复收件箱

系统 SHALL 允许有 `governance:inbox:manage` 和来源读权限的用户把条目移出/恢复到收件箱，且 SHALL NOT 改变来源对象状态。

#### Scenario: 移出告警条目

- **WHEN** 用户点击“移出收件箱”
- **THEN** `gv_inbox_state` 记录为 `DISMISSED`
- **AND** 告警本身仍保持原状态
- **AND** 默认列表不再显示该条目

#### Scenario: 恢复条目

- **WHEN** 用户在“已移出”筛选下点击“恢复”
- **THEN** 状态变为 `OPEN`
- **AND** 条目回到默认列表

#### Scenario: 无管理权限

- **WHEN** 用户只有 `governance:inbox:read`
- **THEN** 不显示移出/恢复操作
- **AND** 后端拒绝 manage 请求

#### Scenario: 来源已消失

- **WHEN** 来源对象已被删除或已不再需要处理
- **THEN** 收件箱不展示该条目
- **AND** 遗留状态行不影响其他条目

### Requirement: 来源跳转

收件箱 SHALL 为每类条目提供回到来源处理页面的入口。

#### Scenario: 打开失败执行

- **WHEN** 用户点击执行条目
- **THEN** 跳转 `/ops/executions`

#### Scenario: 打开待复核标签

- **WHEN** 用户点击敏感条目
- **THEN** 跳转 `/metadata/sensitivity?datasetId={datasetId}&reviewId={labelId}`

### Requirement: 收件箱审计

系统 SHALL 为移出和恢复操作记录审计。

#### Scenario: 移出审计

- **WHEN** 用户移出一条收件箱条目
- **THEN** 审计资源类型为 `INBOX`
- **AND** 动作为 `UPDATE`
- **AND** 资源 ID 包含来源类型和来源 ID
