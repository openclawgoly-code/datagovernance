-- P2 敏感访问申请：申请（审批事实）与 grant（临时明文授权）分表。
--
-- grant 到期不删除，读取时按 expires_at 惰性判断；撤销写 revoked_at。
-- P2 授权粒度是 workspace 内的“敏感类别 + 最高级别”，不是资产/行级 ACL。

CREATE TABLE gv_access_request (
    id             VARCHAR(40)  PRIMARY KEY,
    workspace_id   VARCHAR(40)  NOT NULL,
    requester      VARCHAR(128) NOT NULL,
    -- PII / FINANCIAL / MEDICAL / CREDENTIAL
    category       VARCHAR(32)  NOT NULL,
    -- L1 / L2 / L3 / L4
    max_level      VARCHAR(8)   NOT NULL,
    reason         VARCHAR(512) NOT NULL,
    duration_hours INT          NOT NULL,
    -- PENDING / APPROVED / REJECTED / CANCELLED
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    approver       VARCHAR(128),
    decided_at     TIMESTAMPTZ,
    decision_note  VARCHAR(512),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gv_access_request_workspace
    ON gv_access_request (workspace_id, status, created_at DESC);
CREATE INDEX idx_gv_access_request_requester
    ON gv_access_request (workspace_id, requester, created_at DESC);


CREATE TABLE gv_access_grant (
    id           VARCHAR(40)  PRIMARY KEY,
    workspace_id VARCHAR(40)  NOT NULL,
    request_id   VARCHAR(40)  NOT NULL REFERENCES gv_access_request(id),
    username     VARCHAR(128) NOT NULL,
    category     VARCHAR(32)  NOT NULL,
    max_level    VARCHAR(8)   NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   VARCHAR(40),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gv_access_grant_active
    ON gv_access_grant (workspace_id, username, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_gv_access_grant_request
    ON gv_access_grant (request_id);


-- ── 权限与菜单 ───────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:governance:access', '敏感访问', 'MENU', NULL, '/governance/access-requests', 'Lock', 17, 'GOVERNANCE');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:access:request', '提交敏感访问申请', 'ACTION', 'menu:governance:access', 1, 'METADATA'),
  ('metadata:access:approve', '审批敏感访问申请', 'ACTION', 'menu:governance:access', 2, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:governance:access'),
  ('role_builtin_ws_admin',     'metadata:access:request'),
  ('role_builtin_ws_admin',     'metadata:access:approve'),

  ('role_builtin_ws_developer', 'menu:governance:access'),
  ('role_builtin_ws_developer', 'metadata:access:request'),
  ('role_builtin_ws_developer', 'metadata:access:approve'),

  ('role_builtin_ws_viewer',    'menu:governance:access'),
  ('role_builtin_ws_viewer',    'metadata:access:request');


-- 查询历史需要解释“这次为什么没脱敏”。只追加一列，
-- 不改变 ig_query_log 的事实语义：masked_columns 仍是实际脱敏列。
ALTER TABLE ig_query_log ADD COLUMN grant_ids VARCHAR(1000);

