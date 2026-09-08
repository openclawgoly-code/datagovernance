-- ═══════════════════════════════════════════════════════════════════════
-- V9 · 清洗/转换规则(功能 17)与任务目录(功能 16)
--
-- 规则是「双栖对象」:定义归 Metadata(本表),解释执行归 Runtime。
--
-- 关键约束(架构风险 R6):同步任务<b>引用</b> ruleId,不内嵌规则实现。
-- 若把实现写进任务配置,同一条「手机号脱敏」会在十几个任务里各有一份略微
-- 不同的拷贝,修一处漏九处 —— 而这种漏改在脱敏场景里就是一次合规事故。
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE md_rule (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  -- DATE_FORMAT / NUMBER_FORMAT / NULL_FILL(清洗)
  -- STRING_REPLACE / CHANGE_CASE / AFFIX / DECRYPT / TRIM(转换)
  kind            VARCHAR(32)  NOT NULL,
  description     VARCHAR(512),

  -- 参数,JSON 对象。键由 RuleKind.paramSpec() 规定。
  -- 解密规则里存的是 credentialId 而不是密钥本身 —— 一份明文密钥躺在这里,
  -- 而规则定义是所有人都能看的。
  params_json     TEXT,

  -- 被多少个任务引用。冗余它是为了让删除保护能一眼判断,而不是每次删除
  -- 都去扫所有任务定义的 config_json —— 那是个无法走索引的查询。
  reference_count INTEGER      NOT NULL DEFAULT 0,

  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by      VARCHAR(40),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by      VARCHAR(40),
  deleted         BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_rule_name
  ON md_rule (workspace_id, name) WHERE deleted = FALSE;

CREATE INDEX idx_md_rule_kind
  ON md_rule (workspace_id, kind) WHERE deleted = FALSE;


-- ── 任务目录(功能 16)──────────────────────────────────────────────────
-- 与数据源目录(功能 5)同构:人工维护的组织结构,不是任务的分类属性。
-- 复用同一套树形结构而不是发明新的 —— 用户对这两棵树的心智模型是一样的。
CREATE TABLE ctl_task_catalog (
  id           VARCHAR(40)  PRIMARY KEY,
  workspace_id VARCHAR(40)  NOT NULL,
  parent_id    VARCHAR(40),
  name         VARCHAR(128) NOT NULL,
  description  VARCHAR(512),
  sort_order   INTEGER      NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by   VARCHAR(40),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by   VARCHAR(40),
  deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 同一父节点下名称唯一。根节点 parent_id 为 NULL,而 SQL 里 NULL 不等于 NULL,
-- 所以要拆成两个部分索引 —— 单个 UNIQUE(workspace_id, parent_id, name) 对
-- 根节点根本不生效。
CREATE UNIQUE INDEX uk_ctl_catalog_name
  ON ctl_task_catalog (workspace_id, parent_id, name)
  WHERE deleted = FALSE AND parent_id IS NOT NULL;
CREATE UNIQUE INDEX uk_ctl_catalog_root_name
  ON ctl_task_catalog (workspace_id, name)
  WHERE deleted = FALSE AND parent_id IS NULL;

CREATE INDEX idx_ctl_catalog_parent ON ctl_task_catalog (workspace_id, parent_id)
  WHERE deleted = FALSE;

-- 任务归属目录。null 表示未分类 —— 强制归类会让新建多一步无谓的选择
ALTER TABLE ctl_job_definition ADD COLUMN catalog_id VARCHAR(40);
CREATE INDEX idx_ctl_job_catalog ON ctl_job_definition (workspace_id, catalog_id)
  WHERE deleted = FALSE;


-- ── 权限码 ─────────────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:integration:rule', '规则管理', 'MENU', 'menu:integration', '/integration/rules', 'MagicStick', 32, 'METADATA');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:rule:read',   '查看规则', 'ACTION', 'menu:integration:rule', 1, 'METADATA'),
  ('metadata:rule:manage', '管理规则', 'ACTION', 'menu:integration:rule', 2, 'METADATA'),
  ('control:catalog:manage', '管理任务目录', 'ACTION', 'menu:integration:job', 9, 'CONTROL');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:integration:rule'),
  ('role_builtin_ws_admin',     'metadata:rule:read'),
  ('role_builtin_ws_admin',     'metadata:rule:manage'),
  ('role_builtin_ws_admin',     'control:catalog:manage'),
  ('role_builtin_ws_developer', 'menu:integration:rule'),
  ('role_builtin_ws_developer', 'metadata:rule:read'),
  ('role_builtin_ws_developer', 'metadata:rule:manage'),
  ('role_builtin_ws_developer', 'control:catalog:manage'),
  ('role_builtin_ws_viewer',    'menu:integration:rule'),
  ('role_builtin_ws_viewer',    'metadata:rule:read');
