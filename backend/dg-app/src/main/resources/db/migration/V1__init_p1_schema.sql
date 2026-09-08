-- ═══════════════════════════════════════════════════════════════════════
--  数据治理平台 — P1 基线库表
--  目标库: PostgreSQL 16
--
--  表名前缀即所属 Space,这样一张表属于谁不需要查文档:
--    pf_   Platform / Tenancy   (功能 28/29/30 + 4 凭据托管)
--    md_   Metadata             (功能 1-5, 8)
--  P2 起将出现 ctl_(Control)、rt_(Runtime)、gov_(Governance)。
--
--  ── 关于「空间」(风险 R3)──────────────────────────────────────────
--  pf_workspace 是平台功能菜单里的「空间管理」= 租户。
--  它与架构中的 Space(边界)同名不同物。所有业务表一律带 workspace_id,
--  它是每个对象的顶层归属键。
--
--  ── 关于 JSON 列 ────────────────────────────────────────────────
--  连接扩展参数等以 text 存 JSON 而非 jsonb: P1 不需要按 JSON 内部字段
--  检索,text 让两个模块无需共享 MyBatis TypeHandler。待 P2 出现按参数
--  检索的需求时再迁移为 jsonb,届时是一次纯粹的类型变更。
-- ═══════════════════════════════════════════════════════════════════════


-- ───────────────────────────────────────────────────────────────────────
-- Platform / Tenancy Space
-- ───────────────────────────────────────────────────────────────────────

-- 空间(租户)—— 功能 28
CREATE TABLE pf_workspace (
    id                  VARCHAR(64)   PRIMARY KEY,
    code                VARCHAR(64)   NOT NULL,          -- 空间标识,全局唯一
    name                VARCHAR(128)  NOT NULL,          -- 空间名称
    description         VARCHAR(512),
    secret_key_enc      TEXT          NOT NULL,          -- 空间密钥,AES-GCM 密文
    status              VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_pf_workspace_code ON pf_workspace (code) WHERE deleted = FALSE;
COMMENT ON TABLE pf_workspace IS '空间(租户)。注意: 这是平台功能概念,不是架构中的 Space。';

-- 用户 —— 功能 30
CREATE TABLE pf_user (
    id                  VARCHAR(64)   PRIMARY KEY,
    username            VARCHAR(64)   NOT NULL,
    password_hash       VARCHAR(255)  NOT NULL,          -- BCrypt,单向,与 secret_key_enc 的可逆加密不同
    display_name        VARCHAR(128),
    email               VARCHAR(128),
    phone               VARCHAR(32),
    status              VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
    platform_admin      BOOLEAN       NOT NULL DEFAULT FALSE,     -- 唯一可跨空间的身份
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_pf_user_username ON pf_user (username) WHERE deleted = FALSE;

-- 空间成员(授权用户绑定)—— 功能 28 的「授权用户」
CREATE TABLE pf_workspace_member (
    workspace_id        VARCHAR(64)   NOT NULL,
    user_id             VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX idx_pf_workspace_member_user ON pf_workspace_member (user_id);

-- 权限项(菜单 / 操作)—— 功能 29 的权限全集
CREATE TABLE pf_permission (
    code                VARCHAR(128)  PRIMARY KEY,       -- 如 metadata:datasource:create
    name                VARCHAR(128)  NOT NULL,
    type                VARCHAR(32)   NOT NULL,          -- MENU / ACTION
    parent_code         VARCHAR(128),
    route_path          VARCHAR(255),                    -- MENU 类型对应的前端路由
    icon                VARCHAR(64),
    sort_order          INT           NOT NULL DEFAULT 0,
    owner_space         VARCHAR(32)   NOT NULL,          -- 该权限归属哪个架构 Space,便于按边界审计
    built_in            BOOLEAN       NOT NULL DEFAULT TRUE
);

-- 角色 —— 功能 29
CREATE TABLE pf_role (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64),                     -- NULL = 平台级内置角色,对所有空间可见
    code                VARCHAR(64)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    description         VARCHAR(512),
    built_in            BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
-- 平台级角色(workspace_id IS NULL)与空间级角色分别保证 code 唯一
CREATE UNIQUE INDEX uk_pf_role_platform_code ON pf_role (code)
    WHERE deleted = FALSE AND workspace_id IS NULL;
CREATE UNIQUE INDEX uk_pf_role_workspace_code ON pf_role (workspace_id, code)
    WHERE deleted = FALSE AND workspace_id IS NOT NULL;

CREATE TABLE pf_role_permission (
    role_id             VARCHAR(64)   NOT NULL,
    permission_code     VARCHAR(128)  NOT NULL,
    PRIMARY KEY (role_id, permission_code)
);

-- 用户在某空间下的角色授予
CREATE TABLE pf_user_role (
    workspace_id        VARCHAR(64)   NOT NULL,
    user_id             VARCHAR(64)   NOT NULL,
    role_id             VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    PRIMARY KEY (workspace_id, user_id, role_id)
);
CREATE INDEX idx_pf_user_role_lookup ON pf_user_role (workspace_id, user_id);

-- 接口认证凭据托管 —— 功能 4(RestAPI 数据源)的凭据部分
-- 归属 Platform 而非 Metadata: 凭据的生命周期、轮换与授权独立于任何一个数据源
CREATE TABLE pf_credential (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    auth_type           VARCHAR(32)   NOT NULL,          -- NONE / BASIC / BEARER / API_KEY / OAUTH2_CLIENT
    payload_enc         TEXT          NOT NULL,          -- AES-GCM 密文,内含该认证方式所需的全部字段
    description         VARCHAR(512),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_pf_credential_name ON pf_credential (workspace_id, name) WHERE deleted = FALSE;


-- ───────────────────────────────────────────────────────────────────────
-- Metadata / Semantic Kernel Space
-- ───────────────────────────────────────────────────────────────────────

-- 数据源 —— 功能 1/2/3/4
CREATE TABLE md_datasource (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    type                VARCHAR(32)   NOT NULL,          -- DataSourceType 枚举名
    family              VARCHAR(32)   NOT NULL,          -- RELATIONAL / MPP / FILE / HTTP
    status              VARCHAR(32)   NOT NULL,          -- DRAFT/TESTING/ACTIVE/UNREACHABLE/DISABLED
    description         VARCHAR(512),

    -- 连接参数。密码单独加密存放,其余为明文配置。
    host                VARCHAR(255),
    port                INT,
    database_name       VARCHAR(128),
    username            VARCHAR(128),
    password_enc        TEXT,                            -- AES-GCM 密文
    properties_json     TEXT,                            -- 驱动扩展参数,JSON 对象
    jdbc_url_override   VARCHAR(1024),
    base_url            VARCHAR(1024),                   -- RestAPI 用
    credential_id       VARCHAR(64),                     -- 引用 pf_credential,RestAPI 用
    connect_timeout_ms  INT           NOT NULL DEFAULT 10000,
    read_timeout_ms     INT           NOT NULL DEFAULT 30000,

    -- 最近一次连通性测试的事实。这是 Metadata 允许持有的"执行结果"的边界:
    -- 只记结论,不记过程;完整的执行历史属于 Runtime/Governance(P2/P4)。
    last_test_at        TIMESTAMPTZ,
    last_test_success   BOOLEAN,
    last_test_message   VARCHAR(1024),
    last_test_latency_ms BIGINT,

    version             INT           NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_md_datasource_name ON md_datasource (workspace_id, name) WHERE deleted = FALSE;
CREATE INDEX idx_md_datasource_workspace ON md_datasource (workspace_id, deleted);
CREATE INDEX idx_md_datasource_type ON md_datasource (workspace_id, type) WHERE deleted = FALSE;

-- 数据源版本历史 —— 功能 8「版本」语义的落点
CREATE TABLE md_datasource_version (
    id                  VARCHAR(64)   PRIMARY KEY,
    datasource_id       VARCHAR(64)   NOT NULL,
    workspace_id        VARCHAR(64)   NOT NULL,
    version             INT           NOT NULL,
    change_type         VARCHAR(32)   NOT NULL,          -- CREATED / UPDATED / STATUS_CHANGED / DISABLED
    snapshot_json       TEXT          NOT NULL,          -- 该版本的完整定义(密码字段已脱敏)
    change_summary      VARCHAR(512),
    changed_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    changed_by          VARCHAR(64)
);
CREATE UNIQUE INDEX uk_md_ds_version ON md_datasource_version (datasource_id, version);
CREATE INDEX idx_md_ds_version_lookup ON md_datasource_version (workspace_id, datasource_id, version DESC);

-- 目录结构快照 —— 功能 5「浏览库表结构」的缓存与留痕
-- 存在的理由: 结构浏览不该每次都打到生产库上,且"上次看到的结构长什么样"
-- 是 P4 血缘(功能 26)与 P2 字段映射的输入。
CREATE TABLE md_catalog_snapshot (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64)   NOT NULL,
    datasource_id       VARCHAR(64)   NOT NULL,
    level               VARCHAR(32)   NOT NULL,          -- ROOT / DATABASE / SCHEMA / TABLE / PATH
    path_database       VARCHAR(128),
    path_schema         VARCHAR(128),
    path_table          VARCHAR(128),
    path_literal        VARCHAR(1024),                   -- 文件型数据源的目录路径
    payload_json        TEXT          NOT NULL,          -- CatalogPage 的序列化结果
    item_count          INT           NOT NULL DEFAULT 0,
    captured_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    captured_by         VARCHAR(64)
);
-- 同一路径只保留最新一份快照,刷新即覆盖
CREATE UNIQUE INDEX uk_md_catalog_path ON md_catalog_snapshot (
    datasource_id,
    level,
    COALESCE(path_database, ''),
    COALESCE(path_schema, ''),
    COALESCE(path_table, ''),
    COALESCE(path_literal, '')
);
CREATE INDEX idx_md_catalog_ds ON md_catalog_snapshot (workspace_id, datasource_id);
