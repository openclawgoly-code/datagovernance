-- P2 业务术语：workspace 级术语表 + 资产/字段绑定。
--
-- 术语是人工维护的业务词汇，不是采集事实；字段重命名/重新采集不会覆盖它。
-- 绑定单独成表，便于一个术语绑定多个资产、也便于失效绑定单独清理。

CREATE TABLE md_glossary_term (
    id             VARCHAR(40)  PRIMARY KEY,
    workspace_id   VARCHAR(40)  NOT NULL,
    term           VARCHAR(128) NOT NULL,
    definition     VARCHAR(2048) NOT NULL,
    synonyms_json  TEXT,
    domain_id      VARCHAR(40)  REFERENCES md_business_domain(id),
    -- DRAFT / PUBLISHED / DEPRECATED
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    owner_username VARCHAR(128),
    published_by   VARCHAR(40),
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(40),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by     VARCHAR(40)
);

-- 大小写不敏感唯一：GMV 与 gmv 必须是同一个词，不能有两份定义。
CREATE UNIQUE INDEX uk_md_glossary_term
    ON md_glossary_term (workspace_id, lower(term));
CREATE INDEX idx_md_glossary_term_domain
    ON md_glossary_term (workspace_id, domain_id);
CREATE INDEX idx_md_glossary_term_status
    ON md_glossary_term (workspace_id, status);


CREATE TABLE md_glossary_binding (
    id           VARCHAR(40)  PRIMARY KEY,
    workspace_id VARCHAR(40)  NOT NULL,
    term_id      VARCHAR(40)  NOT NULL REFERENCES md_glossary_term(id),
    dataset_id   VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
    column_id    VARCHAR(40)  REFERENCES md_catalog_column(id),
    note         VARCHAR(512),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(40)
);

-- 数据集级绑定与字段级绑定分别唯一；NULL 在唯一索引里互不相等，
-- 因此必须拆成两个 partial index。
CREATE UNIQUE INDEX uk_md_glossary_binding_dataset
    ON md_glossary_binding (term_id, dataset_id) WHERE column_id IS NULL;
CREATE UNIQUE INDEX uk_md_glossary_binding_column
    ON md_glossary_binding (term_id, column_id) WHERE column_id IS NOT NULL;
CREATE INDEX idx_md_glossary_binding_dataset
    ON md_glossary_binding (workspace_id, dataset_id);


-- ── 权限与菜单 ───────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:glossary', '业务术语', 'MENU', 'menu:metadata', '/metadata/glossary', 'Notebook', 23, 'METADATA');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:glossary:read',   '查看业务术语', 'ACTION', 'menu:metadata:glossary', 1, 'METADATA'),
  ('metadata:glossary:manage', '维护业务术语', 'ACTION', 'menu:metadata:glossary', 2, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:glossary'),
  ('role_builtin_ws_admin',     'metadata:glossary:read'),
  ('role_builtin_ws_admin',     'metadata:glossary:manage'),

  ('role_builtin_ws_developer', 'menu:metadata:glossary'),
  ('role_builtin_ws_developer', 'metadata:glossary:read'),
  ('role_builtin_ws_developer', 'metadata:glossary:manage'),

  ('role_builtin_ws_viewer',    'menu:metadata:glossary'),
  ('role_builtin_ws_viewer',    'metadata:glossary:read');
