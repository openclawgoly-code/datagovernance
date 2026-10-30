# sensitivity-batch-review Specification

## Purpose
TBD - created by archiving change p1-sensitivity-batch-review. Update Purpose after archive.
## Requirements
### Requirement: 批量复核入口

系统 SHALL 在敏感标签列表提供当前页多选；只有拥有 `metadata:sensitivity:review` 的用户可以发起批量复核。

#### Scenario: 有复核权限

- **WHEN** 用户拥有 `metadata:sensitivity:review`
- **THEN** 表格显示多选框
- **AND** 选中至少一条后显示批量确认、批量拒绝和统一调级操作

#### Scenario: 无复核权限

- **WHEN** 用户只有 `metadata:sensitivity:read`
- **THEN** 表格不显示多选框和批量操作
- **AND** 仍可单条查看详情

#### Scenario: 翻页清空选择

- **WHEN** 用户切换分页、筛选条件或刷新列表
- **THEN** 当前选择被清空
- **AND** 页面明确提示批量操作只作用于当前页已选记录

### Requirement: 批量确认与拒绝

系统 SHALL 通过 `POST /api/v1/metadata/sensitivity/columns/batch-review` 批量更新标签状态。

#### Scenario: 批量确认

- **WHEN** 用户选择 10 条标签并提交 `CONFIRMED`
- **THEN** 后端在一次请求内更新所有存在的标签
- **AND** 每条写入 `reviewedBy` 与 `reviewedAt`
- **AND** 响应返回请求数、成功数和失败数

#### Scenario: 批量拒绝

- **WHEN** 用户提交 `REJECTED`
- **THEN** 标签状态变为 `REJECTED`
- **AND** 不修改标签级别

#### Scenario: 统一调级

- **WHEN** 用户选择批量确认并指定 `level=L3`
- **THEN** 所有成功更新的标签级别变为 `L3`
- **AND** 响应不返回完整标签列表

#### Scenario: 未指定级别

- **WHEN** 批量确认时没有指定 level
- **THEN** 每条标签保留原级别

### Requirement: 批量部分失败

系统 SHALL 在部分标签不存在或无权访问时继续处理其余标签，并返回结构化失败列表。

#### Scenario: 部分标签已删除

- **WHEN** 请求包含 10 个 ID，其中 1 个已不存在
- **THEN** 其余 9 个成功更新
- **AND** 响应 `succeeded=9`、`failed=1`
- **AND** 失败列表包含该 ID 与原因
- **AND** 前端提示成功与失败数量

### Requirement: 批量校验与审计

系统 SHALL 校验批量请求的非空、状态、级别和数量，并为批量操作记录审计。

#### Scenario: 超过数量上限

- **WHEN** 请求去重后超过 200 个 ID
- **THEN** 返回 400 校验错误
- **AND** 不更新任何标签

#### Scenario: 非法状态或级别

- **WHEN** `status` 不是 `CONFIRMED/REJECTED` 或 `level` 不是 `L1–L4`
- **THEN** 返回 400 校验错误
- **AND** 不更新任何标签

#### Scenario: 审计

- **WHEN** 批量复核成功执行
- **THEN** 审计记录资源类型为 `SENSITIVITY`
- **AND** 动作为 `UPDATE`

#### Scenario: 无权限调用

- **WHEN** 用户没有 `metadata:sensitivity:review` 调用批量端点
- **THEN** 返回 403
- **AND** 不更新任何标签
