## ADDED Requirements

### Requirement: 业务术语管理

系统 SHALL 提供 workspace 级业务术语的创建、编辑、发布和查询；术语名称在 workspace 内不区分大小写唯一。

#### Scenario: 创建术语

- **WHEN** 有 `metadata:glossary:manage` 的用户创建 term=`GMV`、definition=`商品交易总额`
- **THEN** 术语以 `DRAFT` 状态保存
- **AND** 记录 owner 和创建人

#### Scenario: 重复术语

- **WHEN** 用户创建已存在的 `gmv`（大小写不同）
- **THEN** 后端返回 409 或 400
- **AND** 不创建第二条术语

#### Scenario: 发布术语

- **WHEN** 用户发布术语
- **THEN** 状态变为 `PUBLISHED`
- **AND** 记录 `publishedBy`、`publishedAt`

#### Scenario: 废弃术语

- **WHEN** 用户将已发布术语标为 `DEPRECATED`
- **THEN** 资产详情不再展示该术语
- **AND** 术语管理页仍可查看

### Requirement: 术语绑定资产与字段

系统 SHALL 允许将术语绑定到数据集或字段，并校验字段属于数据集。

#### Scenario: 绑定字段

- **WHEN** 用户把术语绑定到 `catds_1` 的 `catcol_1`
- **THEN** 资产详情和字段抽屉显示该术语
- **AND** 绑定记录包含 datasetId

#### Scenario: 字段不属于数据集

- **WHEN** 请求的 columnId 不属于 datasetId
- **THEN** 返回 400
- **AND** 不创建绑定

#### Scenario: 解绑

- **WHEN** 用户解绑术语
- **THEN** 资产详情不再显示该绑定
- **AND** 术语本身保留

### Requirement: 资产术语展示

资产详情 SHALL 展示绑定到该数据集或其字段的 `PUBLISHED` 术语。

#### Scenario: 查看资产业务术语

- **WHEN** 用户打开绑定术语的资产
- **THEN** 概览/术语面板显示 term、definition、domain 和绑定字段
- **AND** 只显示 PUBLISHED 术语

#### Scenario: 无术语

- **WHEN** 资产没有绑定任何已发布术语
- **THEN** 显示“暂无业务术语”
- **AND** 提供术语页入口（有 read 权限时）

### Requirement: 术语筛选与权限

系统 SHALL 支持关键词、domain、status 筛选，并按 `metadata:glossary:read/manage` 控制读写。

#### Scenario: 关键词搜索

- **WHEN** 用户搜索 `交易`
- **THEN** 返回 term/definition/synonyms 命中的术语
- **AND** 分页总数与筛选一致

#### Scenario: 只读用户

- **WHEN** 用户只有 `metadata:glossary:read`
- **THEN** 可以查看术语和绑定
- **AND** 不显示新建/编辑/发布/绑定操作

#### Scenario: 无权限访问术语页

- **WHEN** 用户没有 `metadata:glossary:read` 访问 `/metadata/glossary`
- **THEN** 跳转 `/403`

### Requirement: 术语审计

系统 SHALL 为术语创建、编辑、发布、绑定和解绑记录审计。

#### Scenario: 发布审计

- **WHEN** 用户发布术语
- **THEN** 审计资源类型为 `GLOSSARY`
- **AND** 动作为 `UPDATE`
- **AND** 记录术语 ID 和操作者
