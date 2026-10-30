-- P0 前端共享入口：血缘、敏感、查询历史。
-- 只新增菜单，不改变已有 ACTION 权限码。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:lineage', '字段血缘', 'MENU', 'menu:metadata', '/metadata/lineage', 'Share', 33, 'METADATA'),
  ('menu:metadata:sensitivity', '敏感数据', 'MENU', 'menu:metadata', '/metadata/sensitivity', 'Warning', 34, 'METADATA'),
  ('menu:insight:queries', '查询历史', 'MENU', 'menu:insight', '/insight/queries', 'Timer', 4, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:lineage'),
  ('role_builtin_ws_admin',     'menu:metadata:sensitivity'),
  ('role_builtin_ws_admin',     'menu:insight:queries'),

  ('role_builtin_ws_developer', 'menu:metadata:lineage'),
  ('role_builtin_ws_developer', 'menu:metadata:sensitivity'),
  ('role_builtin_ws_developer', 'menu:insight:queries'),

  ('role_builtin_ws_viewer',    'menu:metadata:lineage'),
  ('role_builtin_ws_viewer',    'menu:metadata:sensitivity'),
  ('role_builtin_ws_viewer',    'menu:insight:queries');
