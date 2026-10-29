-- P11.6:修正智能问数菜单层级。
-- V18 把 menu:insight 直接当作 /insight/ask 的叶子;V20 给它加了看板子菜单后,
-- 前端 MenuTree 会把 menu:insight 渲染成 sub-menu,父级 routePath 不再可点击,
-- 结果“智能问数”页面从侧边栏消失。这里补一个真正的问数叶子菜单。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:insight:ask', '智能问数', 'MENU', 'menu:insight', '/insight/ask', 'ChatDotRound', 1, 'GOVERNANCE');

UPDATE pf_permission SET route_path = NULL WHERE code = 'menu:insight';
UPDATE pf_permission SET parent_code = 'menu:insight:ask'
WHERE code IN ('insight:ask', 'insight:read', 'insight:export');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:insight:ask'),
  ('role_builtin_ws_developer', 'menu:insight:ask'),
  ('role_builtin_ws_viewer',    'menu:insight:ask');
