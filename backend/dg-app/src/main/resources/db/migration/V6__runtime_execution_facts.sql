-- ═══════════════════════════════════════════════════════════════════════
-- V6 · Runtime Space:平台级统一执行事实
--
-- 架构约束 R4 的落点。需求清单里有五处「执行记录」页面(序号 10 整库迁移、
-- 15 离线同步、19 实时任务、21 批处理、23 工作流),按菜单直译会得到五张
-- 结构几乎相同的表。那样做的代价不是冗余,而是序号 24 的任务监控从此没有
-- 单一事实源:「今天跑了多少任务、失败率多少」要 UNION 五张表,而这五张表
-- 的字段迟早各自演化。
--
-- 所以只有 rt_execution 一张,用 job_ref_type 区分种类。五个页面是同一个查询
-- 加不同过滤,监控是不加过滤的那一个。
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE rt_execution (
  id                  VARCHAR(40)  PRIMARY KEY,
  workspace_id        VARCHAR(40)  NOT NULL,

  job_ref_type        VARCHAR(32)  NOT NULL,
  job_ref_id          VARCHAR(40),
  job_name            VARCHAR(256),
  -- 启动时的定义版本。钉死它才谈得上「可复现」:一个月后回看这条失败记录,
  -- 能确定当时跑的是哪一版定义,而不是今天这一版。
  def_version         INTEGER,

  status              VARCHAR(24)  NOT NULL,

  -- 工作流节点的父执行(序号 22/23);顶层执行为 NULL
  parent_execution_id VARCHAR(40),

  trigger_type        VARCHAR(24)  NOT NULL DEFAULT 'MANUAL',
  triggered_by        VARCHAR(40),

  plan_json           TEXT,
  retry_policy_json   TEXT,
  -- 逐条存超时:整库迁移跑几小时是正常的,连通性检查超过十秒就该判死。
  -- 用同一个全局阈值套两者,要么放过僵死的探测,要么半路杀掉正常的迁移。
  timeout_ms          BIGINT,

  attempt_count       INTEGER      NOT NULL DEFAULT 0,

  submitted_at        TIMESTAMPTZ  NOT NULL,
  started_at          TIMESTAMPTZ,
  finished_at         TIMESTAMPTZ,
  duration_ms         BIGINT,

  message             VARCHAR(1024),
  error_code          VARCHAR(64),

  -- 指标冗余在主表:序号 24 的监控是全表聚合,联表会把一次 GROUP BY
  -- 变成大表 JOIN
  rows_read           BIGINT,
  rows_written        BIGINT,
  bytes_processed     BIGINT,

  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 五个执行记录页面的主查询:空间 + 种类 + 时间倒序
CREATE INDEX idx_rt_exec_ws_type_time
  ON rt_execution (workspace_id, job_ref_type, submitted_at DESC);

-- 序号 24 的监控聚合:空间 + 状态 + 时间窗
CREATE INDEX idx_rt_exec_ws_status_time
  ON rt_execution (workspace_id, status, submitted_at DESC);

-- 「这个定义最近跑得怎么样」——从定义详情页跳过来的那个查询
CREATE INDEX idx_rt_exec_job_ref
  ON rt_execution (job_ref_id, submitted_at DESC)
  WHERE job_ref_id IS NOT NULL;

-- 工作流详情页展开子节点
CREATE INDEX idx_rt_exec_parent
  ON rt_execution (parent_execution_id)
  WHERE parent_execution_id IS NOT NULL;

-- 超时巡检扫的是"还没结束的"。部分索引让它不必扫过历史记录 ——
-- 历史记录会长到千万级,而未结束的永远只有几十条。
CREATE INDEX idx_rt_exec_unfinished
  ON rt_execution (submitted_at)
  WHERE status IN ('PENDING', 'DISPATCHED', 'RUNNING', 'CANCELING');


-- ── 执行尝试 ───────────────────────────────────────────────────────────
-- 重试新增一行 Attempt,不新建 Execution。若重试新建 Execution,序号 24 的
-- 「执行总数」会被重试污染 —— 一个重试三次才成功的任务会被记成四个任务。
CREATE TABLE rt_execution_attempt (
  id              VARCHAR(40)  PRIMARY KEY,
  execution_id    VARCHAR(40)  NOT NULL,
  workspace_id    VARCHAR(40)  NOT NULL,

  attempt_no      INTEGER      NOT NULL,
  status          VARCHAR(24)  NOT NULL,

  executor_id     VARCHAR(64),
  -- 引擎侧作业 ID(Flink JobID / K8s Job 名)—— 排障时的唯一入口
  engine_job_id   VARCHAR(128),

  started_at      TIMESTAMPTZ,
  finished_at     TIMESTAMPTZ,
  duration_ms     BIGINT,

  message         VARCHAR(1024),
  error_code      VARCHAR(64),
  -- 引擎原始报错。单独存并截断:它可能很长,不该混进 message
  error_detail    TEXT,

  rows_read       BIGINT,
  rows_written    BIGINT,
  bytes_processed BIGINT,

  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

  CONSTRAINT uk_rt_attempt_no UNIQUE (execution_id, attempt_no)
);

CREATE INDEX idx_rt_attempt_execution ON rt_execution_attempt (execution_id, attempt_no);


-- ── 执行日志 ───────────────────────────────────────────────────────────
-- Contract 里写的是 OpenSearch,P2 先落 PostgreSQL。刻意不抽象出「日志存储」
-- 接口:只有一个实现时,那层抽象只会让人猜错它的形状。
CREATE TABLE rt_execution_log (
  id           VARCHAR(40)  PRIMARY KEY,
  execution_id VARCHAR(40)  NOT NULL,
  attempt_id   VARCHAR(40),
  workspace_id VARCHAR(40)  NOT NULL,

  -- 行序号保证同一毫秒内的日志顺序稳定 —— 只按时间排会乱序
  line_no      BIGINT       NOT NULL,
  level        VARCHAR(16)  NOT NULL DEFAULT 'INFO',
  content      TEXT         NOT NULL,
  logged_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rt_log_execution ON rt_execution_log (execution_id, line_no);


-- ── 执行器(序号 31)────────────────────────────────────────────────────
-- P2 只有一个内置本地执行器。表先建起来,是为了让 ExecutorAssignment 的分配
-- 逻辑有落点,而不是等接入 Flink 时再回头改执行事实表的结构。
CREATE TABLE rt_executor (
  id                VARCHAR(40)  PRIMARY KEY,
  name              VARCHAR(128) NOT NULL,
  kind              VARCHAR(32)  NOT NULL,
  status            VARCHAR(24)  NOT NULL,

  -- NULL 表示平台共享执行器,非 NULL 表示专属某个空间
  workspace_id      VARCHAR(40),

  endpoint          VARCHAR(512),
  max_concurrency   INTEGER      NOT NULL DEFAULT 4,
  running_count     INTEGER      NOT NULL DEFAULT 0,

  last_heartbeat_at TIMESTAMPTZ,
  labels_json       TEXT,

  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

  CONSTRAINT uk_rt_executor_name UNIQUE (name)
);

CREATE INDEX idx_rt_executor_status ON rt_executor (status, kind);


-- ── 权限码(序号 10/15/19/21/23 共用一套)─────────────────────────────
-- 五个执行记录页面查的是同一张表,权限自然也是同一套。给每个页面各配一个
-- 权限码,只会让"某人能看迁移记录但看不到同步记录"这种没人想要的组合成为可能。
-- 运维监控是需求清单里的一级菜单(序号 24-33 都挂在它下面),这里先立出来;
-- 监控/告警/审计在 P4 补进同一个父节点。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:ops',           '运维监控', 'MENU', NULL,       '/ops',            'DataLine', 50, 'RUNTIME'),
  ('menu:ops:execution', '执行记录', 'MENU', 'menu:ops', '/ops/executions', 'Tickets',  51, 'RUNTIME');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('runtime:execution:read',    '查看执行记录', 'ACTION', 'menu:ops:execution',  1, 'RUNTIME'),
  ('runtime:execution:cancel',  '取消执行',   'ACTION', 'menu:ops:execution',  2, 'RUNTIME'),
  ('runtime:execution:retry',   '重试执行',   'ACTION', 'menu:ops:execution',  3, 'RUNTIME'),
  ('runtime:executor:read',     '查看执行器', 'ACTION', 'menu:ops:execution',  4, 'RUNTIME'),
  ('runtime:executor:manage',   '管理执行器', 'ACTION', 'menu:ops:execution',  5, 'RUNTIME');

-- 空间管理员拿全套;开发者能看能取消自己的任务但不碰执行器配置
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission
WHERE code IN ('menu:ops:execution', 'runtime:execution:read', 'runtime:execution:cancel',
               'runtime:execution:retry', 'runtime:executor:read', 'runtime:executor:manage');

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission
WHERE code IN ('menu:ops:execution', 'runtime:execution:read', 'runtime:execution:cancel',
               'runtime:execution:retry', 'runtime:executor:read');

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_viewer', code FROM pf_permission
WHERE code IN ('menu:ops:execution', 'runtime:execution:read');
