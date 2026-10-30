## Context

`md_catalog_source.source_type` 注释里已经列出 `HIVE_METASTORE / GLUE / ICEBERG_REST / ENGINE_CATALOG`，但代码只支持 JDBC 与 `PARQUET_DIRECTORY`。`md_catalog_source.data_source_id` 目前是 NOT NULL，且没有平台凭据引用。

HMS/Glue 是元数据目录，不是查询数据源；它们不应该被伪装成 `md_datasource` 的一种连接器。

## Goals / Non-Goals

**Goals:**

- 用现有 Catalog Source/Refresh 流程采集 HMS/Glue 元数据；
- 采集 database/table/view/column/partition；
- 凭据经 `pf_credential` 引用，不落 config JSON；
- 外部来源在 UI 明确标识；
- JDBC/Parquet 行为不变。

**Non-Goals:**

- 不通过 StarRocks 查询外部表；
- 不写 HMS/Glue；
- 不做 Hive ACID 事务表语义；
- 不做 Iceberg REST / Nessie；
- 不做外部表数据预览；
- 不做自动创建/迁移湖表。

## Decisions

### 1. Source 模型扩展

- `md_catalog_source.data_source_id` 改为可空；
- 新增 `credential_id VARCHAR(40)`，可空，指向 `pf_credential`；
- `source_type` 增加：
  - `HIVE_METASTORE`
  - `GLUE`

HMS/Glue 创建时：

- `dataSourceId` 必须为空；
- `credentialId` 可选（Glue 默认 AWS provider chain；静态凭据时必须）；
- `configJson` 只放非秘密配置。

### 2. 配置格式

HMS：

```json
{
  "uris": ["thrift://hms.internal:9083"],
  "warehouse": "s3://lake/warehouse",
  "includeDatabases": ["dwd", "dws"],
  "excludeDatabases": ["tmp"],
  "kerberosPrincipal": "datagov@EXAMPLE.COM",
  "keytabPath": "/etc/security/keytabs/datagov.keytab",
  "timeoutMs": 10000
}
```

P2 HMS 支持无 SASL 与 Kerberos(keytab 在应用主机)两种模式；keytab 路径不是秘密内容，但仍只允许运维可读。

Glue：

```json
{
  "region": "cn-north-1",
  "catalogId": "123456789012",
  "includeDatabases": [],
  "excludeDatabases": ["tmp"]
}
```

Glue 凭据：

- `credentialId` 为空 → AWS DefaultCredentialsProvider；
- 非空 → 从平台凭据解析 accessKey/secretKey，不存 config。

### 3. Provider 映射

新增 `HmsCatalogProvider`、`GlueCatalogProvider`，实现 `CatalogProvider`：

- database → `Namespace(kind=DATABASE)`
- table/view → `Dataset(kind=TABLE/VIEW, format=Hive/Glue parameters, location=StorageDescriptor.location)`
- column → `Column(rawType, ordinal, comment)`
- partition keys/partitions → `Partition`
- 行数/大小外部目录通常不可得 → null，不伪造
- schemaHash 仍由 Catalog 持久化层按 columns 计算

Provider 只做只读元数据调用，不访问表数据。

### 4. 刷新与错误

现有 `POST /catalog/sources/{id}/refresh` 根据 sourceType 选择 Provider：

- 成功：更新 source.lastRefreshedAt、namespace/dataset/column/partition；
- HMS/Glue 不可达、认证失败、超时：刷新失败并保留上次成功快照，错误写入更新结果/来源状态；
- 单个 database/table 失败：记录 warning，继续采集其余对象（P2 结果结构增加 warnings 字段）。

### 5. 前端

- 来源创建对话框增加“Hive Metastore”“AWS Glue”；
- 外部来源隐藏 dataSourceId，显示 endpoint/config 与 credential；
- 来源列表显示 `External` 标签；
- 采集错误显示具体错误，不把“0 表”当作成功；
- 外部来源仍使用 Catalog 详情/资产详情/owner/认证等 P2 能力。

### 6. 权限与审计

沿用 `metadata:asset:read/manage`；`/catalog` 写操作审计为 `ASSET_GOVERNANCE` 或 `CATALOG_SOURCE`（若 ownership change 已建映射则复用）。

## Risks / Trade-offs

- [HMS Kerberos 环境复杂] → P2 支持 keytab，但不做跨平台凭据托管；失败明确提示。
- [Glue 凭据链差异] → 默认链与静态凭据二选一；错误信息指出 region/credential。
- [大目录采集慢] → P2 全量采集 + 超时/告警；P3 做增量/分页同步。
- [External 被误解为可查询] → UI 标注“仅元数据，不支持问数直查”；查询接入留 P3。
- [dataSourceId 可空影响现有校验] → JDBC/Parquet 仍强制非空，分支校验。
- [配置含 endpoint 非秘密] → 允许；accessKey/secret 只来自 credential。

## Migration Plan

1. V31 增加 `credential_id`、放宽 `data_source_id`；
2. 后端发布 source 校验与 Provider；
3. 前端发布来源表单；
4. 回滚前端隐藏类型；已采集外部资产可保留只读。

## Open Questions

- P3 是否通过 StarRocks External Catalog 查询 HMS/Glue 表；
- 是否需要数据库/表 include 正则与增量同步。
