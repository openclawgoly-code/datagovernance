-- ═══════════════════════════════════════════════════════════════════════
--  P1 权限与内置角色种子数据
--
--  这里<b>不</b>创建默认空间与管理员账号 —— 二者分别需要 AES-GCM 密文
--  与 BCrypt 散列,SQL 里造不出来,且把默认口令写进迁移脚本会永久留在
--  版本库中。改由 BootstrapRunner 在首次启动时按配置创建。
--
--  ── owner_space 列的用途 ────────────────────────────────────────────
--  它记录每个菜单项归属哪个架构 Space。这一列直接编码了本项目最关键的
--  一条推导结论: 功能菜单「基础配置」横跨三个 Space,不是一个模块。
--    28/29/30 空间、角色、用户  → PLATFORM
--    31/32   执行器、平台级JAR  → RUNTIME    (P3 引入)
--    33      告警渠道           → GOVERNANCE (P4 引入)
--  P1 只落地其中的 PLATFORM 部分。将来新增 31/32/33 时,它们会带着不同的
--  owner_space 值插入同一张表 —— 菜单仍在一起,归属却始终可查。
-- ═══════════════════════════════════════════════════════════════════════


-- ── 菜单 ────────────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:workbench',            '工作台',     'MENU', NULL,            '/workbench',              'Monitor',   10, 'UI'),

  ('menu:metadata',             '数据源管理', 'MENU', NULL,            '/metadata',               'Coin',      20, 'METADATA'),
  ('menu:metadata:datasource',  '数据源',     'MENU', 'menu:metadata', '/metadata/datasources',   'Connection',21, 'METADATA'),

  -- 「基础配置」在 UI 上是一个菜单,在架构上是三个 Space 的并置
  ('menu:settings',             '基础配置',   'MENU', NULL,            '/settings',               'Setting',   90, 'PLATFORM'),
  ('menu:settings:workspace',   '空间管理',   'MENU', 'menu:settings', '/settings/workspaces',    'Grid',      91, 'PLATFORM'),
  ('menu:settings:role',        '角色管理',   'MENU', 'menu:settings', '/settings/roles',         'Key',       92, 'PLATFORM'),
  ('menu:settings:user',        '用户管理',   'MENU', 'menu:settings', '/settings/users',         'User',      93, 'PLATFORM'),
  ('menu:settings:credential',  '凭据管理',   'MENU', 'menu:settings', '/settings/credentials',   'Lock',      94, 'PLATFORM');


-- ── 操作权限: Metadata Space ────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:datasource:read',    '查看数据源',   'ACTION', 'menu:metadata:datasource', 1, 'METADATA'),
  ('metadata:datasource:create',  '新建数据源',   'ACTION', 'menu:metadata:datasource', 2, 'METADATA'),
  ('metadata:datasource:update',  '编辑数据源',   'ACTION', 'menu:metadata:datasource', 3, 'METADATA'),
  ('metadata:datasource:delete',  '删除数据源',   'ACTION', 'menu:metadata:datasource', 4, 'METADATA'),
  ('metadata:datasource:test',    '测试连通性',   'ACTION', 'menu:metadata:datasource', 5, 'METADATA'),
  ('metadata:datasource:browse',  '浏览库表结构', 'ACTION', 'menu:metadata:datasource', 6, 'METADATA');


-- ── 操作权限: Platform / Tenancy Space ──────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('platform:workspace:read',     '查看空间',     'ACTION', 'menu:settings:workspace',  1, 'PLATFORM'),
  ('platform:workspace:create',   '新建空间',     'ACTION', 'menu:settings:workspace',  2, 'PLATFORM'),
  ('platform:workspace:update',   '编辑空间',     'ACTION', 'menu:settings:workspace',  3, 'PLATFORM'),
  ('platform:workspace:delete',   '删除空间',     'ACTION', 'menu:settings:workspace',  4, 'PLATFORM'),
  ('platform:workspace:member',   '管理授权用户', 'ACTION', 'menu:settings:workspace',  5, 'PLATFORM'),

  ('platform:role:read',          '查看角色',     'ACTION', 'menu:settings:role',       1, 'PLATFORM'),
  ('platform:role:create',        '新建角色',     'ACTION', 'menu:settings:role',       2, 'PLATFORM'),
  ('platform:role:update',        '编辑角色',     'ACTION', 'menu:settings:role',       3, 'PLATFORM'),
  ('platform:role:delete',        '删除角色',     'ACTION', 'menu:settings:role',       4, 'PLATFORM'),

  ('platform:user:read',          '查看用户',     'ACTION', 'menu:settings:user',       1, 'PLATFORM'),
  ('platform:user:create',        '新建用户',     'ACTION', 'menu:settings:user',       2, 'PLATFORM'),
  ('platform:user:update',        '编辑用户',     'ACTION', 'menu:settings:user',       3, 'PLATFORM'),
  ('platform:user:delete',        '删除用户',     'ACTION', 'menu:settings:user',       4, 'PLATFORM'),
  ('platform:user:assign-role',   '分配角色',     'ACTION', 'menu:settings:user',       5, 'PLATFORM'),

  ('platform:credential:read',    '查看凭据',     'ACTION', 'menu:settings:credential', 1, 'PLATFORM'),
  ('platform:credential:create',  '新建凭据',     'ACTION', 'menu:settings:credential', 2, 'PLATFORM'),
  ('platform:credential:update',  '编辑凭据',     'ACTION', 'menu:settings:credential', 3, 'PLATFORM'),
  ('platform:credential:delete',  '删除凭据',     'ACTION', 'menu:settings:credential', 4, 'PLATFORM');


-- ── 内置角色 ────────────────────────────────────────────────────────
-- workspace_id 为 NULL 表示平台级内置角色,对所有空间可见但不可编辑。
INSERT INTO pf_role (id, workspace_id, code, name, description, built_in) VALUES
  ('role_builtin_ws_admin',     NULL, 'WORKSPACE_ADMIN',
   '空间管理员', '在所授权空间内拥有全部操作权限,但不能新建或删除空间本身', TRUE),
  ('role_builtin_ws_developer', NULL, 'WORKSPACE_DEVELOPER',
   '空间开发者', '可管理数据源与凭据,不能管理用户与角色', TRUE),
  ('role_builtin_ws_viewer',    NULL, 'WORKSPACE_VIEWER',
   '空间只读', '仅可查看,不能做任何变更', TRUE);


-- ── 角色 → 权限 ─────────────────────────────────────────────────────

-- 空间管理员: 除「新建/删除空间」外的全部权限。
-- 空间的创建与销毁是平台级动作,留给 platform_admin —— 否则一个空间的
-- 管理员就能创建平级空间,租户隔离形同虚设。
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission
WHERE code NOT IN ('platform:workspace:create', 'platform:workspace:delete');

-- 空间开发者: 数据源全套 + 凭据全套 + 只读的空间/用户/角色可见性
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission
WHERE code LIKE 'metadata:%'
   OR code LIKE 'platform:credential:%'
   OR code IN ('platform:workspace:read', 'platform:user:read', 'platform:role:read')
   OR code IN ('menu:workbench', 'menu:metadata', 'menu:metadata:datasource',
               'menu:settings', 'menu:settings:credential');

-- 空间只读: 所有 :read 权限 + 可见菜单
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_viewer', code FROM pf_permission
WHERE code LIKE '%:read'
   OR code IN ('menu:workbench', 'menu:metadata', 'menu:metadata:datasource');
