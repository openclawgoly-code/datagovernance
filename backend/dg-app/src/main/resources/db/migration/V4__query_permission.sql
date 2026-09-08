-- 功能7「支持自定义查询类的 SQL 语句」的权限码。
--
-- 与 metadata:datasource:browse 分开,是因为两者的风险等级不同:
-- 浏览结构只读元数据,而自定义查询会对业务库执行用户手写的语句。
-- 合成一个权限码等于让所有能看表结构的人都能跑任意 SELECT。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:datasource:query', '执行自定义查询', 'ACTION', 'menu:metadata:datasource', 9, 'DATA');

-- 管理员与开发者可查询;只读角色<b>不</b>给 —— 它的定位是"仅可查看",
-- 而自定义查询能读到表结构里看不到的业务数据。
INSERT INTO pf_role_permission (role_id, permission_code)
VALUES ('role_builtin_ws_admin', 'metadata:datasource:query'),
       ('role_builtin_ws_developer', 'metadata:datasource:query');
