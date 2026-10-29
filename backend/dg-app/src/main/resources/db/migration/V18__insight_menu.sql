-- P11.3:智能问数入口菜单(V17 只种了权限码,没有菜单,前端会没有入口)
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:insight', '智能问数', 'MENU', NULL, '/insight/ask', 'ChatDotRound', 15, 'GOVERNANCE');

-- 把 V17 的 ACTION 挂到新菜单下。只改 parent_code,权限码保持不变,
-- 已经下发的角色授权不需要迁移。
UPDATE pf_permission SET parent_code = 'menu:insight'
WHERE code IN ('insight:ask', 'insight:read');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:insight'),
  ('role_builtin_ws_developer', 'menu:insight'),
  ('role_builtin_ws_viewer',    'menu:insight');
