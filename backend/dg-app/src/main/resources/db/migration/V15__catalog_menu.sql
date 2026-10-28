-- P10.2:数据目录菜单
--
-- V14 只种了 ACTION 权限,没有菜单。数据目录是独立页面(来源 → 数据集 → 字段/分区),
-- 不是“数据源”页里的一个抽屉,所以这里补一个 MENU,并把 ACTION 挂到它下面。
-- 菜单与路由是两件事:菜单决定侧边栏可见性,前端路由决定 URL 渲染哪个组件。

INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:catalog', '数据目录', 'MENU', 'menu:metadata', '/metadata/catalog', 'Collection', 22, 'METADATA');

UPDATE pf_permission SET parent_code = 'menu:metadata:catalog'
 WHERE code IN ('metadata:asset:read', 'metadata:asset:manage', 'metadata:asset:refresh');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:catalog'),
  ('role_builtin_ws_developer', 'menu:metadata:catalog'),
  ('role_builtin_ws_viewer',    'menu:metadata:catalog');
