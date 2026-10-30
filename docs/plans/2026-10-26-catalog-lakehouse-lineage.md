# Catalog / Lakehouse / Lineage 实施计划

> 状态：实施中（P10.1 + P10.2a 后端已落地，2026-10-27） · 日期：2026-10-26 · 依据：`docs/analysis-report.md`、`README.md`、`docs/space-contracts/metadata.yaml`
> 范围：Metadata Catalog、对象存储/Parquet/Iceberg、厂商直连库、实时同步、字段级血缘、敏感数据识别
> 规模：约 1000 张表 / 5GB 数据 / 单工作区起步

---

## 0. 结论与默认假设

先给结论：**5GB 不是瓶颈，1000 张表的自动化才是瓶颈**。方案的重点不是买更大的数仓，而是：

1. PostgreSQL 做元数据、catalog、血缘、敏感标签的统一存储；
2. 厂商库用「JDBC 连接器 + 厂商 profile」接，不为每个厂商写一套连接器；
3. Parquet 仓库用 **Iceberg + 对象存储 + JDBC Catalog（PG）**，不要用裸 Parquet 目录当唯一事实源；
4. 实时同步用 **Flink CDC（核心表）+ JDBC 微批（长尾表）**；
5. 字段级血缘用 **Calcite/OpenLineage + PG 图表**，不引入 DataHub/Atlas/图数据库；
6. 敏感识别用 **规则 + 采样 + 中文 PII 校验 + 人工复核**，5GB 可全扫，不必上重型 DSPM。

### 默认假设（如与实际不符，只改本节）

| # | 假设 | 说明 |
|---|---|---|
| A1 | 实时 = 核心表秒级、长尾表分钟级 | 核心 50–100 张表走 CDC；其余走 1–5 分钟微批 |
| A2 | Parquet 仓库目前没有成熟 Hive Metastore/Iceberg | 先上 Iceberg JDBC Catalog；已有 HMS/Glue 时加适配器 |
| A3 | 查询层二选一：StarRocks 主选、Doris 备选 | 以 Parquet/Iceberg 外表查询和复杂 Join 为主时优先 StarRocks |
| A4 | 元数据库沿用 PostgreSQL 16 | 平台元数据、catalog、血缘、敏感标签、Iceberg JDBC Catalog 分 schema |
| A5 | 1000 张表、5GB 为单工作区首期规模 | 后续按工作区/数据源水平扩展 |
| A6 | 厂商库允许只读账号或只读副本 | 不允许写厂商业务库；不做厂商 API 集成 |

### 明确不做

- 不做完整 SQL 查询引擎；查询委托给 StarRocks/Doris/Trino/Spark。
- 不引入 DataHub、Apache Atlas、Neo4j、Elasticsearch；1000 张表用 PG 足够。
- 不把原始业务数据或原始 PII 样本存进 PG。
- 不写厂商业务库；不做厂商 ERP/OA 的 API 对接。
- 不替换现有 `md_catalog_snapshot`，先并存，后续逐步下线。

---

## 1. 现状与差距

| 能力 | 现状 | 差距 |
|---|---|---|
| 元数据库 | PostgreSQL 16（`README.md:75`） | 已满足；需为 catalog/lineage/sensitivity 分 schema |
| 外部数据源 | 10 种：MySQL/PG/Oracle/SQLServer/DM8/Doris/StarRocks/FTP/SFTP/REST | 缺 DB2、Kingbase、TiDB/OceanBase 别名、对象存储 |
| 目录浏览 | `CatalogService` 按路径存 `md_catalog_snapshot` JSON | 不是规范化 catalog；无法跨源搜索/统计/血缘 |
| 厂商适配 | 只有通用 JDBC 连接器 | 缺泛微/金蝶/用友的 vendor profile、系统表过滤、白名单 |
| Parquet | 无 | 无对象存储、无 Parquet schema 推断、无 Iceberg/HMS/Glue |
| 实时同步 | 无 CDC；只有离线同步/批处理 | 缺 Flink CDC/Debezium、schema 演进、Upsert 目标 |
| 字段级血缘 | 只有 `md_relation_edge`（语义映射） | 缺列级血缘图、SQL 解析、OpenLineage 接入 |
| 敏感识别 | 无 | 缺扫描器、规则、标签、复核、脱敏联动 |

关键代码证据：

- `backend/dg-data-spi/.../DataSourceType.java`：10 种类型，Family 只有 `RELATIONAL/MPP/FILE/HTTP`
- `backend/dg-data-connectors/.../DefaultDataAccessGateway.java`：按 JDBC 驱动是否存在过滤可用类型
- `backend/dg-metadata/.../CatalogService.java`：目录快照按路径存 JSON
- `backend/dg-app/.../runner/FileParseRunner.java`：文件解析只支持 CSV/JSON
- `backend/dg-runtime/.../ArtifactService.java`：制品存本地文件系统，没有 S3/OSS/MinIO 客户端
- `V12__intelligence_contract_and_masking.sql`：只有 registry 与 `md_relation_edge`，没有敏感标签表

---

## 2. 目标架构

```text
┌─────────────────────────────────────────────────────────────────────┐
│ PostgreSQL 16                                                       │
│  ├─ 平台元数据（现有 md_* / pf_* / rt_*）                             │
│  ├─ Catalog：md_catalog_source / namespace / dataset / column ...    │
│  ├─ 血缘：md_lineage_node / edge / job                               │
│  ├─ 敏感：md_sensitivity_rule / column_sensitivity / scan_*           │
│  └─ Iceberg JDBC Catalog（独立 schema）                              │
└─────────────────────────────────────────────────────────────────────┘
              ▲                                   ▲
              │ catalog / lineage / labels        │ Iceberg metadata
              │                                   │
┌─────────────┴──────────────┐      ┌─────────────┴──────────────┐
│ Metadata Space             │      │ Object Storage             │
│  CatalogGateway            │      │  S3 / OSS / MinIO / HDFS   │
│  LineageService            │      │  └─ Iceberg tables         │
│  SensitivityService        │      │       └─ Parquet data files│
└─────────────▲──────────────┘      └─────────────▲──────────────┘
              │                                   │
┌─────────────┴──────────────┐      ┌─────────────┴──────────────┐
│ Data Space                 │      │ Query / Sync               │
│  JDBC / ObjectStorage      │      │  StarRocks / Doris         │
│  Parquet / Iceberg Catalog │      │  Flink CDC                 │
└────────────────────────────┘      └────────────────────────────┘
```

边界：

- Metadata 只读写自己的 PG 表；不直接读目标库。
- Data 提供只读连接/目录/Parquet 读取能力；`DataAccessGateway` 仍是 Query-only。
- Runtime/Control 执行 CDC/同步；不把写入能力塞进 Metadata。
- 查询委托给 StarRocks/Doris；平台不实现 SQL 引擎。

---

## 3. PostgreSQL 数据模型

新增迁移建议：

- `V14__catalog_assets.sql`：catalog 规范化表 + `md_datasource.vendor_profile`
- `V15__lineage.sql`：列级血缘
- `V16__sensitivity.sql`：敏感规则/标签/扫描
- 权限种子随各迁移一起写

### 3.1 Catalog

```sql
ALTER TABLE md_datasource ADD COLUMN vendor_profile VARCHAR(48);

CREATE TABLE md_catalog_source (
  id                 VARCHAR(40)  PRIMARY KEY,
  workspace_id       VARCHAR(40)  NOT NULL,
  name               VARCHAR(128) NOT NULL,
  -- JDBC / ICEBERG_JDBC / HIVE_METASTORE / GLUE / ICEBERG_REST /
  -- PARQUET_DIRECTORY / ENGINE_CATALOG
  source_type        VARCHAR(32)  NOT NULL,
  -- JDBC/对象存储指向 md_datasource；HMS/Glue 可为空
  data_source_id     VARCHAR(40),
  -- 端点、warehouse、rootPath、include/exclude、分区规则等
  config_json        TEXT         NOT NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  last_refreshed_at  TIMESTAMPTZ,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by         VARCHAR(40),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by         VARCHAR(40),
  deleted            BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_catalog_source_name
  ON md_catalog_source (workspace_id, name) WHERE deleted = FALSE;
CREATE INDEX idx_md_catalog_source_ds
  ON md_catalog_source (workspace_id, data_source_id) WHERE deleted = FALSE;

CREATE TABLE md_catalog_namespace (
  id           VARCHAR(40)  PRIMARY KEY,
  source_id    VARCHAR(40)  NOT NULL REFERENCES md_catalog_source(id),
  parent_id    VARCHAR(40),
  name         VARCHAR(256) NOT NULL,
  kind         VARCHAR(24)  NOT NULL, -- CATALOG / DATABASE / SCHEMA / DIRECTORY
  path         VARCHAR(1024),
  comment      VARCHAR(512),
  ordinal      INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_catalog_ns_root
  ON md_catalog_namespace (source_id, name)
  WHERE deleted = FALSE AND parent_id IS NULL;
CREATE UNIQUE INDEX uk_md_catalog_ns_child
  ON md_catalog_namespace (source_id, parent_id, name)
  WHERE deleted = FALSE AND parent_id IS NOT NULL;
CREATE INDEX idx_md_catalog_ns_parent
  ON md_catalog_namespace (source_id, parent_id) WHERE deleted = FALSE;

CREATE TABLE md_catalog_dataset (
  id                    VARCHAR(40)  PRIMARY KEY,
  workspace_id          VARCHAR(40)  NOT NULL,
  source_id             VARCHAR(40)  NOT NULL REFERENCES md_catalog_source(id),
  namespace_id          VARCHAR(40),
  name                  VARCHAR(256) NOT NULL,
  qualified_name        VARCHAR(1024) NOT NULL,
  -- TABLE / VIEW / MATERIALIZED_VIEW / PARQUET_DATASET / STREAM / TOPIC
  kind                  VARCHAR(24)  NOT NULL,
  -- PARQUET / ORC / AVRO / ICEBERG / DELTA / HUDI / JDBC
  format                VARCHAR(16),
  location              VARCHAR(2048),
  comment               VARCHAR(1024),
  schema_hash           CHAR(64),
  column_count          INT,
  row_count             BIGINT,
  size_bytes            BIGINT,
  last_modified_at      TIMESTAMPTZ,
  partition_columns_json TEXT,
  properties_json       TEXT,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted               BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_catalog_dataset_qn
  ON md_catalog_dataset (source_id, qualified_name) WHERE deleted = FALSE;
CREATE INDEX idx_md_catalog_dataset_ns
  ON md_catalog_dataset (source_id, namespace_id) WHERE deleted = FALSE;
CREATE INDEX idx_md_catalog_dataset_name
  ON md_catalog_dataset (workspace_id, name) WHERE deleted = FALSE;

CREATE TABLE md_catalog_column (
  id                VARCHAR(40)  PRIMARY KEY,
  dataset_id        VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
  name              VARCHAR(256) NOT NULL,
  ordinal           INT          NOT NULL,
  raw_type          VARCHAR(256),
  canonical_type    VARCHAR(32),
  precision         INT,
  scale             INT,
  nullable          BOOLEAN      NOT NULL DEFAULT TRUE,
  primary_key       BOOLEAN      NOT NULL DEFAULT FALSE,
  partition_key     BOOLEAN      NOT NULL DEFAULT FALSE,
  default_value     VARCHAR(1024),
  comment           VARCHAR(1024),
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_md_catalog_column
  ON md_catalog_column (dataset_id, name);
CREATE INDEX idx_md_catalog_column_name
  ON md_catalog_column (name);

CREATE TABLE md_catalog_schema_version (
  id            VARCHAR(40)  PRIMARY KEY,
  dataset_id    VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
  schema_hash   CHAR(64)     NOT NULL,
  columns_json  TEXT         NOT NULL,
  change_type   VARCHAR(24), -- INITIAL / ADD_COLUMN / DROP_COLUMN / TYPE_CHANGE
  captured_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_md_catalog_schema_ds
  ON md_catalog_schema_version (dataset_id, captured_at DESC);

CREATE TABLE md_catalog_partition (
  id                    VARCHAR(40)  PRIMARY KEY,
  dataset_id            VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
  partition_path        VARCHAR(2048) NOT NULL,
  partition_values_json TEXT,
  file_count            BIGINT,
  size_bytes            BIGINT,
  row_count             BIGINT,
  last_modified_at      TIMESTAMPTZ,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_md_catalog_partition
  ON md_catalog_partition (dataset_id, partition_path);
CREATE INDEX idx_md_catalog_partition_ds
  ON md_catalog_partition (dataset_id, last_modified_at DESC);

CREATE TABLE md_catalog_snapshot_run (
  id            VARCHAR(40)  PRIMARY KEY,
  source_id     VARCHAR(40)  NOT NULL REFERENCES md_catalog_source(id),
  started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  finished_at   TIMESTAMPTZ,
  status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
  stats_json    TEXT,
  error_message VARCHAR(2048)
);

CREATE INDEX idx_md_catalog_snapshot_run
  ON md_catalog_snapshot_run (source_id, started_at DESC);
```

> `md_catalog_dataset` 保留 `workspace_id` 冗余列：跨源搜索/权限过滤不必每次 join `md_catalog_source`，1000 张表规模下这点冗余是值得的。
>
> 文件级清单（`md_catalog_file`）首期不做：1000 张表/5GB 用分区级统计 + 按需列文件即可；否则 PG 里会出现几十万到几百万行，得不偿失。

### 3.2 血缘

```sql
CREATE TABLE md_lineage_node (
  id                 VARCHAR(40)  PRIMARY KEY,
  workspace_id       VARCHAR(40)  NOT NULL,
  node_type          VARCHAR(16)  NOT NULL, -- TABLE / COLUMN / DATASET
  catalog_dataset_id VARCHAR(40),
  catalog_column_id  VARCHAR(40),
  qualified_name     VARCHAR(1024) NOT NULL,
  display_name       VARCHAR(256),
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_md_lineage_node
  ON md_lineage_node (workspace_id, node_type, qualified_name);

CREATE TABLE md_lineage_job (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  job_type      VARCHAR(24)  NOT NULL, -- FLINK / SPARK / DORIS / CDC / MANUAL
  source_ref    VARCHAR(256),
  sql_hash      CHAR(64),
  last_seen_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE md_lineage_edge (
  id             VARCHAR(40)  PRIMARY KEY,
  workspace_id   VARCHAR(40)  NOT NULL,
  from_node_id   VARCHAR(40)  NOT NULL REFERENCES md_lineage_node(id),
  to_node_id     VARCHAR(40)  NOT NULL REFERENCES md_lineage_node(id),
  relation       VARCHAR(24)  NOT NULL DEFAULT 'DERIVE',
  transform_expr TEXT,
  job_id         VARCHAR(40)  REFERENCES md_lineage_job(id),
  confidence     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  origin         VARCHAR(16)  NOT NULL DEFAULT 'AUTO', -- AUTO / MANUAL / OPENLINEAGE
  sql_hash       CHAR(64),
  discovered_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE UNIQUE INDEX uk_md_lineage_edge_job
  ON md_lineage_edge (workspace_id, from_node_id, to_node_id, relation, job_id)
  WHERE job_id IS NOT NULL;
CREATE UNIQUE INDEX uk_md_lineage_edge_no_job
  ON md_lineage_edge (workspace_id, from_node_id, to_node_id, relation)
  WHERE job_id IS NULL;
CREATE INDEX idx_md_lineage_edge_from
  ON md_lineage_edge (workspace_id, from_node_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_md_lineage_edge_to
  ON md_lineage_edge (workspace_id, to_node_id) WHERE status = 'ACTIVE';
```

> 血缘边不删除，SQL 变化时把旧边 `status='SUPERSEDED'`，新边插入。这样“上周的血缘为什么是这样”可复盘。

### 3.3 敏感数据

```sql
CREATE TABLE md_sensitivity_rule (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40),           -- NULL = 平台内置
  name          VARCHAR(128) NOT NULL,
  category      VARCHAR(32)  NOT NULL, -- PII / FINANCIAL / MEDICAL / CREDENTIAL
  level         VARCHAR(8)   NOT NULL, -- L1 / L2 / L3 / L4
  detector_type VARCHAR(24)  NOT NULL, -- REGEX / LUHN / DICTIONARY / COLUMN_NAME
  pattern       VARCHAR(512),
  validator     VARCHAR(64),
  priority      INT          NOT NULL DEFAULT 100,
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by    VARCHAR(40)
);

CREATE UNIQUE INDEX uk_md_sensitivity_rule_builtin
  ON md_sensitivity_rule (name) WHERE workspace_id IS NULL;
CREATE UNIQUE INDEX uk_md_sensitivity_rule_ws
  ON md_sensitivity_rule (workspace_id, name) WHERE workspace_id IS NOT NULL;

CREATE TABLE md_column_sensitivity (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40)  NOT NULL,
  dataset_id    VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
  column_id     VARCHAR(40)  NOT NULL REFERENCES md_catalog_column(id),
  category      VARCHAR(32)  NOT NULL,
  level         VARCHAR(8)   NOT NULL,
  confidence    DOUBLE PRECISION NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'SUGGESTED',
  evidence_json TEXT,         -- 只存脱敏后的样本/命中统计，绝不存原始 PII
  detected_by   VARCHAR(16)  NOT NULL, -- RULE / SCAN / MANUAL / LINEAGE
  detected_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  reviewed_by   VARCHAR(40),
  reviewed_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_md_column_sensitivity
  ON md_column_sensitivity (dataset_id, column_id, category);
CREATE INDEX idx_md_column_sensitivity_level
  ON md_column_sensitivity (workspace_id, level, status);

CREATE TABLE md_scan_run (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40)  NOT NULL,
  source_id     VARCHAR(40),
  started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  finished_at   TIMESTAMPTZ,
  status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
  stats_json    TEXT,
  error_message VARCHAR(2048)
);

CREATE TABLE md_scan_finding (
  id             VARCHAR(40)  PRIMARY KEY,
  run_id         VARCHAR(40)  NOT NULL REFERENCES md_scan_run(id),
  dataset_id     VARCHAR(40)  NOT NULL,
  column_id      VARCHAR(40)  NOT NULL,
  rule_id        VARCHAR(40),
  category       VARCHAR(32)  NOT NULL,
  level          VARCHAR(8)   NOT NULL,
  match_count    BIGINT,
  sample_count   BIGINT,
  confidence     DOUBLE PRECISION,
  sample_masked  VARCHAR(512), -- 脱敏后的样例，不是原值
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_md_scan_finding_run
  ON md_scan_finding (run_id);
CREATE INDEX idx_md_scan_finding_column
  ON md_scan_finding (dataset_id, column_id);
```

### 3.4 权限种子

```sql
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:asset:read',      '查看数据资产目录', 'ACTION', 'menu:metadata', 20, 'METADATA'),
  ('metadata:asset:manage',    '管理数据资产目录', 'ACTION', 'menu:metadata', 21, 'METADATA'),
  ('metadata:asset:refresh',   '刷新数据资产目录', 'ACTION', 'menu:metadata', 22, 'METADATA'),
  ('metadata:lineage:read',     '查看字段血缘', 'ACTION', 'menu:metadata', 23, 'METADATA'),
  ('metadata:lineage:manage',   '维护字段血缘', 'ACTION', 'menu:metadata', 24, 'METADATA'),
  ('metadata:sensitivity:read', '查看敏感标签', 'ACTION', 'menu:metadata', 25, 'METADATA'),
  ('metadata:sensitivity:scan', '发起敏感扫描', 'ACTION', 'menu:metadata', 26, 'METADATA'),
  ('metadata:sensitivity:review','复核敏感标签','ACTION', 'menu:metadata', 27, 'METADATA');
```

角色：`ws_admin` 全给；`ws_developer` 给 read/manage/refresh/scan/review；`ws_viewer` 只给 read。UI 菜单可在前端排期时再加，避免死链。

---

## 4. SPI 与接口

### 4.1 Catalog

```java
public enum CatalogSourceType {
    JDBC, ICEBERG_JDBC, HIVE_METASTORE, GLUE, ICEBERG_REST,
    PARQUET_DIRECTORY, ENGINE_CATALOG
}

public record CatalogSourceConfig(
        String id,
        String workspaceId,
        CatalogSourceType type,
        String dataSourceId,       // JDBC/对象存储连接
        String endpoint,           // HMS/Glue/Iceberg REST
        String warehouse,          // Iceberg warehouse
        String rootPath,            // Parquet 目录
        Map<String, String> properties,
        String includePattern,
        String excludePattern,
        String partitionSpecJson
) {}

public record CatalogCapabilities(
        boolean canBrowseNamespaces,
        boolean canBrowseDatasets,
        boolean canReadSchema,
        boolean canListPartitions,
        boolean canQuery,
        boolean canCreateTable
) {}

public record CatalogNamespace(
        String path, String name, String kind, String comment) {}

public record CatalogDataset(
        String namespacePath, String name, String qualifiedName,
        String kind, String format, String location,
        Long rowCount, Long sizeBytes, Instant lastModifiedAt,
        String schemaHash, List<String> partitionColumns) {}

public record CatalogColumn(
        String name, int ordinal, String rawType, String canonicalType,
        Integer precision, Integer scale, boolean nullable,
        boolean primaryKey, boolean partitionKey, String comment) {}

public record CatalogPartition(
        String path, String valuesJson, long fileCount,
        long sizeBytes, Instant lastModifiedAt) {}

public record CatalogAddress(
        String namespacePath,   // 库/模式/目录，例如 "ecology.dbo" 或 "warehouse/orders"
        String dataset,         // 表/数据集，例如 "formtable_main_1" 或 "orders"
        String partition,       // 分区路径，可为空
        String objectPath       // 对象存储路径，可为空
) {}

public record CatalogAssetPage(
        List<CatalogNamespace> namespaces,
        List<CatalogDataset> datasets,
        List<CatalogColumn> columns,
        List<CatalogPartition> partitions
) {}

public interface CatalogProvider {
    Set<CatalogSourceType> supportedTypes();
    CatalogCapabilities capabilities(CatalogSourceType type);
    ConnectivityResult test(CatalogSourceConfig config);
    CatalogAssetPage listNamespaces(CatalogSourceConfig config, String parentPath);
    CatalogAssetPage listDatasets(CatalogSourceConfig config, String namespacePath);
    CatalogAssetPage listColumns(CatalogSourceConfig config, CatalogAddress address);
    CatalogAssetPage listPartitions(CatalogSourceConfig config, CatalogAddress address);
}
```

> 刻意不复用现有 `CatalogPath` / `CatalogPage`：后者是“数据源目录浏览”的 UI 模型（库/模式/表/列/文件），而 catalog 采集还要表达 namespace、dataset、partition、object path。混用会让两个功能的演进互相牵制。
>
> **P10.1 的实施偏差**：JDBC 采集先直接复用现有 `DataAccessGateway`，没有为单一来源先造一层空的 `CatalogProvider`。`CatalogProvider` 在 P10.2 引入对象存储/Parquet/HMS 时再落地 —— 那时才有两个以上实现，抽象才有信息量。本文保留接口设计作为 P10.2 的目标形状。

`CatalogGateway` 放在 `dg-data-spi`，实现放 `dg-data-connectors`，`dg-metadata` 通过它采集；**仍然只读**，不增加写入方法。

### 4.2 对象存储 / Parquet

```java
public enum ObjectStorageType { S3, OSS, MINIO, HDFS }

public record ObjectStorageConfig(
        ObjectStorageType type, String endpoint, String region,
        String bucket, String rootPath, String accessKey, String secretKey,
        boolean pathStyleAccess, int connectTimeoutMillis, int readTimeoutMillis
) {}

public interface ObjectStorageConnector {
    Set<ObjectStorageType> supportedTypes();
    ConnectivityResult test(ObjectStorageConfig config);
    List<ObjectEntry> list(ObjectStorageConfig config, String prefix,
                           String delimiter, int maxKeys);
    InputStream open(ObjectStorageConfig config, String path);
    ObjectMetadata stat(ObjectStorageConfig config, String path);
}
```

`ParquetCatalogProvider`：

- 用 `parquet-mr`/`parquet-avro` 读 footer（schema、行数、列类型）。
- 从路径解析 Hive 风格分区：`dt=2026-10-26/hour=09/`。
- 支持 `includePattern`/`excludePattern`（如 `*.parquet`、`_SUCCESS` 忽略）。
- 先做“目录 → 数据集 → 字段 → 分区”四级；文件级清单按需分页，不默认落 PG。

### 4.3 厂商 profile

```java
public record VendorProfile(
        String code,                 // WEAVER_ECOLOGY / KINGDEE_EAS / YONYOU_U8 ...
        String displayName,
        DataSourceType defaultType,  // SQLSERVER / ORACLE / MYSQL / POSTGRESQL / DB2 / DAMENG / KINGBASE
        int defaultPort,
        List<String> systemSchemas,
        List<String> systemTablePatterns,
        List<String> includePatterns,
        Map<String, String> defaultProperties,
        String docUrl
) {}
```

首期内置：

| code | 说明 | 默认 DB |
|---|---|---|
| `WEAVER_ECOLOGY` | 泛微 e-cology | SQL Server / Oracle / MySQL / DM / Kingbase |
| `WEAVER_EOFFICE` | 泛微 e-office | MySQL |
| `KINGDEE_EAS` | 金蝶 EAS | Oracle / SQL Server / DB2 / MySQL / PG / DM |
| `KINGDEE_K3CLOUD` | 金蝶 K/3 Cloud / 云星空 | SQL Server / Oracle |
| `YONYOU_NC` | 用友 NC/NCC | Oracle / SQL Server |
| `YONYOU_U8` | 用友 U8/U8+ | SQL Server / Oracle / DM / Kingbase / PG（实验） |
| `YONYOU_YONBIP` | 用友 YonBIP | Oracle / MySQL / PG |
| `GENERIC` | 通用 | 按实际 DB |

> profile 只做“默认值 + 系统表过滤 + 表白名单”，不改变 `DataSourceType`。真正的表名模式必须在客户 POC 现场用真实库确认，不靠猜。

### 4.4 CDC / 同步

```java
public record CdcSourceDescriptor(
        String dataSourceId, DataSourceType type,
        List<String> includeTables, List<String> excludeTables,
        String serverId, Map<String, String> properties) {}

public record CdcSinkDescriptor(
        String type, // ICEBERG / STARROCKS / DORIS / KAFKA
        String target, Map<String, String> properties) {}

public record CdcJobSpec(
        String name, CdcSourceDescriptor source, CdcSinkDescriptor sink,
        String startupMode, int parallelism, String schemaEvolutionPolicy) {}
```

- Control 负责把 `CdcJobSpec` 编译成 Flink SQL/作业配置；
- Runtime 通过 Flink REST API 提交/监控，不在 Spring Boot 内嵌 Flink；
- 不把写能力加到 `DataAccessGateway`。

---

## 5. 连接器与厂商 profile 落地

### 5.1 新增类型

| 类型 | Family | 驱动 | 端口 | 说明 |
|---|---|---|---|---|
| `DB2` | RELATIONAL | `com.ibm.db2.jcc.DB2Driver` | 50000 | 金蝶 EAS 老版本、部分用友 NC |
| `KINGBASE` | RELATIONAL | `com.kingbase8.Driver` | 54321 | 泛微/用友信创场景；PG 兼容但需独立方言 |
| `S3` | OBJECT_STORAGE | AWS SDK v2 | 443 | S3/MinIO/OSS S3 兼容端点 |
| `OSS` | OBJECT_STORAGE | Aliyun OSS SDK | 443 | 阿里云 OSS 原生 |
| `MINIO` | OBJECT_STORAGE | AWS SDK v2 | 9000 | 私有化对象存储 |
| `HDFS` | OBJECT_STORAGE | Hadoop FileSystem | 8020 | 自建 Parquet 湖 |

TiDB/OceanBase 首期不新增类型：用 `MYSQL` + `vendor_profile` 标注兼容性；等真实 POC 再决定是否独立类型。

### 5.2 采集策略

- 1000 张表一次全量：按 schema 分页，先拉表清单，再按需拉列；不要一次 `getColumns(null,null,"%","%")`。
- 系统 schema/表排除：`information_schema`、`mysql`、`performance_schema`、`pg_catalog`、`sys`、`SYSTEM`、`SYS` 等；厂商表模式由 profile 配置。
- 快照幂等：`source_id + qualified_name` 唯一；刷新时 upsert，不重复插入。
- 增量刷新：先按 `last_modified`/`table count` 比对；列变更写 `md_catalog_schema_version`。
- 只读：所有连接器禁止 DDL/DML；查询走 `ReadOnlySqlGuard`。

---

## 6. 实时同步

### 6.1 架构

```text
源库（只读副本）
  │
  ├─ 核心 50–100 表：Flink CDC（全量+增量）
  └─ 长尾 900+ 表：JDBC 微批（1–5 分钟，按 updated_at/主键水位）
        │
        ▼
Flink（可选 Kafka 缓冲）
  ├─ 清洗/标准化/敏感标记
  ├─ Sink 1：Iceberg（Parquet，JDBC Catalog 在 PG）
  └─ Sink 2：StarRocks/Doris（热层，Primary/Unique Key）
```

### 6.2 为什么不全量 CDC

- 1000 张表 = 1000 个 CDC 表级对象；Oracle LogMiner/SQL Server CDC 的权限、日志保留、DDL 同步都是运维负担。
- 5GB 数据量很小，分钟级微批完全能满足大量主数据表；把 CDC 留给订单、财务、库存等核心表。
- 混合模式降低对厂商生产库的影响，也降低 Flink 作业数。

### 6.3 CDC 可行性矩阵

| 源库 | CDC 方式 | 注意 |
|---|---|---|
| SQL Server | CDC / Change Tracking + Debezium/Flink CDC | 需要开启库/表 CDC；只读副本 |
| Oracle | LogMiner / XStream + Flink CDC | 权限、Redo 保留、性能；优先只读副本 |
| MySQL | binlog + Debezium/Flink CDC | binlog 保留、GTID |
| PostgreSQL | 逻辑复制 + Debezium/Flink CDC | publication/slot；WAL 保留 |
| DB2 | IBM CDC / InfoSphere；Debezium DB2 需验证 | 版本/许可 |
| 达梦/金仓 | 时间戳/触发器/厂商工具 | 不假设标准 CDC 可用 |

### 6.4 目标端语义

- **StarRocks**：Primary Key 表，Flink/Stream Load Upsert；适合实时更新。
- **Doris**：Unique Key 表，Flink/Stream Load Upsert；注意高频更新写放大。
- **Iceberg**：主键 + upsert/merge；Flink Iceberg Connector；需要小文件 compaction。
- **Paimon（备选）**：Flink 原生流式 Upsert，Parquet 存储；如果 CDC 是主链路，Paimon 比 Iceberg 更省事；但生态/查询层支持需 POC。

一致性：优先“至少一次 + 幂等 Upsert”，不要为了 exactly-once 把架构复杂化；用主键/唯一键保证重放结果一致。

---

## 7. 字段级血缘

### 7.1 提取路径

| 来源 | 方法 | 覆盖 |
|---|---|---|
| 平台 ETL SQL（离线同步/批处理） | Apache Calcite 解析 `INSERT INTO ... SELECT` | 高 |
| Flink SQL / Spark SQL | OpenLineage `ColumnLineageDatasetFacet` | 高 |
| CDC 同步 | 源表→目标表同名字段自动映射；重命名按配置 | 高 |
| Doris/StarRocks | audit log 解析 DDL/DML；`INSERT INTO ... SELECT` | 中 |
| 存储过程/动态 SQL | 标记 `UNKNOWN`，人工补 | 低 |

### 7.2 服务

- `LineageExtractor`：输入 SQL/作业，输出列级边；
- `LineageService`：合并自动/人工边，写 `md_lineage_edge`；SQL 变化时旧边 `SUPERSEDED`；
- `LineageQueryService`：递归 CTE 做 upstream/downstream/impact；
- `OpenLineageController`：接收外部作业事件；内部作业走 `ApplicationEventPublisher`。

### 7.3 1000 张表的现实目标

- 自动覆盖率 ≥ 80%（按字段）；
- 核心财务/订单/客户表人工确认 100%；
- 其余标记 `confidence < 1`，UI 显示“自动推断”；
- 不做“100% 自动”，存储过程和厂商自定义逻辑一定会漏。

---

## 8. 敏感数据识别

### 8.1 规则

内置规则（中文场景）：

- 身份证：18/15 位 + 校验位
- 手机号：`1[3-9]\d{9}`
- 银行卡：Luhn + 长度
- 邮箱、地址、姓名
- 医疗：病历号、诊断、处方、检验结果
- 凭据：密码、token、AK/SK、JDBC 连接串

字段名/注释命中作为“强提示”，内容扫描作为“证据”；两者结合降误报。

### 8.2 扫描

- 每表采样 100–1000 行；高风险表全扫。
- 5GB 全扫可行，安排在低峰；只读账号、限流。
- 证据只存脱敏样例/命中统计，不存原始 PII。
- 输出 `md_scan_finding` + `md_column_sensitivity(status=SUGGESTED)`。

### 8.3 复核与传播

- UI 复核：确认/拒绝/改级别；
- 沿血缘传播：直接复制继承；mask/hash/聚合降级；Join 键取最高级；
- 与脱敏策略联动：`L3/L4` 查询时默认脱敏；导出/外发需要审批；
- 审计：扫描、复核、敏感访问都记 `AuditRecord`。

---

## 9. API / UI / 权限

### 9.1 API

```text
GET  /api/v1/metadata/catalog/sources
POST /api/v1/metadata/catalog/sources
POST /api/v1/metadata/catalog/sources/{id}/refresh
GET  /api/v1/metadata/catalog/sources/{id}/namespaces
GET  /api/v1/metadata/catalog/sources/{id}/datasets
GET  /api/v1/metadata/catalog/datasets/{id}
GET  /api/v1/metadata/catalog/datasets/{id}/columns
GET  /api/v1/metadata/catalog/datasets/{id}/partitions

GET  /api/v1/metadata/lineage/columns/{columnId}/upstream
GET  /api/v1/metadata/lineage/columns/{columnId}/downstream
POST /api/v1/metadata/lineage/edges            # 人工补录
POST /api/v1/metadata/openlineage/events       # 外部作业事件

GET  /api/v1/metadata/sensitivity/columns
POST /api/v1/metadata/sensitivity/scan
POST /api/v1/metadata/sensitivity/{id}/review
GET  /api/v1/metadata/sensitivity/rules
```

### 9.2 UI

- 数据目录：来源 → 命名空间 → 数据集 → 字段/分区；显示“最后采集时间/采集人”。
- 血缘：表级/字段级切换；上游/下游；影响分析。
- 敏感：按级别/类别/状态过滤；复核工作台；扫描任务。
- 厂商 profile：新建数据源时选“泛微/金蝶/用友”，自动填 DB 类型/端口/系统表过滤。

### 9.3 权限

见 §3.4；所有查询带 `workspace_id`，跨空间 404。

---

## 10. 分期计划

| 阶段 | 内容 | 产出 | 预估 |
|---|---|---|---|
| P10.1 | PG catalog 表 + JDBC catalog 采集（复用 DataAccessGateway）+ 厂商 profile 字段 + DB2/Kingbase 连接器 | 1000 表可采集；泛微/金蝶/用友常见库可连 | 2 周（后端已完成，UI 预设待排期） |
| P10.2 | 对象存储 + Parquet 目录采集 + Iceberg JDBC Catalog | Parquet 仓库可浏览；Iceberg 表可采集 | 3 周（P10.2a LOCAL/S3 系已落地；HDFS/Iceberg 待续） |
| P10.3 | Flink CDC + 微批 + Iceberg/StarRocks Sink + schema 演进 | 核心表秒级、长尾分钟级；可重放 | 3 周 |
| P10.4 | Calcite/OpenLineage 血缘 + 敏感扫描/复核/传播 | 字段级血缘 + 敏感标签 + 脱敏联动 | 3 周 |
| P10.5 | 1000 表/5GB POC + 性能/准确率验收 | 验收报告 + 调优清单 | 1 周 |

依赖顺序：P10.1 → P10.2 → P10.3 → P10.4；P10.4 的血缘可以在 P10.2 后提前做一部分。

---

## 11. 验收标准

| 项 | 目标 |
|---|---|
| Catalog 首采 | 1000 张表 ≤ 5 分钟（按 schema 分页） |
| Catalog 增量 | ≤ 1 分钟；列变更 100% 生成 schema version |
| Catalog 查询 | UI p95 < 1s（1000 表规模） |
| CDC 延迟 | 核心表 p95 < 5s；长尾微批 < 5min |
| 同步正确性 | 重放/重复投递不产生重复行（主键/Upsert） |
| Schema 演进 | 新增列自动同步；删列/改类型进入告警+人工确认 |
| 血缘自动覆盖 | ≥ 80% 字段；核心表 100% 人工确认 |
| 血缘影响查询 | 3 跳以内 < 1s |
| 敏感识别 | 已知 PII 测试集召回 ≥ 95%；误报 ≤ 10% |
| 敏感扫描 | 5GB 全扫 ≤ 30 分钟；只读、限流 |
| 安全 | PG 不存原始 PII；不写厂商库；跨空间 404；审计完整 |
| 运维 | 单工作区 1000 表，PG < 10GB；Flink/StarRocks 可监控 |

---

## 12. 风险与回滚

| 风险 | 处理 |
|---|---|
| 厂商库 CDC 权限/日志 | 只读副本；Oracle LogMiner 单独评估；DB2/达梦/金仓先微批 |
| 1000 表 CDC 作业爆炸 | 按源库/schema 分组；核心 CDC + 长尾微批 |
| Parquet 小文件 | Iceberg/Paimon + 定时 compaction；限制单分区文件数 |
| Iceberg JDBC Catalog 并发 | 单写多读；刷新串行；后续可换 HMS/Glue/REST |
| Flink 运维 | 外部 Flink 集群 + REST 提交；先做 2–3 条核心管道 |
| 敏感扫描影响生产 | 只读副本；采样；限流；低峰；不存原始值 |
| 血缘误报 | confidence + 人工复核；存储过程标记 UNKNOWN |
| PG catalog 膨胀 | 分区级统计，不存文件级；按 workspace/source 清理历史 |
| 驱动许可 | DB2/Kingbase 驱动需客户提供/确认分发许可 |

回滚：

- 新表全部 additive，不影响现有 `md_catalog_snapshot` 与接口；
- catalog/lineage/sensitivity 功能用配置开关关闭；
- CDC 作业可停，源库无写入；
- 不删除现有数据源/目录快照。

---

## 13. 明确不做

- 不做 DataHub/Atlas/Neo4j/Elasticsearch 替换；
- 不做完整 SQL 引擎/BI；
- 不做厂商 ERP/OA API 对接；
- 不写厂商业务库；
- 不在 PG 存原始 PII 或原始业务数据；
- 不做文件级全量清单（首期只做分区级）。

---

## 14. 待确认

1. 泛微/金蝶/用友的具体产品与版本；是否存在 DB2/金仓/TiDB/OceanBase/Access 场景。
2. 实时延迟目标：秒级还是分钟级；核心表清单。
3. Parquet 仓库是裸目录还是已有 Iceberg/Hive/Glue/Paimon；对象存储是 S3/OSS/MinIO/HDFS。
4. 查询层是否必须 Doris；是否接受 StarRocks POC。
5. 厂商库是否提供只读副本/只读账号；DB2/Kingbase 驱动许可。

---

## 15. 参考

- OLAP 选型（2026）：ClickHouse / Doris / StarRocks 对比  
  https://www.dtstack.com/zh-cn/blogs/olap-engine-comparison-2025/
- DB-Engines：Doris vs ClickHouse vs StarRocks  
  https://db-engines.com/en/system/Apache+Doris%3BClickHouse%3BStarRocks
- Flink CDC 实时数据集成架构  
  https://www.dtstack.com/zh-cn/blogs/flink-cdc-integration-architecture/
- OpenLineage / Marquez  
  https://openlineage.io/ · https://marquezproject.ai/
- Apache Iceberg JDBC Catalog  
  https://iceberg.apache.org/docs/latest/jdbc/
- Apache Paimon（Flink 原生 Upsert 备选）  
  https://paimon.apache.org/
- 泛微 e-cology 兼容性  
  https://www.weaver.com.cn/subpage/aboutus/news/news-detail.html?id=23240
- 金蝶 EAS 外部数据库类型  
  https://vip.kingdee.com/eascloud/help/8.6hybrid/BAS.web/topics/noname23.html
- 中文敏感数据扫描规则参考  
  https://github.com/ruoji6/database_scan

---

## 16. 实施记录（P10.1 · 2026-10-27）

### 已完成

- **迁移 `V14__catalog_assets.sql`**：7 张 catalog 表 + `md_datasource.vendor_profile` + `metadata:asset:*` 权限种子。
- **数据源类型**：新增 `DB2`、`KINGBASE`；新增 `Db2Connector`、`KingbaseConnector`；补类型映射、建表/ UPSERT 方言、JDBC URL、连接参数校验。
- **厂商预设**：新增 `VendorProfile` 枚举（泛微 e-cology/e-office、金蝶 EAS/K3 Cloud、用友 NC/U8/YonBIP、GENERIC），并写入 `DataSourceUpsertCommand` / `DataSourceEntity` / `DataSourceView` / `DataSourceController`。
- **Catalog 采集**：新增 `CatalogAssetService` 与 `CatalogAssetController`：
  - `POST /api/v1/metadata/catalog/sources`
  - `POST /api/v1/metadata/catalog/sources/{id}/refresh`
  - `GET  /api/v1/metadata/catalog/sources`
  - `GET  /api/v1/metadata/catalog/sources/{id}/datasets`
  - `GET  /api/v1/metadata/catalog/datasets/{id}`
  - `GET  /api/v1/metadata/catalog/datasets/{id}/columns`
- **测试**：`TypeMappersTest`、`ConnectorContractTest`、`DialectDdlTest`、`DialectUpsertTest`、`ConnectionRequirementsTest`、`CatalogAssetServiceTest` 已补 DB2/Kingbase/目录采集用例。

### 端到端验证

临时 PostgreSQL 15 + 空库启动：

- Flyway 执行到 `v14`，7 张 catalog 表与权限种子存在。
- 用平台自身 PG 作为 JDBC 数据源，连通性测试通过后采集：
  - `namespaces=4`、`datasets=40`、`columns=516`，耗时约 1.5s。
- 第二次 refresh：仍为 40 张表 / 516 字段，schema_version 不重复增长（40 条），说明 upsert 幂等。
- 数据集详情能返回字段、原始类型、规范类型、主键标记。

### 与计划的偏差

1. **权限码改名**：`metadata:catalog:read/manage` 已被 V3 的数据源目录占用，新目录改用 `metadata:asset:read/manage/refresh`。
2. **`CatalogProvider` 延后**：P10.1 直接复用 `DataAccessGateway`；P10.2 引入对象存储/Parquet/HMS 时再落地 `CatalogProvider`。
3. **DB2/Kingbase 驱动不打包**：连接器已实现，驱动需现场安装/确认许可；未安装时不会出现在 `availableTypes`。
4. **UI 厂商预设未做**：后端字段与枚举已就绪，前端下拉与自动填默认值留到 UI 排期。

### 下一步（P10.2）

- 对象存储（S3/OSS/MinIO/HDFS）+ Parquet 目录采集 + Iceberg JDBC Catalog。
- 前端：厂商 profile 预设、目录来源向导、数据集/字段浏览页。
- 血缘/敏感：P10.4 的 PG 表结构与采集器。



---

## 17. 实施记录（P10.2a · 2026-10-27）

### 已完成

- **对象存储类型**：`LOCAL` / `S3` / `MINIO` / `OSS`，`Family.OBJECT_STORAGE`；`ObjectStorageConnector` 负责连通性测试。
- **Catalog SPI**：`CatalogProvider` / `CatalogGateway` / `CatalogAssets`；`DefaultCatalogGateway` 负责注册与路由。
- **Parquet Provider**：`ParquetCatalogProvider` + `DuckDbParquetSupport`：
  - DuckDB 读 `glob` / `read_parquet` / `parquet_file_metadata`
  - 数据集识别：剥离任意 `key=value` 分区段
  - schema/分区列/row count/分区文件数/本地文件大小
  - S3/MinIO/OSS 通过 DuckDB httpfs + endpoint/access key 配置
- **Catalog 服务**：`CatalogAssetService` 支持 `PARQUET_DIRECTORY` 来源；新增分区落库与 `GET /datasets/{id}/partitions`。
- **前端**：数据源表单增加对象存储分支（Endpoint/Bucket 或本地根目录）；类型联合增加 `OBJECT_STORAGE`。

### 端到端验证

本地目录（`LOCAL`）作为 Parquet 湖：

- 2 个数据集：`orders`（3 列，`dt` 分区）、`customers`（2 列）
- 首次 refresh：`datasets=2, columns=5, partitions=1`
- 第二次 refresh：schema version 不重复增长，说明幂等
- `orders` 详情返回 `id/name/dt` 与分区 `dt=2026-10-27`；Provider 测试断言 `dt.partitionKey=true`，`ColumnView` 已透出该字段
- `mvn -pl backend/dg-app -am test`：BUILD SUCCESS

### 限制

- S3/MinIO/OSS 只用 DuckDB httpfs 代码路径验证，未在真实对象存储上跑；首次使用可能需要下载 httpfs 扩展。
- HDFS 尚未接入（需要 Hadoop/WebHDFS）。
- Iceberg/Hive Metastore/Glue Provider 尚未实现；当前 Parquet 目录是“无 metastore”模式。
- DuckDB JDBC 使 app jar 增大约 80MB（含多平台 native）；生产可考虑按平台裁剪或独立 sidecar。
- 前端目录浏览页尚未接 `PARQUET_DATASET`/分区展示。

---

## 18. 实施记录（P10.4 基础版 · 2026-10-27）

### 已完成

- **迁移 `V16__lineage_sensitivity.sql`**：
  - `md_lineage_node` / `md_lineage_job` / `md_lineage_edge`
  - `md_sensitivity_rule` / `md_column_sensitivity` / `md_scan_run` / `md_scan_finding`
  - 内置 8 条中文敏感规则（身份证、手机号、银行卡、邮箱、姓名、地址、医疗、密码/密钥）
  - `metadata:lineage:*` / `metadata:sensitivity:*` 权限种子
- **血缘服务**：`LineageService`
  - 作业级列血缘上报；同一作业复用/更新边，旧边标 `SUPERSEDED`
  - 人工补录边
  - 上游/下游/影响分析（BFS,深度上限 5）
- **敏感服务**：`SensitivityService`
  - 按字段名/注释匹配内置规则
  - 人工复核 `CONFIRMED / REJECTED`，自动扫描不覆盖人工结论
  - 扫描运行记录与命中明细
- **API**：
  - `POST /api/v1/metadata/lineage/jobs`
  - `POST /api/v1/metadata/lineage/edges`
  - `GET  /api/v1/metadata/lineage/columns/{id}/upstream|downstream|impact`
  - `GET  /api/v1/metadata/sensitivity/rules|columns`
  - `POST /api/v1/metadata/sensitivity/scan`
  - `POST /api/v1/metadata/sensitivity/columns/{id}/review`

### 端到端验证

在 Parquet 湖中增加 `sensitive_users` 数据集（`user_phone` / `id_card_no` / `email`）：

- 全工作区扫描 9 个字段，命中 3 条：
  - `email` → PII / L2
  - `user_phone` → PII / L3
  - `id_card_no` → PII / L4
- 复核 `id_card_no` 为 `CONFIRMED` 后再次扫描，状态保持 `CONFIRMED`，未被自动规则覆盖。
- 人工补录 `ods.users.id_card_no → dwd.users.id_card_no` 后，上游查询返回 2 个节点 + 1 条边。

### 限制

- 当前只做**字段名/注释规则**，未做样本内容扫描（身份证校验位、手机号正则、银行卡 Luhn）。
- 血缘写入 API 已就绪，但尚未接 SQL 解析/OpenLineage；当前需要 ETL 作业或人工调用。
- 敏感标签尚未沿血缘传播（直接复制继承、mask/聚合降级）。
- 前端还没有血缘图/敏感复核工作台。

### 下一步

- P10.3：Flink CDC/微批同步。
- P10.4b：内容采样扫描 + 血缘传播 + 前端血缘图/复核工作台。
- P10.2b：HDFS/Iceberg/Hive Metastore 适配。

---

## 19. 合成测试数据脚本（2026-10-28）

新增 `scripts/seed-synthetic-warehouse.py`，用 DuckDB 直接生成 Parquet 数据湖，不依赖 Spark/Hadoop/Faker。

结构：

- ODS 400 张、DWD 400 张、DWS 200 张，共 1000 张表；
- ODS/DWD 按 `dt=` 分区（默认 3 个），DWS 按聚合日期分区；
- 约一半 ODS 表带 PII 列：姓名/手机号/身份证/邮箱/地址/银行卡/诊断；
- DWD 对手机号/身份证/银行卡做 mask，金额派生 `amount_with_tax`；
- DWS 按 `status/region` 聚合 `count/sum/max`；
- 同时输出 `manifest.json`（数据集/字段/PII/行数/血缘边）与 `lineage.sql`（ODS→DWD→DWS 列级映射）。

用法：

```bash
python3 -m pip install duckdb
python3 scripts/seed-synthetic-warehouse.py \
  --tables 1000 --rows 8000 --partitions 3 --payload-bytes 5120 \
  --output data/synthetic-warehouse
```

实测：

- 30 表 / 20 行：0.00GB，0.6s；
- 1000 表 / 100 行：0.01GB，16.9s；
- 1000 表 / 8000 行 / payload 512：**0.78GB，84s**；
- 要达到约 5GB，建议把 `--payload-bytes` 提到 **5120**（payload 随机哈希块，Parquet 仍会压缩，生成后看 `manifest.parquetGB` 再微调）。

生成后的接入方式：

1. 新建 `LOCAL` 数据源，根目录 = `data/synthetic-warehouse`；
2. 新建 `PARQUET_DIRECTORY` 目录来源，rootPath = 同一目录；
3. 刷新后应看到约 1000 个 `PARQUET_DATASET`；
4. 可把 `manifest.json`/`lineage.sql` 作为后续血缘解析与敏感扫描的对照答案。

---

## 20. P11 · SuperSonic AI 问数 POC（2026-10-28）

### 决策

- **Phase 1 不部署 PI**，先用 SuperSonic 验证“语义层 + 自然语言问数 + 图表/看板/导出”。
- `earendil-works/pi` 是 MIT 的通用 Agent Harness（Node.js ≥ 22.19，带 Agent 循环/工具/MCP/SDK），不是 BI 产品，且没有内置权限系统；生产必须容器化/沙箱化。
- PI 只作为 Phase 3 可选编排层：PI → `dg-mcp` → `semantic.ask`（SuperSonic）/ Parquet / catalog / lineage / sensitivity / chart / export。
- POC 使用 SuperSonic 自带 UI，不做平台前端改造。

### 已落地脚手架

- `deploy/supersonic/docker-compose.yml`：SuperSonic standalone + PostgreSQL + ClickHouse；Parquet 目录挂入 ClickHouse 的 `user_files`。
- `deploy/supersonic/README.md`：DeepSeek、ClickHouse 数据源、语义建模、50 问评测、安全边界。
- `scripts/load-supersonic-clickhouse.py`：把合成湖 manifest 逐表装入 ClickHouse，用 `file()` 读 Parquet，并补 `dt` 分区列。
- `scripts/build-supersonic-duckdb.py`：DuckDB 实验路径；连接器元数据不完整，不用于 UI POC。
- `docs/plans/2026-10-28-supersonic-poc.md`：完整 POC 计划、验收线、Phase 2/3 路线。

### 验证

- 本机实际拉起 SuperSonic + PostgreSQL + ClickHouse；
- 30 张合成表装入 ClickHouse（24,558 行），SuperSonic `testConnect=true`；
- `getDbNames` 返回 `synthetic`，`getTables` 返回 30 张表；
- DuckDB 对照：`testConnect=true`，但 `getCatalogs`/`getTables` 元数据接口失败，确认不适合 UI POC；
- DeepSeek `deepseek-chat` 已配置并通过连通测试；
- 已建 **20 个语义模型 / 20 个数据集**（12 DWD + 6 DWS + 2 无 PII ODS），助手绑定 DeepSeek；
- 50 问评测：**49 PASS / 1 FAIL**，见 `docs/plans/2026-10-28-supersonic-50-eval.md`；
- 修复了中文 CTE 别名、指标被 `COUNT(*)` 替代、TopN 不带金额三个问题；
- 尚未验证图表/看板/导出、多模型 join、真实业务问题。

### 下一步

1. Phase 2：切 StarRocks/Doris External Catalog，接平台权限/查询网关/审计/脱敏；
2. 用真实业务问题扩充评测集，覆盖多轮、归因、同义词歧义；
3. 评估图表/看板/导出。

---

## 21. P11.2 · 问数网关（2026-10-28）

### 已完成

- 迁移 `V17__insight_gateway.sql`：
  - `ig_query_log` 问数事实表（问题、SQL、行数、耗时、脱敏列、成功/失败）；
  - 权限 `insight:ask` / `insight:read`，授予内置管理员/开发者/只读角色。
- `POST /api/v1/insight/ask`：
  - 平台 JWT + workspace；
  - 服务端调用 SuperSonic（单一服务账号），不把 SuperSonic 登录态暴露给前端；
  - 默认数据集/助手可配置；
  - 返回 SQL、列、行、摘要、是否截断、脱敏列。
- `GET /api/v1/insight/queries`：当前空间问数记录。
- `InsightMasking`：
  - 按 `md_column_sensitivity` 未拒绝标签做兜底脱敏；
  - 支持结果列名与 `expr AS alias` 的别名映射；
  - 单元测试覆盖直接列、中文别名、相似字段名不误伤。
- `InsightQueryService`：
  - 行数上限、耗时、失败记录；
  - 同时写 `ig_query_log` 与 `gv_audit_record`。
- 配置：`dg.insight.*` / `DG_SUPERSONIC_*`。
- `V18__insight_menu.sql` + 前端 `/insight/ask` 页面：
  - 数据集下拉来自 `GET /api/v1/insight/datasets`；
  - 展示结果表格、生成 SQL、脱敏列、最近问数；
  - 前端 `pnpm type-check` / `pnpm build` 通过；结果自动渲染 ECharts 折线/柱状图。
- `V19__insight_export.sql` + `POST /api/v1/insight/export`：
  - `insight:export` 单独授权(管理员/开发者,只读角色不给)；
  - 复用同一问数链路,CSV 结果同样脱敏；
  - 写 `INSIGHT_EXPORT` 审计；
- `POST /api/v1/insight/export/xlsx`：基于 Apache POI 生成 XLSX，同样脱敏/审计。
- `V20/V21` + `/insight/dashboards`：
  - 看板列表、创建、保存问题项、删除；
  - V21 修正 `menu:insight` 父菜单与问数叶子的层级；
  - 看板只保存问题定义,打开时重新问数,不落结果快照；
  - `POST /{id}/run` 逐项重新问数并返回结果,单题失败不拖垮看板；
  - 前端支持手动刷新和 60 秒自动刷新。

### 验证

- 平台应用启动并跑 Flyway V17；
- 登录后权限集包含 `insight:ask` / `insight:read`；
- `/api/v1/insight/ask` 对“按状态统计订单数”返回 SuperSonic 生成 SQL 和 3 行结果；
- 临时给 `status` 打 L2 敏感标签后，返回 `status=***`，`maskedColumns=["status","状态"]`；
- `/api/v1/insight/queries` 与审计接口均出现 `INSIGHT_QUERY` 记录；
- `mvn -pl backend/dg-app -am -Dtest=InsightMaskingTest test` 通过。

### 限制

- 当前是单一 SuperSonic 服务账号，workspace 隔离只在平台侧；尚未做用户级 SuperSonic 映射。
- 脱敏依赖列名/别名匹配；语义模型不暴露原始 PII 仍是第一道防线。
- 尚未切 StarRocks/Doris External Catalog，也尚未做前端 `/insight/ask` 页面。

---

## 22. P11.3 · StarRocks + MinIO 本地验证（2026-10-29）

### 已完成

- `deploy/starrocks/docker-compose.yml`：MinIO + StarRocks allin1 3.2.9。
- `scripts/upload-minio.sh`：把 `data/synthetic-warehouse` 上传到 MinIO `s3://lake/synthetic-warehouse`。
- `scripts/load-starrocks.py`：
  - 为 30 张表建 StarRocks 内表；
  - 用 `FILES()` 从 MinIO 读 Parquet；
  - `columns_from_path="dt"` 还原分区日期；
  - 实测 93 行 DWS 汇总表、31 个区域、2 天数据均正确。
- SuperSonic：
  - 数据源 `synthetic_starrocks`（id=4）连接成功；
  - 主题域 `销售分析-StarRocks`（id=5）；
  - 20 个模型/数据集；
  - 助手 id=6；
  - 50 问评测 **50/50 PASS**，见 `docs/plans/2026-10-29-supersonic-starrocks-50-eval.md`。
- 平台网关切到 StarRocks 助手/数据集，`/insight/ask`、CSV 导出验证通过。

### 限制

- 当前用 `FILES()` 装载到 StarRocks **内表**,不是 External Catalog；
- 尝试 file external table(不支持 `columns_from_path` 且目录/通配符路径失败)与 view over FILES()(凭据不持久化,报 `InvalidAccessKeyId`)均未通过；真正外部 Catalog 需要 HMS/Glue；
- MinIO 与 StarRocks 的 root 无密码只限本地 POC；
- 还没有用户级 SuperSonic 映射、限流、看板。
