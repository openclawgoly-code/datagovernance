-- P2 资产质量：规则（配置）与运行（不可变事实）。
--
-- 规则不保存连接信息：运行时从 catalog → source → datasource 解析，
-- 避免质量表成为第二份数据源配置。运行记录不做级联删除，
-- 删规则不等于删历史。

CREATE TABLE md_quality_rule (
    id           VARCHAR(40)  PRIMARY KEY,
    workspace_id VARCHAR(40)  NOT NULL,
    dataset_id   VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
    column_id    VARCHAR(40)  REFERENCES md_catalog_column(id),
    -- ROW_COUNT_RANGE / NOT_NULL / UNIQUE / FRESHNESS / SCHEMA_DRIFT
    rule_type    VARCHAR(32)  NOT NULL,
    config_json  TEXT,
    -- CRITICAL / HIGH / MEDIUM / LOW
    severity     VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(40),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(40)
);

CREATE INDEX idx_md_quality_rule_dataset
    ON md_quality_rule (workspace_id, dataset_id);
CREATE INDEX idx_md_quality_rule_type
    ON md_quality_rule (workspace_id, rule_type);


CREATE TABLE md_quality_run (
    id            VARCHAR(40)  PRIMARY KEY,
    workspace_id  VARCHAR(40)  NOT NULL,
    -- 不对 rule_id 建外键：删规则后历史运行仍需保留，作为“曾经跑过什么”的证据
    rule_id       VARCHAR(40)  NOT NULL,
    dataset_id    VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
    -- RUNNING / SUCCEEDED / FAILED
    status        VARCHAR(16)  NOT NULL,
    passed        BOOLEAN,
    metric_json   TEXT,
    violations    BIGINT,
    error_message VARCHAR(2048),
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    duration_ms   BIGINT,
    executed_by   VARCHAR(40)
);

CREATE INDEX idx_md_quality_run_dataset
    ON md_quality_run (workspace_id, dataset_id, started_at DESC);
CREATE INDEX idx_md_quality_run_rule
    ON md_quality_run (rule_id, started_at DESC);
CREATE INDEX idx_md_quality_run_status
    ON md_quality_run (workspace_id, status, started_at DESC);


-- ── 权限与菜单 ───────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:quality', '资产质量', 'MENU', 'menu:metadata', '/metadata/quality', 'DataAnalysis', 24, 'METADATA');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:quality:read',   '查看资产质量', 'ACTION', 'menu:metadata:quality', 1, 'METADATA'),
  ('metadata:quality:manage', '管理资产质量', 'ACTION', 'menu:metadata:quality', 2, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:quality'),
  ('role_builtin_ws_admin',     'metadata:quality:read'),
  ('role_builtin_ws_admin',     'metadata:quality:manage'),

  ('role_builtin_ws_developer', 'menu:metadata:quality'),
  ('role_builtin_ws_developer', 'metadata:quality:read'),
  ('role_builtin_ws_developer', 'metadata:quality:manage'),

  ('role_builtin_ws_viewer',    'menu:metadata:quality'),
  ('role_builtin_ws_viewer',    'metadata:quality:read');
