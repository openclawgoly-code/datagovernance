-- P5:Intelligence 契约(序号 34)与全民健康信息平台对接的合规底座(序号 35)
--
-- 序号 34「高质量数据集制备」<b>已确认独立立项</b>(架构风险 R1):它包含
-- OWL 2 七层医学概念体系、多智能体标注工厂、影像标注训练一体化数据飞轮与
-- 四维度质控引擎,工程量与序号 1-33 之和相当。本期不实现它,只锁定它与平台
-- 之间的四条契约(SPACE-MODEL.md C+D.8)——而契约要写成代码才算数:
-- 写在文档里的契约会在对接时被两边各自理解一遍。
--
-- 这一版落地其中两条:
--   第 3 条(注册)Dataset / Model 的标识与版本进 Metadata Registry,
--                 内容存对象存储 —— 所以下面没有一个 BLOB 列
--   第 4 条(语义)Column ──MapsTo──> Concept 写进 md_relation_edge
-- 另外两条不靠表结构:
--   第 1 条(取数)由 PythonJobCompiler 在编译期拦住直连配置
--   第 2 条(算力)由 JobType.PYTHON_JOB 复用统一 Execution 事实模型保证


-- ── 注册中心(契约第 3 条)──────────────────────────────────────────
-- Dataset / Model / Ontology 共用一张表:它们的注册信息完全同构,
-- 差异全在内容里,而内容不归平台管。
CREATE TABLE md_registry_artifact (
  id                       VARCHAR(40)  PRIMARY KEY,
  workspace_id             VARCHAR(40)  NOT NULL,

  kind                     VARCHAR(24)  NOT NULL,
  name                     VARCHAR(128) NOT NULL,
  description              VARCHAR(512),

  -- 契约第 2 条在数据上的痕迹:产物是哪一次执行产出的。
  -- 顺着它能追到 rt_execution,再追到当时的物理计划与定义版本 ——
  -- 没有它,"这个模型是用哪版数据训的"就只能靠人记
  produced_by_execution_id VARCHAR(40),

  latest_version           INTEGER      NOT NULL DEFAULT 0,

  created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by               VARCHAR(40),
  updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by               VARCHAR(40),
  deleted                  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uk_md_registry_name ON md_registry_artifact (workspace_id, kind, name)
  WHERE deleted = FALSE;
CREATE INDEX idx_md_registry_kind ON md_registry_artifact (workspace_id, kind)
  WHERE deleted = FALSE;


-- 版本。已发布的版本不可变 —— 这是"可复现"的前提,与作业制品(序号 32)
-- 同一条原则:一个可变的数据集版本意味着"上个月那次评测"今天再跑可能是
-- 另一个结果。所以这张表没有 updated_at,也没有逻辑删除列。
CREATE TABLE md_registry_version (
  id                       VARCHAR(40)  PRIMARY KEY,
  artifact_id              VARCHAR(40)  NOT NULL,
  workspace_id             VARCHAR(40)  NOT NULL,
  version                  INTEGER      NOT NULL,

  -- 只存地址,不存内容:影像、标注文件、模型权重动辄几十 GB,
  -- 平台的元数据库不该也不能承载它们
  content_uri              VARCHAR(1024) NOT NULL,
  size_bytes               BIGINT,
  checksum_sha256          VARCHAR(64),
  item_count               BIGINT,

  produced_by_execution_id VARCHAR(40),
  -- 数据飞轮的一条边:这一版是从哪一版做出来的
  derived_from_version_id  VARCHAR(40),

  metadata_json            TEXT,

  created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by               VARCHAR(40),

  CONSTRAINT uk_md_registry_version UNIQUE (artifact_id, version)
);

CREATE INDEX idx_md_registry_version_artifact
  ON md_registry_version (artifact_id, version DESC);


-- ── 关系边(契约第 4 条)────────────────────────────────────────────
-- 通用三元组,不是一张 column_concept_mapping 专用表:血缘与影响分析要
-- 遍历的是"任意实体之间的任意关系"。专用表会让下一种关系又开一张表,
-- 而遍历它们的代码要 UNION 全部。
CREATE TABLE md_relation_edge (
  id           VARCHAR(40)   PRIMARY KEY,
  workspace_id VARCHAR(40)   NOT NULL,

  from_type    VARCHAR(24)   NOT NULL,
  -- COLUMN 用「数据源ID:库.模式.表.字段」这种可拼可拆的形式,而不是指向
  -- md_catalog_snapshot 某一行的外键:快照会随刷新重建,外键会跟着失效,
  -- 而这条语义映射不该因为刷新了一次目录就丢掉
  from_id      VARCHAR(512)  NOT NULL,

  relation     VARCHAR(32)   NOT NULL,

  to_type      VARCHAR(24)   NOT NULL,
  -- CONCEPT 用本体里的 IRI —— 它的定义归 Intelligence,平台只记这条边
  to_id        VARCHAR(512)  NOT NULL,
  to_label     VARCHAR(256),

  -- 自动抽取的映射要能与人工确认过的区分开,否则复核时无从下手
  confidence   DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  origin       VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',

  created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  created_by   VARCHAR(40),
  deleted      BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_md_edge_from ON md_relation_edge (workspace_id, from_type, from_id)
  WHERE deleted = FALSE;
CREATE INDEX idx_md_edge_to ON md_relation_edge (workspace_id, to_type, to_id)
  WHERE deleted = FALSE;
-- 同一条映射不重复:一个字段映到同一个概念两次没有意义
CREATE UNIQUE INDEX uk_md_edge ON md_relation_edge
  (workspace_id, from_type, from_id, relation, to_id)
  WHERE deleted = FALSE;


-- ── 权限码 ─────────────────────────────────────────────────────────────
-- 注册中心与语义映射挂在「数据源管理」下:它们是元数据,归 Metadata Space。
-- Intelligence 独立立项之后,那个平台调的是这套接口,而不是自己建一个。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:registry', '数据集与模型', 'MENU', 'menu:metadata', '/metadata/registry', 'Collection', 12, 'METADATA');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:registry:read',   '查看注册项',   'ACTION', 'menu:metadata:registry', 1, 'METADATA'),
  ('metadata:registry:manage', '注册与发布版本', 'ACTION', 'menu:metadata:registry', 2, 'METADATA'),
  -- 语义映射单独一个权限:它是把医学概念挂到字段上,改错了会让血缘分析
  -- 给出错误答案,而那种错误很难被发现
  ('metadata:semantic:manage', '维护语义映射', 'ACTION', 'menu:metadata:registry', 3, 'METADATA');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:registry'),
  ('role_builtin_ws_admin',     'metadata:registry:read'),
  ('role_builtin_ws_admin',     'metadata:registry:manage'),
  ('role_builtin_ws_admin',     'metadata:semantic:manage'),

  ('role_builtin_ws_developer', 'menu:metadata:registry'),
  ('role_builtin_ws_developer', 'metadata:registry:read'),
  ('role_builtin_ws_developer', 'metadata:registry:manage'),
  ('role_builtin_ws_developer', 'metadata:semantic:manage'),

  ('role_builtin_ws_viewer',    'menu:metadata:registry'),
  ('role_builtin_ws_viewer',    'metadata:registry:read');


-- ── Python 任务的权限沿用任务管理那一套 ────────────────────────────────
-- 序号 34 的训练与预标注作业是 ctl_job_definition 里的一种 jobType,
-- 不是一个新对象 —— 所以它不需要新的权限码。这正是契约第 2 条想要的:
-- 复用统一 Execution 事实模型,自动获得监控、告警与审计。
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:dev:python', 'Python 任务', 'MENU', 'menu:dev', '/dev/python', 'Cpu', 44, 'CONTROL');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:dev:python'),
  ('role_builtin_ws_developer', 'menu:dev:python'),
  ('role_builtin_ws_viewer',    'menu:dev:python');
