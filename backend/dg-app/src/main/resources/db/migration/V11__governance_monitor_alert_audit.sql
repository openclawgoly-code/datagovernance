-- P4:任务监控(24)、告警规则(25)、告警信息(26)、审计日志(27)、告警渠道(33)
--
-- 序号 24/25/27 都明确要求「跨数据集成与数据开发聚合」——这是统一 Execution
-- 模型(架构约束 R4)的三条独立证据。所以这一版<b>没有</b>新建任何执行相关的
-- 表:监控直接聚合 rt_execution,告警的触发源也是它。
--
-- 若当初按菜单把执行记录拆成五张表,这三个功能现在要做的第一件事就是 UNION
-- 五张表 —— 而那个 UNION 会在每加一种任务类型时被人忘记更新。


-- ── 告警渠道(序号 33)──────────────────────────────────────────────
-- 归 Governance 而非配置模块(R2):渠道有连通性状态,一个发不出去的渠道
-- 会让挂在它上面的告警静默失效 —— 那是治理问题,不是配置项。
CREATE TABLE gv_alert_channel (
  id                  VARCHAR(40)  PRIMARY KEY,
  workspace_id        VARCHAR(40)  NOT NULL,
  name                VARCHAR(128) NOT NULL,
  type                VARCHAR(24)  NOT NULL,
  config_json         TEXT,
  status              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',

  last_tested_at      TIMESTAMPTZ,
  last_test_succeeded BOOLEAN,
  last_test_message   VARCHAR(512),

  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by          VARCHAR(40),
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by          VARCHAR(40),
  deleted             BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_gv_channel_name ON gv_alert_channel (workspace_id, name)
  WHERE deleted = FALSE;


-- ── 告警规则(序号 25)──────────────────────────────────────────────
CREATE TABLE gv_alert_rule (
  id                      VARCHAR(40)  PRIMARY KEY,
  workspace_id            VARCHAR(40)  NOT NULL,
  name                    VARCHAR(128) NOT NULL,
  description             VARCHAR(512),

  -- EXECUTION_FAILED / EXECUTION_TIMEOUT / EXECUTION_SLOW / ...
  trigger_type            VARCHAR(48)  NOT NULL,
  -- ALL(全部任务)/ SPECIFIC(指定任务)—— 需求原文就是这两种
  scope                   VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  target_job_ids_json     TEXT,
  threshold_ms            BIGINT,

  channel_ids_json        TEXT,

  -- 需求里的「告警频率」:通知之后多久内不再通知。0 = 不抑制。
  -- 一个每分钟跑一次、连续失败两小时的任务,不抑制会推送 120 条 ——
  -- 那不是"及时",那是让人把告警静音。
  suppress_window_seconds INTEGER      NOT NULL DEFAULT 600,

  status                  VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',

  created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by              VARCHAR(40),
  updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by              VARCHAR(40),
  deleted                 BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_gv_rule_name ON gv_alert_rule (workspace_id, name)
  WHERE deleted = FALSE;
-- 每次执行结束都要问"有哪些启用的规则盯着这类事件",所以按它建索引
CREATE INDEX idx_gv_rule_trigger ON gv_alert_rule (workspace_id, trigger_type, status)
  WHERE deleted = FALSE;


-- ── 告警信息(序号 26)──────────────────────────────────────────────
CREATE TABLE gv_alert (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40)  NOT NULL,
  rule_id         VARCHAR(40)  NOT NULL,
  -- 冗余规则名:规则改名或删除后,历史告警仍要说得清它是谁触发的
  rule_name       VARCHAR(128),

  status          VARCHAR(24)  NOT NULL,

  -- 抑制窗口按它分组:同一规则 + 同一任务的连续失败算同源。
  -- 只按 rule_id 分组的话,一个"全部任务失败就告警"的规则会在 A 任务
  -- 告警后把 B 任务的失败也抑制掉 —— 而那是两个不相关的故障。
  source_key      VARCHAR(200) NOT NULL,

  execution_id    VARCHAR(40),
  job_ref_id      VARCHAR(40),
  job_name        VARCHAR(128),

  title           VARCHAR(256) NOT NULL,
  content         TEXT,
  severity        VARCHAR(16)  NOT NULL DEFAULT 'WARNING',

  triggered_at    TIMESTAMPTZ  NOT NULL,
  notified_at     TIMESTAMPTZ,
  -- 指向压住它的那一条,排查时能顺着链找到源头
  suppressed_by   VARCHAR(40),

  acknowledged_by VARCHAR(40),
  acknowledged_at TIMESTAMPTZ,
  resolved_at     TIMESTAMPTZ,
  resolve_note    VARCHAR(512),

  notify_error    VARCHAR(1024),

  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 序号 26 的两个页面:今日 + 历史。两者都按触发时间倒序
CREATE INDEX idx_gv_alert_time ON gv_alert (workspace_id, triggered_at DESC);
CREATE INDEX idx_gv_alert_status ON gv_alert (workspace_id, status, triggered_at DESC);
-- 抑制查询:同源的最近一条通知是什么时候
CREATE INDEX idx_gv_alert_source ON gv_alert (workspace_id, source_key, notified_at DESC);


-- ── 审计日志(序号 27)──────────────────────────────────────────────
-- <b>不可变、只追加</b>:没有 updated_at,没有 deleted 列。
-- 一条能被修改的审计记录不是审计记录。
CREATE TABLE gv_audit_record (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40),

  user_id         VARCHAR(40),
  username        VARCHAR(64),
  client_ip       VARCHAR(64),

  action          VARCHAR(32)  NOT NULL,
  resource_type   VARCHAR(48)  NOT NULL,
  resource_id     VARCHAR(64),
  resource_name   VARCHAR(256),

  -- 序号 27 要求跨数据集成与数据开发聚合;记下归属 Space 让那个聚合有依据
  owner_space     VARCHAR(24),

  succeeded       BOOLEAN      NOT NULL DEFAULT TRUE,
  error_code      VARCHAR(64),

  -- 只存方法 + 路径,不存请求体:里面可能有凭据
  request_summary VARCHAR(512),
  detail          TEXT,

  occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gv_audit_time ON gv_audit_record (workspace_id, occurred_at DESC);
CREATE INDEX idx_gv_audit_user ON gv_audit_record (workspace_id, user_id, occurred_at DESC);
CREATE INDEX idx_gv_audit_resource ON gv_audit_record (workspace_id, resource_type, occurred_at DESC);


-- ── 监控查询用的索引 ────────────────────────────────────────────────
-- 序号 24 的五个口径都是对 rt_execution 的时间范围聚合。没有这个索引,
-- 每次打开监控页都会全表扫描一张持续增长的事实表。
CREATE INDEX idx_rt_execution_monitor
  ON rt_execution (workspace_id, submitted_at DESC, status);


-- ── 权限码 ─────────────────────────────────────────────────────────────
-- 序号 33「告警渠道」的菜单在「基础配置」下,归属却是 Governance(R2)——
-- 与执行器、文件管理同一个道理:它不是配置项。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:ops:monitor',        '任务监控', 'MENU', 'menu:ops',      '/ops/monitor',        'Odometer', 52, 'GOVERNANCE'),
  ('menu:ops:alert-rule',     '告警规则', 'MENU', 'menu:ops',      '/ops/alert-rules',    'Bell',     53, 'GOVERNANCE'),
  ('menu:ops:alert',          '告警信息', 'MENU', 'menu:ops',      '/ops/alerts',         'Warning',  54, 'GOVERNANCE'),
  ('menu:ops:audit',          '审计日志', 'MENU', 'menu:ops',      '/ops/audit',          'Document', 55, 'GOVERNANCE'),
  ('menu:settings:channel',   '告警渠道', 'MENU', 'menu:settings', '/settings/channels',  'Message',  68, 'GOVERNANCE');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:monitor:read',  '查看任务监控', 'ACTION', 'menu:ops:monitor',      1, 'GOVERNANCE'),
  ('governance:rule:read',     '查看告警规则', 'ACTION', 'menu:ops:alert-rule',   1, 'GOVERNANCE'),
  ('governance:rule:manage',   '管理告警规则', 'ACTION', 'menu:ops:alert-rule',   2, 'GOVERNANCE'),
  ('governance:alert:read',    '查看告警信息', 'ACTION', 'menu:ops:alert',        1, 'GOVERNANCE'),
  ('governance:alert:handle',  '认领/关闭告警', 'ACTION', 'menu:ops:alert',       2, 'GOVERNANCE'),
  -- 审计日志单独一个权限:能看任务不等于能看谁在什么时候删了什么
  ('governance:audit:read',    '查看审计日志', 'ACTION', 'menu:ops:audit',        1, 'GOVERNANCE'),
  ('governance:channel:read',  '查看告警渠道', 'ACTION', 'menu:settings:channel', 1, 'GOVERNANCE'),
  ('governance:channel:manage', '管理告警渠道', 'ACTION', 'menu:settings:channel', 2, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:ops:monitor'),
  ('role_builtin_ws_admin',     'menu:ops:alert-rule'),
  ('role_builtin_ws_admin',     'menu:ops:alert'),
  ('role_builtin_ws_admin',     'menu:ops:audit'),
  ('role_builtin_ws_admin',     'menu:settings:channel'),
  ('role_builtin_ws_admin',     'governance:monitor:read'),
  ('role_builtin_ws_admin',     'governance:rule:read'),
  ('role_builtin_ws_admin',     'governance:rule:manage'),
  ('role_builtin_ws_admin',     'governance:alert:read'),
  ('role_builtin_ws_admin',     'governance:alert:handle'),
  ('role_builtin_ws_admin',     'governance:audit:read'),
  ('role_builtin_ws_admin',     'governance:channel:read'),
  ('role_builtin_ws_admin',     'governance:channel:manage'),

  ('role_builtin_ws_developer', 'menu:ops:monitor'),
  ('role_builtin_ws_developer', 'menu:ops:alert-rule'),
  ('role_builtin_ws_developer', 'menu:ops:alert'),
  ('role_builtin_ws_developer', 'menu:settings:channel'),
  ('role_builtin_ws_developer', 'governance:monitor:read'),
  ('role_builtin_ws_developer', 'governance:rule:read'),
  ('role_builtin_ws_developer', 'governance:rule:manage'),
  ('role_builtin_ws_developer', 'governance:alert:read'),
  ('role_builtin_ws_developer', 'governance:alert:handle'),
  ('role_builtin_ws_developer', 'governance:channel:read'),
  -- 开发不看审计日志:那里面有其他人的操作痕迹,与他的工作无关

  ('role_builtin_ws_viewer',    'menu:ops:monitor'),
  ('role_builtin_ws_viewer',    'menu:ops:alert'),
  ('role_builtin_ws_viewer',    'governance:monitor:read'),
  ('role_builtin_ws_viewer',    'governance:alert:read');
