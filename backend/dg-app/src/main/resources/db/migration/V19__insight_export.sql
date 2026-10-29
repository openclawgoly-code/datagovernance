-- P11.4:问数导出单独授权。
-- 默认只给空间管理员/开发者;只读角色可以问数,但不能把结果带走。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('insight:export', '导出问数结果', 'ACTION', 'menu:insight', 3, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'insight:export'),
  ('role_builtin_ws_developer', 'insight:export');
