# insight-dashboard-admin Specification

## Purpose
TBD - created by archiving change dashboard-admin-ui. Update Purpose after archive.
## Requirements
### Requirement: 看板 ACL 管理

看板详情 SHALL 显示 ACL 列表，并允许有管理权限的用户从当前 workspace 的有效成员中搜索、添加或移除用户。系统 SHALL 提供 `GET /api/v1/insight/dashboards/{id}/acl/candidates?keyword=`，且仅返回未授权的 ACTIVE 空间成员。

#### Scenario: 搜索空间成员

- **WHEN** 创建者在 ACL 抽屉输入 `ali`
- **THEN** 候选选择器返回用户名或显示名包含 `ali` 的当前空间成员
- **AND** 最多返回 20 条
- **AND** 已授权用户不出现在候选中

#### Scenario: 添加读权限

- **WHEN** 创建者选择一个候选用户并选择 READ
- **THEN** 系统保存 ACL
- **AND** 该用户可在看板列表中看到该看板
- **AND** 候选列表刷新后不再包含该用户

#### Scenario: 添加管理权限

- **WHEN** 创建者为候选用户选择 MANAGE
- **THEN** ACL 保存为 MANAGE
- **AND** 仍受 `insight:dashboard:manage` 权限约束

#### Scenario: 非空间成员不可选

- **WHEN** 某个平台用户不属于当前 workspace
- **THEN** 候选端点不返回该用户
- **AND** 前端无法通过选择器提交该用户

#### Scenario: 候选接口无权限

- **WHEN** 用户没有 `insight:dashboard:manage` 请求候选端点
- **THEN** 返回 403
- **AND** 不泄露空间成员列表

#### Scenario: 非授权用户

- **WHEN** 用户既不是创建者、没有 MANAGE ACL，也不是平台管理员
- **THEN** 看板列表不返回该看板
- **AND** 直接请求详情返回 403

### Requirement: 看板版本查看

看板详情 SHALL 展示版本号和版本历史摘要。

#### Scenario: 打开版本历史

- **WHEN** 用户点击“版本”
- **THEN** 显示版本号、问题数、创建人和时间

### Requirement: 问题项排序

看板详情 SHALL 允许管理者上移/下移问题项。

#### Scenario: 下移问题项

- **WHEN** 用户点击下移
- **THEN** 调用 PATCH 更新 sortOrder
- **AND** 刷新详情后顺序保持
