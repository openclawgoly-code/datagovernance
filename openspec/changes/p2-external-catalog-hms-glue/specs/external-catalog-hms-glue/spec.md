## ADDED Requirements

### Requirement: 外部目录来源

系统 SHALL 支持创建 `HIVE_METASTORE` 与 `GLUE` 两种只读 Catalog Source；这类来源 SHALL 不要求 `dataSourceId`。

#### Scenario: 创建 HMS 来源

- **WHEN** 有 `metadata:asset:manage` 的用户创建 HMS 来源，填写 uris 与可选 credential
- **THEN** 来源保存为 `HIVE_METASTORE`
- **AND** 来源列表显示 External 标识

#### Scenario: 创建 Glue 来源

- **WHEN** 用户创建 Glue 来源，填写 region 和可选 credentialId
- **THEN** 来源保存为 `GLUE`
- **AND** 未填 credential 时使用 AWS DefaultCredentialsProvider

#### Scenario: 非法来源配置

- **WHEN** HMS/Glue 来源缺少 endpoint/region，或提交 dataSourceId
- **THEN** 返回 400
- **AND** 不创建来源

#### Scenario: 现有来源兼容

- **WHEN** 创建 JDBC/PARQUET_DIRECTORY 来源
- **THEN** 仍要求 dataSourceId
- **AND** 行为与 P1 一致

### Requirement: 外部目录采集

系统 SHALL 通过现有 refresh 流程采集 HMS/Glue 的 database、table/view、column 和 partition；系统 SHALL NOT 复制表数据。

#### Scenario: 刷新 HMS

- **WHEN** 用户刷新 HMS 来源
- **THEN** 目录出现 database/table/view/column/partition
- **AND** 数据集 kind/format/location 来自 Metastore
- **AND** 不读取表数据

#### Scenario: 刷新 Glue

- **WHEN** 用户刷新 Glue 来源
- **THEN** 目录出现 Glue databases/tables/columns/partitions
- **AND** 行数/大小不可得时为 null，不伪造 0

#### Scenario: 部分对象失败

- **WHEN** 某个 database/table 读取失败
- **THEN** 其他对象继续采集
- **AND** 刷新结果显示 warnings

#### Scenario: 认证失败

- **WHEN** HMS/Glue 认证失败或超时
- **THEN** 刷新失败并返回明确错误
- **AND** 保留上一次成功目录快照
- **AND** 不把失败伪装成 0 表成功

### Requirement: 外部目录凭据

系统 SHALL 只通过 `credential_id` 引用秘密，SHALL NOT 把 accessKey/secret 写入 `config_json`。

#### Scenario: Glue 静态凭据

- **WHEN** 用户选择平台凭据
- **THEN** 后端从 `pf_credential` 解析 accessKey/secret
- **AND** 配置 JSON 只保存 region/catalogId 等非秘密字段

#### Scenario: 凭据缺失

- **WHEN** 配置了静态凭据但凭据不存在/为空
- **THEN** 刷新失败并提示凭据错误
- **AND** 不尝试匿名访问

### Requirement: 外部目录标识与限制

Catalog 列表和资产详情 SHALL 标识外部来源，并明确“仅元数据、不直查数据”。

#### Scenario: 外部资产详情

- **WHEN** 用户打开 HMS/Glue 采集的资产
- **THEN** 显示 External 标识
- **AND** 显示来源类型与采集时间
- **AND** 不显示不存在的外部行数/大小

#### Scenario: 查询限制

- **WHEN** 用户尝试把外部目录资产用于 P2 问数数据源
- **THEN** 前端不提供该入口
- **AND** 后端不把外部 Catalog 当作可用 DataSource

### Requirement: 外部目录权限与审计

系统 SHALL 使用现有 Catalog 权限，并为来源创建/刷新记录审计。

#### Scenario: 无权限创建

- **WHEN** 用户没有 `metadata:asset:manage` 创建外部来源
- **THEN** 返回 403

#### Scenario: 刷新审计

- **WHEN** 用户刷新外部来源
- **THEN** 审计资源类型为 `CATALOG_SOURCE`
- **AND** 动作为 `EXECUTE`
- **AND** 记录来源 ID
