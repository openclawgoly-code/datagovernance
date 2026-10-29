-- P11.5:问数看板(最小可用)
-- 看板只存“问题 + 数据集 + 图表类型”,结果在打开看板时重新问数;
-- 不落结果快照,避免数据变化后看板展示过期数字却没有提示。
CREATE TABLE ig_dashboard (
  id            VARCHAR(40)  PRIMARY KEY,
  workspace_id  VARCHAR(40)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(512),
  created_by    VARCHAR(40),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_ig_dashboard_workspace
  ON ig_dashboard (workspace_id, updated_at DESC) WHERE deleted = FALSE;

CREATE TABLE ig_dashboard_item (
  id            VARCHAR(40)  PRIMARY KEY,
  dashboard_id  VARCHAR(40)  NOT NULL REFERENCES ig_dashboard(id),
  question      VARCHAR(2000) NOT NULL,
  dataset_id    BIGINT,
  chart_type    VARCHAR(24)  NOT NULL DEFAULT 'AUTO',
  sort_order    INTEGER      NOT NULL DEFAULT 0,
  created_by    VARCHAR(40),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ig_dashboard_item
  ON ig_dashboard_item (dashboard_id, sort_order, created_at);

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('insight:dashboard:read',   '查看看板', 'ACTION', 'menu:insight', 4, 'GOVERNANCE'),
  ('insight:dashboard:manage', '管理看板', 'ACTION', 'menu:insight', 5, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'insight:dashboard:read'),
  ('role_builtin_ws_admin',     'insight:dashboard:manage'),
  ('role_builtin_ws_developer', 'insight:dashboard:read'),
  ('role_builtin_ws_developer', 'insight:dashboard:manage'),
  ('role_builtin_ws_viewer',    'insight:dashboard:read');

INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:insight:dashboard', '问数看板', 'MENU', 'menu:insight', '/insight/dashboards', 'DataBoard', 2, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:insight:dashboard'),
  ('role_builtin_ws_developer', 'menu:insight:dashboard'),
  ('role_builtin_ws_viewer',    'menu:insight:dashboard');
