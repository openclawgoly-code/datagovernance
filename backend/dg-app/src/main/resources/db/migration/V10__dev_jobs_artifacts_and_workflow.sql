-- P3:数据开发(序号 18-23)、执行器管理(31)、文件管理(32)
--
-- 这一版只加了一张新表(rt_artifact)。实时/离线开发与工作流的定义仍然住在
-- ctl_job_definition 里 —— 它们是三种 jobType,不是三种对象。给每种任务菜单
-- 各开一张表,会得到三套几乎相同的 CRUD、三套状态机,以及一个回答不了
-- 「这个空间一共有多少个任务」的数据模型。
--
-- 流任务的运行态是个例外:它<b>不是</b>执行记录的一种状态,而是一台独立的
-- 状态机(SPACE-MODEL.md E.3,架构风险 R5)。所以它有自己的几个列,
-- 而不是复用 rt_execution.status。


-- ── 作业制品仓库(序号 32「文件管理」)─────────────────────────────────
-- 菜单挂在「基础配置」下,归属却是 Runtime(R2)。存的是作业要执行的 JAR /
-- Python 包,生命周期与执行绑定 —— 被运行中的任务引用的包不能删。
CREATE TABLE rt_artifact (
  id                VARCHAR(40)  PRIMARY KEY,

  -- NULL 表示平台级制品(所有空间可用),非 NULL 表示某空间专属
  workspace_id      VARCHAR(40),

  name              VARCHAR(128) NOT NULL,
  version           VARCHAR(64)  NOT NULL,
  type              VARCHAR(16)  NOT NULL,
  description       VARCHAR(512),

  -- 相对路径。存绝对路径的话,换个部署目录就全失效了
  storage_path      VARCHAR(512) NOT NULL,
  size_bytes        BIGINT,
  checksum_sha256   VARCHAR(64),
  original_filename VARCHAR(255),

  -- 被多少个任务定义引用。与规则的引用计数(功能 17)同一个套路
  ref_count         INTEGER      NOT NULL DEFAULT 0,

  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by        VARCHAR(40),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by        VARCHAR(40),
  deleted           BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 同名同版本只能有一个:制品不可变,要改内容就发新版本。
-- 与 ctl_task_catalog 一样,workspace_id 可空,所以要拆成两个部分索引
CREATE UNIQUE INDEX uk_rt_artifact_ws_name_version
  ON rt_artifact (workspace_id, name, version)
  WHERE deleted = FALSE AND workspace_id IS NOT NULL;
CREATE UNIQUE INDEX uk_rt_artifact_platform_name_version
  ON rt_artifact (name, version)
  WHERE deleted = FALSE AND workspace_id IS NULL;

CREATE INDEX idx_rt_artifact_lookup ON rt_artifact (workspace_id, type)
  WHERE deleted = FALSE;


-- ── 流任务运行态(序号 18)──────────────────────────────────────────────
-- 挂在任务定义上而不是单开一张表:一个实时任务定义只有一个运行态,
-- 一对一的关系拆两张表只会让每次读都要 JOIN。
ALTER TABLE ctl_job_definition ADD COLUMN streaming_status VARCHAR(24);
-- 连续重启次数。超过阈值落 FAILED 并告警,而不是无限重启
ALTER TABLE ctl_job_definition ADD COLUMN streaming_restart_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ctl_job_definition ADD COLUMN streaming_started_at TIMESTAMPTZ;
ALTER TABLE ctl_job_definition ADD COLUMN streaming_stopped_at TIMESTAMPTZ;
-- 当前那次运行对应的执行记录。流任务的"一次运行"可能持续几个月
ALTER TABLE ctl_job_definition ADD COLUMN streaming_execution_id VARCHAR(40);
ALTER TABLE ctl_job_definition ADD COLUMN streaming_message VARCHAR(1024);

CREATE INDEX idx_ctl_job_streaming ON ctl_job_definition (workspace_id, streaming_status)
  WHERE deleted = FALSE AND streaming_status IS NOT NULL;


-- ── 工作流节点执行的索引(序号 23)──────────────────────────────────────
-- 取消父执行要级联取消所有 RUNNING 子执行,而"某个父执行的所有子执行"
-- 是这个查询的唯一形状。没有这个索引,级联取消会在大表上做全表扫描。
CREATE INDEX idx_rt_execution_parent ON rt_execution (parent_execution_id, status)
  WHERE parent_execution_id IS NOT NULL;

-- 工作流节点执行要能回答"这是哪个节点" —— 存在计划快照里不便于查询
ALTER TABLE rt_execution ADD COLUMN workflow_node_id VARCHAR(64);


-- ── 权限码 ─────────────────────────────────────────────────────────────
-- 数据开发是需求清单里的一级菜单(序号 18-23)。实时开发与离线开发共用
-- 任务管理页(按 jobType 过滤),所以这里不给它们各开一个路由 —— 菜单与
-- 模块是多对多(SPACE-MODEL.md I.2)。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:dev',           '数据开发', 'MENU', NULL,       '/dev',            'Cpu',       40, 'CONTROL'),
  ('menu:dev:streaming', '实时开发', 'MENU', 'menu:dev', '/dev/streaming',  'VideoPlay', 41, 'CONTROL'),
  ('menu:dev:batch',     '离线开发', 'MENU', 'menu:dev', '/dev/batch',      'Notebook',  42, 'CONTROL'),
  ('menu:dev:workflow',  '工作流编排', 'MENU', 'menu:dev', '/dev/workflows', 'Share',    43, 'CONTROL');

-- 序号 31/32 的菜单挂在「基础配置」下,归属却是 Runtime —— 这正是 R2 想说的:
-- 菜单位置不是归属。权限码用 runtime: 前缀,与菜单位置无关。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:settings:executor', '执行器管理', 'MENU', 'menu:settings', '/settings/executors', 'Monitor', 66, 'RUNTIME'),
  ('menu:settings:artifact', '文件管理',   'MENU', 'menu:settings', '/settings/artifacts', 'Files',   67, 'RUNTIME');

-- 执行器的两个 ACTION 在 V6 里就建好了,当时挂在「执行记录」下 —— 那时还没有
-- 执行器管理这个菜单。现在它有了自己的入口,把父节点改过去而不是再插一遍:
-- 权限码是身份,换个菜单位置不该换一个码,否则已经授过权的角色会突然失去它。
UPDATE pf_permission
SET parent_code = 'menu:settings:executor', sort_order = 1
WHERE code = 'runtime:executor:read';
UPDATE pf_permission
SET parent_code = 'menu:settings:executor', sort_order = 2
WHERE code = 'runtime:executor:manage';

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  -- 实时任务的启停是一个独立的权限:能编辑定义不等于能把生产上的流停掉
  ('control:job:stream',      '启停实时任务',  'ACTION', 'menu:dev:streaming', 1, 'CONTROL'),
  ('runtime:artifact:read',   '查看制品',      'ACTION', 'menu:settings:artifact', 1, 'RUNTIME'),
  ('runtime:artifact:manage', '上传/删除制品', 'ACTION', 'menu:settings:artifact', 2, 'RUNTIME');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:dev'),
  ('role_builtin_ws_admin',     'menu:dev:streaming'),
  ('role_builtin_ws_admin',     'menu:dev:batch'),
  ('role_builtin_ws_admin',     'menu:dev:workflow'),
  ('role_builtin_ws_admin',     'control:job:stream'),
  ('role_builtin_ws_admin',     'menu:settings:executor'),
  ('role_builtin_ws_admin',     'menu:settings:artifact'),
  ('role_builtin_ws_admin',     'runtime:artifact:read'),
  ('role_builtin_ws_admin',     'runtime:artifact:manage'),

  ('role_builtin_ws_developer', 'menu:dev'),
  ('role_builtin_ws_developer', 'menu:dev:streaming'),
  ('role_builtin_ws_developer', 'menu:dev:batch'),
  ('role_builtin_ws_developer', 'menu:dev:workflow'),
  ('role_builtin_ws_developer', 'control:job:stream'),
  ('role_builtin_ws_developer', 'menu:settings:artifact'),
  ('role_builtin_ws_developer', 'runtime:artifact:read'),
  ('role_builtin_ws_developer', 'runtime:artifact:manage'),
  -- 开发能看执行器但不能改:执行器是平台资源,改动影响所有人的任务。
  -- runtime:executor:read 在 V6 里已经授给开发者了,这里只补菜单入口
  ('role_builtin_ws_developer', 'menu:settings:executor'),

  ('role_builtin_ws_viewer',    'menu:dev'),
  ('role_builtin_ws_viewer',    'menu:dev:streaming'),
  ('role_builtin_ws_viewer',    'menu:dev:batch'),
  ('role_builtin_ws_viewer',    'menu:dev:workflow'),
  ('role_builtin_ws_viewer',    'menu:settings:artifact'),
  ('role_builtin_ws_viewer',    'runtime:artifact:read');
