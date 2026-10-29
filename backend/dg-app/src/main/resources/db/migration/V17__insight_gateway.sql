-- P11.2:智能问数网关(Phase 2 基础版)
--
-- 为什么单独一张查询日志,而不只写 gv_audit_record:
--   gv_audit_record 回答“谁做了什么”,适合操作审计;
--   这里回答“问了什么、生成了什么 SQL、命中哪些敏感列、返回多少行”,
--   是问数产品的质量与合规事实表。两者粒度不同,混表会让审计被查询明细淹没。
--
-- SQL 只存语句文本,不存结果数据;问题文本可能含业务信息,按 2000 字截断。

CREATE TABLE ig_query_log (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40)  NOT NULL,
  user_id         VARCHAR(40),
  username        VARCHAR(64),

  question        VARCHAR(2000) NOT NULL,
  agent_id        INTEGER,
  dataset_id      BIGINT,

  query_state     VARCHAR(24),
  generated_sql   TEXT,
  row_count       INTEGER,
  elapsed_ms      BIGINT,
  masked_columns  VARCHAR(1000),

  succeeded       BOOLEAN      NOT NULL DEFAULT FALSE,
  error_message   VARCHAR(1024),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ig_query_log_workspace_time
  ON ig_query_log (workspace_id, created_at DESC);
CREATE INDEX idx_ig_query_log_user_time
  ON ig_query_log (workspace_id, user_id, created_at DESC);


-- 权限码:问数挂在工作台下,先不建独立菜单,避免前端没有路由时出现死链。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('insight:ask',  '使用智能问数', 'ACTION', 'menu:workbench', 10, 'GOVERNANCE'),
  ('insight:read', '查看问数记录', 'ACTION', 'menu:workbench', 11, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'insight:ask'),
  ('role_builtin_ws_admin',     'insight:read'),
  ('role_builtin_ws_developer', 'insight:ask'),
  ('role_builtin_ws_developer', 'insight:read'),
  ('role_builtin_ws_viewer',    'insight:ask'),
  ('role_builtin_ws_viewer',    'insight:read');
