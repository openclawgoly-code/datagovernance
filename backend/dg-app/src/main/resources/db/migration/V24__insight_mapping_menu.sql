-- P11.9:问数账号映射菜单
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:insight:mapping', '问数账号映射', 'MENU', 'menu:insight', '/insight/mappings', 'UserFilled', 3, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin', 'menu:insight:mapping');
