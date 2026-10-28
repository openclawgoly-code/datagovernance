-- P10.1:Catalog 规范化存储 + 厂商预设
--
-- 为什么不是继续往 md_catalog_snapshot 里塞 JSON:那张表按“浏览路径”缓存一页
-- 结构,适合点开树节点,不适合跨源搜索、表/字段统计、血缘和敏感标签关联。
-- 这里把 catalog 拆成 source / namespace / dataset / column / schema_version /
-- partition / snapshot_run,让 1000 张表可以被查询而不是只能被逐层展开。
--
-- 为什么 catalog 和血缘/敏感分迁移:它们由不同阶段落地,分开后回滚边界清楚。


-- ── 厂商预设 ─────────────────────────────────────────────────────────
-- 泛微/金蝶/用友不是数据库类型,而是“客户买了哪家 ERP”。底层仍然是标准
-- SQL Server / Oracle / MySQL / PostgreSQL / Db2 / 达梦 / 金仓。
ALTER TABLE md_datasource ADD COLUMN vendor_profile VARCHAR(48);


-- ── Catalog 来源 ─────────────────────────────────────────────────────
CREATE TABLE md_catalog_source (
  id                 VARCHAR(40)  PRIMARY KEY,
  workspace_id       VARCHAR(40)  NOT NULL,
  name               VARCHAR(128) NOT NULL,
  -- JDBC / PARQUET_DIRECTORY / HIVE_METASTORE / GLUE / ICEBERG_REST / ENGINE_CATALOG
  source_type        VARCHAR(32)  NOT NULL,
  -- JDBC 来源指向 md_datasource;HMS/Glue 等可为空
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


-- ── 命名空间:库 / 模式 / 目录 ───────────────────────────────────────
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

-- NULL 在唯一索引里不等于 NULL,根节点和子节点分开约束
CREATE UNIQUE INDEX uk_md_catalog_ns_root
  ON md_catalog_namespace (source_id, name)
  WHERE deleted = FALSE AND parent_id IS NULL;
CREATE UNIQUE INDEX uk_md_catalog_ns_child
  ON md_catalog_namespace (source_id, parent_id, name)
  WHERE deleted = FALSE AND parent_id IS NOT NULL;
CREATE INDEX idx_md_catalog_ns_parent
  ON md_catalog_namespace (source_id, parent_id) WHERE deleted = FALSE;


-- ── 数据集:表 / 视图 / Parquet 数据集 ───────────────────────────────
CREATE TABLE md_catalog_dataset (
  id                     VARCHAR(40)   PRIMARY KEY,
  workspace_id           VARCHAR(40)   NOT NULL,
  source_id              VARCHAR(40)   NOT NULL REFERENCES md_catalog_source(id),
  namespace_id           VARCHAR(40),
  name                   VARCHAR(256)  NOT NULL,
  qualified_name         VARCHAR(1024) NOT NULL,
  -- TABLE / VIEW / MATERIALIZED_VIEW / PARQUET_DATASET / STREAM / TOPIC
  kind                   VARCHAR(24)   NOT NULL,
  -- PARQUET / ORC / AVRO / ICEBERG / DELTA / HUDI / JDBC
  format                 VARCHAR(16),
  location               VARCHAR(2048),
  comment                VARCHAR(1024),
  schema_hash            CHAR(64),
  column_count           INT,
  row_count              BIGINT,
  size_bytes             BIGINT,
  last_modified_at       TIMESTAMPTZ,
  partition_columns_json TEXT,
  properties_json        TEXT,
  created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
  deleted                BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_catalog_dataset_qn
  ON md_catalog_dataset (source_id, qualified_name) WHERE deleted = FALSE;
CREATE INDEX idx_md_catalog_dataset_ns
  ON md_catalog_dataset (source_id, namespace_id) WHERE deleted = FALSE;
CREATE INDEX idx_md_catalog_dataset_name
  ON md_catalog_dataset (workspace_id, name) WHERE deleted = FALSE;


-- ── 字段 ─────────────────────────────────────────────────────────────
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


-- ── schema 版本:列变更的可复盘证据 ──────────────────────────────────
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


-- ── 分区:Parquet 场景用;JDBC 表首期不写 ────────────────────────────
CREATE TABLE md_catalog_partition (
  id                    VARCHAR(40)   PRIMARY KEY,
  dataset_id            VARCHAR(40)   NOT NULL REFERENCES md_catalog_dataset(id),
  partition_path        VARCHAR(2048) NOT NULL,
  partition_values_json TEXT,
  file_count            BIGINT,
  size_bytes            BIGINT,
  row_count             BIGINT,
  last_modified_at      TIMESTAMPTZ,
  created_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_md_catalog_partition
  ON md_catalog_partition (dataset_id, partition_path);
CREATE INDEX idx_md_catalog_partition_ds
  ON md_catalog_partition (dataset_id, last_modified_at DESC);


-- ── 采集运行记录 ─────────────────────────────────────────────────────
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


-- ── 权限种子 ─────────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:asset:read',    '查看数据资产目录', 'ACTION', 'menu:metadata', 30, 'METADATA'),
  ('metadata:asset:manage',  '管理数据资产目录', 'ACTION', 'menu:metadata', 31, 'METADATA'),
  ('metadata:asset:refresh', '刷新数据资产目录', 'ACTION', 'menu:metadata', 32, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'metadata:asset:read'),
  ('role_builtin_ws_admin',     'metadata:asset:manage'),
  ('role_builtin_ws_admin',     'metadata:asset:refresh'),

  ('role_builtin_ws_developer', 'metadata:asset:read'),
  ('role_builtin_ws_developer', 'metadata:asset:manage'),
  ('role_builtin_ws_developer', 'metadata:asset:refresh'),

  ('role_builtin_ws_viewer',    'metadata:asset:read');
