## ADDED Requirements

### Requirement: 质量规则管理

系统 SHALL 允许有 `metadata:quality:manage` 的用户为数据集/字段创建、编辑、启停和删除内置质量规则。

#### Scenario: 新建行数规则

- **WHEN** 用户为数据集创建 `ROW_COUNT_RANGE` 规则，配置 min=1、max=100000
- **THEN** 规则保存为启用状态
- **AND** 资产质量页显示该规则

#### Scenario: 字段规则校验

- **WHEN** 用户为 `NOT_NULL` 规则指定不属于该数据集的字段
- **THEN** 返回 400
- **AND** 不创建规则

#### Scenario: 非法配置

- **WHEN** `ROW_COUNT_RANGE` 的 min 大于 max，或比例不在 0–1
- **THEN** 返回 400
- **AND** 不创建/不更新规则

#### Scenario: 只读用户

- **WHEN** 用户只有 `metadata:quality:read`
- **THEN** 可以查看规则和质量结果
- **AND** 不显示编辑、启停、运行按钮

### Requirement: 质量运行

系统 SHALL 支持手动运行单条规则或一个数据集下所有启用规则，并保存每次运行结果。

#### Scenario: 运行单条规则

- **WHEN** 用户点击某条规则的“运行”
- **THEN** 创建一个 `md_quality_run`
- **AND** 成功/失败、指标、耗时写入运行记录
- **AND** 资产质量摘要刷新

#### Scenario: 运行数据集全部规则

- **WHEN** 用户运行数据集全部启用规则
- **THEN** 每条规则各产生一次运行
- **AND** 单条规则失败不阻断其他规则

#### Scenario: 不支持的数据源

- **WHEN** 目标数据源无法通过 DataAccessGateway 执行只读聚合 SQL
- **THEN** 运行状态为 `FAILED`
- **AND** errorMessage 说明“不支持的类型/无法执行”
- **AND** 不把结果标为通过

#### Scenario: 超时

- **WHEN** 质量查询超过配置超时
- **THEN** 运行状态为 `FAILED`
- **AND** 记录超时错误，不无限占用连接

### Requirement: 质量结果与分数

资产详情 SHALL 展示质量分数、每条规则最新状态和最近运行历史。

#### Scenario: 从未运行

- **WHEN** 资产有规则但没有任何运行
- **THEN** 分数显示“未评估”
- **AND** 规则显示“未运行”

#### Scenario: 计算分数

- **WHEN** 资产有最新运行结果
- **THEN** 按规则严重度加权计算 0–100 分
- **AND** 详情展示通过/失败规则明细
- **AND** 明确“分数仅覆盖已配置规则”

#### Scenario: 查看历史

- **WHEN** 用户打开质量 Tab 的历史
- **THEN** 按时间倒序显示运行状态、指标、耗时、执行人
- **AND** 可分页

### Requirement: 质量总览

系统 SHALL 提供质量总览，帮助数据管家定位失败资产和最近运行。

#### Scenario: 失败资产列表

- **WHEN** 用户打开 `/metadata/quality`
- **THEN** 显示最近一次运行失败的资产/规则
- **AND** 可按规则类型和状态筛选
- **AND** 可跳转资产详情质量 Tab

### Requirement: 质量权限与审计

系统 SHALL 使用 `metadata:quality:read/manage` 控制质量读写，并为规则和运行记录审计。

#### Scenario: 无权限运行

- **WHEN** 用户没有 `metadata:quality:manage` 调用 run
- **THEN** 返回 403
- **AND** 不创建运行记录

#### Scenario: 运行审计

- **WHEN** 用户运行质量规则
- **THEN** 审计资源类型为 `QUALITY`
- **AND** 动作为 `EXECUTE`
- **AND** 记录规则/数据集 ID
