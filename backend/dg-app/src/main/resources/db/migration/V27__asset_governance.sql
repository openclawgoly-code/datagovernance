-- P2 资产治理：业务域 + 资产治理结论。
--
-- 治理结论与采集事实分表存放：md_catalog_dataset 由采集任务 upsert，
-- 每次 refresh 都会覆盖 schema/行数/大小；owner/domain/认证属于人工结论，
-- 不能放在同一张表上，否则下一次刷新就会把它们静默冲掉。

CREATE TABLE md_business_domain (
    id             VARCHAR(40)  PRIMARY KEY,
    workspace_id   VARCHAR(40)  NOT NULL,
    code           VARCHAR(64)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    description    VARCHAR(1024),
    owner_username VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(40),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by     VARCHAR(40)
);

CREATE UNIQUE INDEX uk_md_business_domain_code
    ON md_business_domain (workspace_id, code);
CREATE INDEX idx_md_business_domain_workspace
    ON md_business_domain (workspace_id, name);


CREATE TABLE md_asset_governance (
    id                   VARCHAR(40)  PRIMARY KEY,
    workspace_id         VARCHAR(40)  NOT NULL,
    dataset_id           VARCHAR(40)  NOT NULL REFERENCES md_catalog_dataset(id),
    domain_id            VARCHAR(40)  REFERENCES md_business_domain(id),
    owner_username       VARCHAR(128),
    steward_username     VARCHAR(128),
    -- NONE / CERTIFIED / DEPRECATED
    certification_status VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    certified_by         VARCHAR(40),
    certified_at         TIMESTAMPTZ,
    certification_note   VARCHAR(1024),
    review_due_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(40),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           VARCHAR(40)
);

CREATE UNIQUE INDEX uk_md_asset_governance_dataset
    ON md_asset_governance (workspace_id, dataset_id);
CREATE INDEX idx_md_asset_governance_domain
    ON md_asset_governance (workspace_id, domain_id);
CREATE INDEX idx_md_asset_governance_owner
    ON md_asset_governance (workspace_id, owner_username);
CREATE INDEX idx_md_asset_governance_cert
    ON md_asset_governance (workspace_id, certification_status);


-- ── 权限种子 ─────────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:asset:govern', '维护资产治理', 'ACTION', 'menu:metadata:catalog', 3, 'METADATA');

-- 读取复用 metadata:asset:read；只有 admin/developer 能写治理结论。
INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'metadata:asset:govern'),
  ('role_builtin_ws_developer', 'metadata:asset:govern');
