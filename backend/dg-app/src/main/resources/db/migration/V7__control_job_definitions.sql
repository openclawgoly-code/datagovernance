-- ═══════════════════════════════════════════════════════════════════════
-- V7 · Control Space:任务定义、版本与调度触发日志
--
-- 序号 9、11-14、18、20、22 共用一张 ctl_job_definition。七种任务菜单项对应
-- 七个 job_type,但只有一张表:它们的公共部分(名称、状态、版本、调度、编译
-- 结果)占了定义的绝大多数;差异部分全部收进 config_json。
--
-- 按菜单拆七张表会得到七套几乎相同的 CRUD、七套状态机,以及一个无法回答
-- 「这个空间一共有多少个任务、其中多少在调度中」的数据模型。
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE ctl_job_definition (
  id                            VARCHAR(40)  PRIMARY KEY,
  workspace_id                  VARCHAR(40)  NOT NULL,
  name                          VARCHAR(128) NOT NULL,
  job_type                      VARCHAR(32)  NOT NULL,
  status                        VARCHAR(24)  NOT NULL,
  description                   VARCHAR(512),

  -- 类型特有的配置。结构由 job_type 决定,Control 不解释它 ——
  -- 解释它的是对应的 JobCompiler。这让新增一种任务类型不必改这张表。
  config_json                   TEXT,

  -- 每次修改 +1。Execution 绑定它启动时的版本;SCHEDULING 中的任务在下一次
  -- 触发时才用新版本。这两条合起来才是「可复现」。
  version                       INTEGER      NOT NULL DEFAULT 1,

  -- ── 最近一次编译 ────────────────────────────────────────────────────
  last_compiled_at              TIMESTAMPTZ,
  last_compile_succeeded        BOOLEAN,
  last_compile_message          VARCHAR(1024),
  -- 完整诊断列表,供 UI 把错误标在对应的字段/节点上。只存 message 的话,
  -- 一个二十字段的同步任务编译失败,用户只能逐个字段去猜是哪一个。
  last_compile_diagnostics_json TEXT,

  physical_plan_json            TEXT,
  -- 该计划由哪一版定义编译而来。与 version 不等 = 计划过期,拒绝执行
  plan_def_version              INTEGER,

  -- ── 调度(功能 16)──────────────────────────────────────────────────
  cron_expression               VARCHAR(128),
  -- IANA 时区名。跨时区团队里「每天凌晨两点」是谁的两点,必须说清楚
  cron_timezone                 VARCHAR(64),
  next_fire_at                  TIMESTAMPTZ,
  last_fire_at                  TIMESTAMPTZ,
  -- SKIP / QUEUE / CONCURRENT。默认 SKIP:一个每 5 分钟跑一次、单次要跑
  -- 20 分钟的同步任务,允许并发会在一小时内堆出十几个实例冲击目标库
  misfire_policy                VARCHAR(24)  NOT NULL DEFAULT 'SKIP',

  timeout_ms                    BIGINT,
  retry_max_attempts            INTEGER,
  retry_backoff_seconds         INTEGER,

  created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by                    VARCHAR(40),
  updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by                    VARCHAR(40),
  deleted                       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 同空间下任务名唯一。带 deleted 条件的部分索引:软删除后同名任务可以重建
CREATE UNIQUE INDEX uk_ctl_job_name
  ON ctl_job_definition (workspace_id, name)
  WHERE deleted = FALSE;

CREATE INDEX idx_ctl_job_ws_type
  ON ctl_job_definition (workspace_id, job_type, status)
  WHERE deleted = FALSE;

-- 调度器每分钟扫的那个查询。部分索引让它只扫 SCHEDULING 的那几十条,
-- 而不是全表 —— 任务定义会长到数千条,其中调度中的永远是少数。
CREATE INDEX idx_ctl_job_next_fire
  ON ctl_job_definition (next_fire_at)
  WHERE deleted = FALSE AND status = 'SCHEDULING' AND next_fire_at IS NOT NULL;


-- ── 定义版本快照 ───────────────────────────────────────────────────────
-- 与 md_datasource_version 同一套路:留完整快照而非 diff。回滚时不必逐版重放。
CREATE TABLE ctl_job_definition_version (
  id                 VARCHAR(40)  PRIMARY KEY,
  job_definition_id  VARCHAR(40)  NOT NULL,
  workspace_id       VARCHAR(40)  NOT NULL,
  version            INTEGER      NOT NULL,
  change_type        VARCHAR(32)  NOT NULL,
  change_summary     VARCHAR(512),
  snapshot_json      TEXT,
  changed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  changed_by         VARCHAR(40),

  CONSTRAINT uk_ctl_job_version UNIQUE (job_definition_id, version)
);

CREATE INDEX idx_ctl_job_version ON ctl_job_definition_version (job_definition_id, version DESC);


-- ── 调度触发日志 ───────────────────────────────────────────────────────
-- 单独一张表而不是只看 Execution:没能触发出 Execution 的那些才是关键 ——
-- 上一次还没跑完所以跳过、任务已暂停、计划过期。这些事件在 Execution 表里
-- 根本不存在,而它们恰恰是「为什么昨天的任务没跑」这个问题的答案。
CREATE TABLE ctl_schedule_fire (
  id                VARCHAR(40)  PRIMARY KEY,
  job_definition_id VARCHAR(40)  NOT NULL,
  workspace_id      VARCHAR(40)  NOT NULL,

  -- 计划触发时刻与实际触发时刻分开记:合成一个就没法回答「调度延迟了多久」
  scheduled_at      TIMESTAMPTZ,
  fired_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

  outcome           VARCHAR(16)  NOT NULL,     -- FIRED / SKIPPED / FAILED
  execution_id      VARCHAR(40),
  reason            VARCHAR(512)
);

CREATE INDEX idx_ctl_fire_job ON ctl_schedule_fire (job_definition_id, fired_at DESC);
CREATE INDEX idx_ctl_fire_ws  ON ctl_schedule_fire (workspace_id, fired_at DESC);


-- ── 权限码 ─────────────────────────────────────────────────────────────
-- 数据集成子系统的任务菜单。序号 9-16 的任务共用一套权限:它们是同一张表上
-- 的同一组操作,给每种任务类型各配一套权限码只会制造出「能建同步任务但不能
-- 建迁移任务」这种没人想要的组合。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:integration',      '数据集成', 'MENU', NULL,               '/integration',      'Switch',   30, 'CONTROL'),
  ('menu:integration:job',  '任务管理', 'MENU', 'menu:integration', '/integration/jobs', 'Operation', 31, 'CONTROL');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('control:job:read',     '查看任务',   'ACTION', 'menu:integration:job', 1, 'CONTROL'),
  ('control:job:create',   '新建任务',   'ACTION', 'menu:integration:job', 2, 'CONTROL'),
  ('control:job:update',   '编辑任务',   'ACTION', 'menu:integration:job', 3, 'CONTROL'),
  ('control:job:delete',   '删除任务',   'ACTION', 'menu:integration:job', 4, 'CONTROL'),
  ('control:job:compile',  '编译任务',   'ACTION', 'menu:integration:job', 5, 'CONTROL'),
  ('control:job:publish',  '发布任务',   'ACTION', 'menu:integration:job', 6, 'CONTROL'),
  ('control:job:trigger',  '手工执行',   'ACTION', 'menu:integration:job', 7, 'CONTROL'),
  ('control:job:schedule', '配置调度',   'ACTION', 'menu:integration:job', 8, 'CONTROL');

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission WHERE owner_space = 'CONTROL';

-- 开发者能建能跑,但不能删别人的任务
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission
WHERE owner_space = 'CONTROL' AND code <> 'control:job:delete';

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_viewer', code FROM pf_permission
WHERE code IN ('menu:integration', 'menu:integration:job', 'control:job:read');
