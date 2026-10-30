## ADDED Requirements

### Requirement: 资产业务域与负责人

系统 SHALL 在资产详情展示并允许有 `metadata:asset:govern` 权限的用户维护业务域、负责人和管家。

#### Scenario: 查看未配置资产

- **WHEN** 用户打开没有治理记录的资产
- **THEN** 概览显示业务域“未配置”、负责人“未配置”、认证状态 `NONE`
- **AND** 不因缺少治理记录影响资产字段/血缘/敏感 Tab

#### Scenario: 编辑负责人

- **WHEN** 有治理权限的用户选择当前空间的有效成员作为 owner
- **THEN** 保存后资产详情和目录列表显示该 owner
- **AND** 刷新 Catalog 不会清空该 owner

#### Scenario: 非空间成员

- **WHEN** 用户提交的 owner/steward 不是当前空间 ACTIVE 成员
- **THEN** 后端返回 400
- **AND** 不写入治理记录

#### Scenario: 无治理权限

- **WHEN** 用户只有 `metadata:asset:read`
- **THEN** 可以查看治理面板
- **AND** 不显示编辑入口

### Requirement: 资产认证状态

系统 SHALL 支持 `NONE / CERTIFIED / DEPRECATED` 三种认证状态，并记录认证人、时间和复审日期。

#### Scenario: 认证资产

- **WHEN** 有治理权限的用户将状态设为 `CERTIFIED`
- **THEN** 记录 `certifiedBy=当前用户`、`certifiedAt=now`
- **AND** 资产详情显示认证徽标

#### Scenario: 废弃资产

- **WHEN** 用户将状态设为 `DEPRECATED` 且没有填写说明
- **THEN** 后端返回 400
- **AND** 状态保持不变

#### Scenario: 认证到期只提示

- **WHEN** `reviewDueAt` 早于当前时间
- **THEN** 资产详情显示“待复审”
- **AND** 不阻止资产被查看或问数

### Requirement: 业务域管理

系统 SHALL 提供 workspace 内业务域的查询与维护，业务域 code 在 workspace 内唯一。

#### Scenario: 新建业务域

- **WHEN** 管理员创建 code=`sales`、name=`销售` 的业务域
- **THEN** 返回业务域 ID
- **AND** 资产治理编辑可选择该业务域

#### Scenario: 删除被引用业务域

- **WHEN** 业务域仍被资产引用
- **THEN** 删除请求被拒绝
- **AND** 提示先迁移资产

### Requirement: Catalog 治理筛选

Catalog 列表 SHALL 支持按 domain、owner 和 certificationStatus 筛选，并新增治理摘要字段。

#### Scenario: 筛选已认证资产

- **WHEN** 用户选择 `certificationStatus=CERTIFIED`
- **THEN** 列表只返回已认证资产
- **AND** 分页总数反映筛选结果

#### Scenario: 兼容旧调用

- **WHEN** 调用方不传治理筛选参数
- **THEN** 列表行为与 P0 一致
- **AND** 未配置治理的资产仍正常返回

### Requirement: 治理审计

系统 SHALL 为每次治理写入记录审计。

#### Scenario: 更新治理

- **WHEN** 用户修改 owner 或认证状态
- **THEN** 审计资源类型为 `ASSET_GOVERNANCE`
- **AND** 动作为 `UPDATE`
- **AND** 记录资产 ID 与操作者

#### Scenario: 无权限写入

- **WHEN** 没有 `metadata:asset:govern` 的用户调用 PUT
- **THEN** 返回 403
- **AND** 不修改治理记录
