-- ═══════════════════════════════════════════════════════════════════════
--  补齐两处 P1 缺口 —— 对照《数据治理平台功能规格说明》原文发现
--
--  功能 5「数据源目录」:支持管理数据源目录,包括目录的新增、编辑、查询、删除。
--    此前被误当作"浏览库表结构"而遗漏。库表结构浏览其实是功能 7「数据查询」。
--
--  功能 6「连通性测试」:…支持开启或关闭周期连通性检查。
--    此前只实现了手工测试;周期检查的开关与调度缺失,而状态机里
--    AVAILABLE──ProbeFailed──>UNREACHABLE 这条边正是为它准备的 —— 没有周期检查
--    的话,UNREACHABLE 状态永远进不去,状态机有一条边是死的。
-- ═══════════════════════════════════════════════════════════════════════


-- ── 功能 5:数据源目录 ──────────────────────────────────────────────────
-- 树形结构。parent_id 为 NULL 表示根节点。
--
-- 为什么用邻接表而不是路径枚举或闭包表:数据源目录是人工维护的组织结构,
-- 深度通常不超过 3-4 层、节点数以百计,邻接表递归查询完全够用。
-- 闭包表的写放大在这个规模上是纯粹的复杂度。
CREATE TABLE md_datasource_catalog (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64)   NOT NULL,
    parent_id           VARCHAR(64),                     -- NULL = 根节点
    name                VARCHAR(128)  NOT NULL,
    description         VARCHAR(512),
    sort_order          INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE
);
-- 同一父节点下名称唯一。根节点(parent_id IS NULL)与子节点分开约束,
-- 因为 SQL 里 NULL 不等于 NULL,合并写会让根节点之间的重名检查失效。
CREATE UNIQUE INDEX uk_md_ds_catalog_root_name ON md_datasource_catalog (workspace_id, name)
    WHERE deleted = FALSE AND parent_id IS NULL;
CREATE UNIQUE INDEX uk_md_ds_catalog_child_name ON md_datasource_catalog (workspace_id, parent_id, name)
    WHERE deleted = FALSE AND parent_id IS NOT NULL;
CREATE INDEX idx_md_ds_catalog_parent ON md_datasource_catalog (workspace_id, parent_id)
    WHERE deleted = FALSE;

COMMENT ON TABLE md_datasource_catalog IS '数据源目录(功能5)。注意与 md_catalog_snapshot 区分:'
    '前者是人工维护的组织结构,后者是从目标库探测来的库表结构快照(功能7)。';

-- 数据源归属目录。可为空 —— 未归类的数据源落在"未分类"里,
-- 强制归类会让新建数据源多一步无谓的选择。
ALTER TABLE md_datasource ADD COLUMN catalog_id VARCHAR(64);
CREATE INDEX idx_md_datasource_catalog ON md_datasource (workspace_id, catalog_id)
    WHERE deleted = FALSE;


-- ── 功能 6:周期连通性检查 ──────────────────────────────────────────────
-- 开关默认关闭。理由:周期检查会按间隔持续连接目标库,对生产库是真实负载;
-- 默认开启等于替用户做了一个他不知情的决定。
ALTER TABLE md_datasource ADD COLUMN probe_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- 检查间隔(分钟)。下限由应用层校验,避免有人填 1 分钟去打生产库。
ALTER TABLE md_datasource ADD COLUMN probe_interval_minutes INT NOT NULL DEFAULT 30;
-- 上次探测时间。与 last_test_at 分开:手工测试与周期探测是不同的边
-- (手工失败回 DRAFT,探测失败进 UNREACHABLE),各自的时间戳也不该混用。
ALTER TABLE md_datasource ADD COLUMN last_probe_at TIMESTAMPTZ;

-- 调度器每轮扫描"开了周期检查且到期"的数据源,这个索引支撑那次扫描
CREATE INDEX idx_md_datasource_probe ON md_datasource (probe_enabled, last_probe_at)
    WHERE deleted = FALSE AND probe_enabled = TRUE;


-- ── 功能 5 的权限项 ────────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:catalog:read',   '查看数据源目录', 'ACTION', 'menu:metadata:datasource', 7, 'METADATA'),
  ('metadata:catalog:manage', '管理数据源目录', 'ACTION', 'menu:metadata:datasource', 8, 'METADATA');

-- 内置角色补授:管理员与开发者可管目录,只读角色只能看
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission WHERE code LIKE 'metadata:catalog:%';
INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission WHERE code LIKE 'metadata:catalog:%';
INSERT INTO pf_role_permission (role_id, permission_code)
VALUES ('role_builtin_ws_viewer', 'metadata:catalog:read');
