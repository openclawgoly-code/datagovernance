## ADDED Requirements

### Requirement: 看板 ACL 管理

看板详情 SHALL 显示 ACL 列表，并允许有管理权限的用户添加或移除用户。

#### Scenario: 添加读权限

- **WHEN** 创建者输入用户名并选择 READ
- **THEN** 系统保存 ACL
- **AND** 该用户可在看板列表中看到该看板

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
