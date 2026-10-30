-- P1 治理收件箱：只保存“移出/恢复”状态，不复制执行/告警/敏感标签事实。
CREATE TABLE gv_inbox_state (
    id            VARCHAR(40)  PRIMARY KEY,
    workspace_id  VARCHAR(40)  NOT NULL,
    source_type   VARCHAR(24)  NOT NULL, -- EXECUTION / ALERT / SENSITIVITY
    source_id     VARCHAR(64)  NOT NULL,
    state         VARCHAR(16)  NOT NULL DEFAULT 'OPEN', -- OPEN / DISMISSED
    note          VARCHAR(512),
    updated_by    VARCHAR(40),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_gv_inbox_state_source
    ON gv_inbox_state (workspace_id, source_type, source_id);
CREATE INDEX idx_gv_inbox_state_workspace
    ON gv_inbox_state (workspace_id, state);

-- 收件箱权限挂在工作台下；不新增菜单，入口由工作台负责展示。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:inbox:read',   '查看治理收件箱', 'ACTION', 'menu:workbench', 20, 'GOVERNANCE'),
  ('governance:inbox:manage', '管理治理收件箱', 'ACTION', 'menu:workbench', 21, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'governance:inbox:read'),
  ('role_builtin_ws_admin',     'governance:inbox:manage'),
  ('role_builtin_ws_developer', 'governance:inbox:read'),
  ('role_builtin_ws_developer', 'governance:inbox:manage'),
  ('role_builtin_ws_viewer',    'governance:inbox:read');
