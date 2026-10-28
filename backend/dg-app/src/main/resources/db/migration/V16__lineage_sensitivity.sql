-- P10.4:字段级血缘 + 敏感标签(PG 原生)
--
-- 为什么不用 DataHub/Atlas:它们要 Elasticsearch/Neo4j/HBase;1000 张表、
-- 5GB 数据的规模下,这些组件的运维成本远大于收益。PG 的邻接表 + 递归查询
-- 足够回答“这个字段从哪来、改它会影响谁”。
--
-- 为什么血缘边不删除:SQL 会变,但“上周的血缘为什么是这样”必须可复盘。
-- 旧边标 SUPERSEDED,新边插入。

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
CREATE INDEX idx_md_lineage_node_dataset
  ON md_lineage_node (catalog_dataset_id);
CREATE INDEX idx_md_lineage_node_column
  ON md_lineage_node (catalog_column_id);

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

CREATE UNIQUE INDEX uk_md_lineage_job
  ON md_lineage_job (workspace_id, job_type, name);

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


-- ── 敏感规则 ─────────────────────────────────────────────────────────
CREATE TABLE md_sensitivity_rule (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40),           -- NULL = 平台内置
  name          VARCHAR(128) NOT NULL,
  category      VARCHAR(32)  NOT NULL, -- PII / FINANCIAL / MEDICAL / CREDENTIAL
  level         VARCHAR(8)   NOT NULL, -- L1 / L2 / L3 / L4
  detector_type VARCHAR(24)  NOT NULL, -- COLUMN_NAME / REGEX / LUHN / DICTIONARY
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
  evidence_json TEXT,         -- 只存脱敏后的证据/命中统计，绝不存原始 PII
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
  sample_masked  VARCHAR(512),
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_md_scan_finding_run
  ON md_scan_finding (run_id);
CREATE INDEX idx_md_scan_finding_column
  ON md_scan_finding (dataset_id, column_id);


-- ── 内置规则(按字段名/注释匹配;内容扫描后续加) ─────────────────────
INSERT INTO md_sensitivity_rule
  (id, workspace_id, name, category, level, detector_type, pattern, priority, enabled, created_by)
VALUES
  ('rule_builtin_id_card', NULL, '身份证号', 'PII', 'L4', 'COLUMN_NAME',
   '(身份证|证件号|identity.?card|id.?card)', 10, TRUE, 'system'),
  ('rule_builtin_phone', NULL, '手机号', 'PII', 'L3', 'COLUMN_NAME',
   '(手机号|手机|mobile|phone|telephone)', 20, TRUE, 'system'),
  ('rule_builtin_bank_card', NULL, '银行卡号', 'FINANCIAL', 'L4', 'COLUMN_NAME',
   '(银行卡|卡号|bank.?card|account.?no)', 30, TRUE, 'system'),
  ('rule_builtin_email', NULL, '邮箱', 'PII', 'L2', 'COLUMN_NAME',
   '(邮箱|email|e-mail|mail)', 40, TRUE, 'system'),
  ('rule_builtin_name', NULL, '真实姓名', 'PII', 'L2', 'COLUMN_NAME',
   '(真实姓名|姓名|real.?name|customer.?name)', 50, TRUE, 'system'),
  ('rule_builtin_address', NULL, '地址', 'PII', 'L2', 'COLUMN_NAME',
   '(地址|住址|address|addr)', 60, TRUE, 'system'),
  ('rule_builtin_medical', NULL, '医疗信息', 'MEDICAL', 'L3', 'COLUMN_NAME',
   '(病历|诊断|处方|检验|medical|diagnosis|prescription)', 70, TRUE, 'system'),
  ('rule_builtin_secret', NULL, '密码/密钥', 'CREDENTIAL', 'L4', 'COLUMN_NAME',
   '(密码|口令|密钥|secret|token|password|passwd)', 80, TRUE, 'system');


-- ── 权限种子 ─────────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:lineage:read',      '查看字段血缘', 'ACTION', 'menu:metadata', 35, 'METADATA'),
  ('metadata:lineage:manage',    '维护字段血缘', 'ACTION', 'menu:metadata', 36, 'METADATA'),
  ('metadata:sensitivity:read',  '查看敏感标签', 'ACTION', 'menu:metadata', 37, 'METADATA'),
  ('metadata:sensitivity:scan',  '发起敏感扫描', 'ACTION', 'menu:metadata', 38, 'METADATA'),
  ('metadata:sensitivity:review','复核敏感标签', 'ACTION', 'menu:metadata', 39, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',      'metadata:lineage:read'),
  ('role_builtin_ws_admin',      'metadata:lineage:manage'),
  ('role_builtin_ws_admin',      'metadata:sensitivity:read'),
  ('role_builtin_ws_admin',      'metadata:sensitivity:scan'),
  ('role_builtin_ws_admin',      'metadata:sensitivity:review'),

  ('role_builtin_ws_developer',  'metadata:lineage:read'),
  ('role_builtin_ws_developer',  'metadata:lineage:manage'),
  ('role_builtin_ws_developer',  'metadata:sensitivity:read'),
  ('role_builtin_ws_developer',  'metadata:sensitivity:scan'),
  ('role_builtin_ws_developer',  'metadata:sensitivity:review'),

  ('role_builtin_ws_viewer',     'metadata:lineage:read'),
  ('role_builtin_ws_viewer',     'metadata:sensitivity:read');
