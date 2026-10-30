## MODIFIED Requirements

### Requirement: 历史列表

页面 SHALL 展示问题、用户、状态、行数、耗时、脱敏列和时间。列表筛选 SHALL 由服务端执行，筛选条件与分页信息一致。

#### Scenario: 查看失败记录

- **WHEN** 历史中存在失败问数
- **THEN** 列表显示失败状态
- **AND** 详情抽屉显示错误信息

#### Scenario: 跨页筛选

- **WHEN** 用户在筛选栏选择“失败”
- **THEN** 服务端返回全部匹配记录，而不只是当前页
- **AND** 分页总数与筛选条件一致

## ADDED Requirements

### Requirement: 历史服务端筛选

系统 SHALL 支持关键词、成功/失败和用户名的服务端筛选；筛选条件可组合。

#### Scenario: 关键词匹配问题或 SQL

- **WHEN** 用户输入关键词 `销售`
- **THEN** 返回问题或生成 SQL 包含该关键词的记录
- **AND** 结果仍限定在当前 workspace

#### Scenario: 状态筛选

- **WHEN** 用户选择“成功”
- **THEN** 只返回 `succeeded=true` 的记录
- **WHEN** 用户选择“失败”
- **THEN** 只返回 `succeeded=false` 的记录

#### Scenario: 用户筛选

- **WHEN** 用户输入用户名 `alice`
- **THEN** 只返回 `username=alice` 的记录

#### Scenario: 组合筛选

- **WHEN** 用户同时设置关键词、失败状态和用户名
- **THEN** 三个条件按 AND 组合
- **AND** 空值条件不参与筛选

### Requirement: 筛选状态与 URL

页面 SHALL 将非空筛选条件和分页写入 URL，并在刷新或分享后恢复。

#### Scenario: 刷新保持筛选

- **WHEN** 用户设置关键词、状态和页码后刷新页面
- **THEN** 筛选栏与分页恢复为 URL 中的值
- **AND** 请求使用相同条件

#### Scenario: 清空筛选

- **WHEN** 用户点击“清除筛选”
- **THEN** URL 中移除筛选参数
- **AND** 页码回到第 1 页

### Requirement: 筛选与分页联动

页面 SHALL 在筛选变化时将页码重置为第 1 页，并在翻页/改页大小时保留筛选条件。

#### Scenario: 筛选后翻页

- **WHEN** 用户先筛选失败记录，再进入第 2 页
- **THEN** 第 2 页仍只包含失败记录
- **AND** 页码和总数来自服务端

#### Scenario: 无匹配结果

- **WHEN** 服务端返回 0 条记录
- **THEN** 页面显示“没有匹配的查询记录”
- **AND** 保留当前筛选条件以便调整

### Requirement: 筛选参数校验

系统 SHALL 忽略空筛选值，并 SHALL 将超过 200 字符的关键词截断或拒绝。

#### Scenario: 空关键词

- **WHEN** 请求带空字符串或纯空白 keyword
- **THEN** 后端按未提供 keyword 处理

#### Scenario: 超长关键词

- **WHEN** 请求 keyword 超过 200 字符
- **THEN** 返回 400 校验错误
