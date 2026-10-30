-- P11.7:看板布局/版本/细粒度权限
--
-- 版本只存“问题项定义”的快照,不存查询结果;回放/审计时重新问数。
-- 细粒度权限用 dashboard ACL,和 Space 级 insight:dashboard:* 叠加:
--   - Space 权限决定“能不能进看板功能”
--   - dashboard ACL 决定“能看/能改哪一个看板”
--   - 创建者始终可管理自己的看板
ALTER TABLE ig_dashboard
  ADD COLUMN version INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE';

ALTER TABLE ig_dashboard_item
  ADD COLUMN layout_json TEXT;

CREATE TABLE ig_dashboard_acl (
  id            VARCHAR(40)  PRIMARY KEY,
  dashboard_id  VARCHAR(40)  NOT NULL REFERENCES ig_dashboard(id),
  username      VARCHAR(64)  NOT NULL,
  permission    VARCHAR(16)  NOT NULL, -- READ / MANAGE
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by    VARCHAR(64)
);

CREATE UNIQUE INDEX uk_ig_dashboard_acl
  ON ig_dashboard_acl (dashboard_id, username);
CREATE INDEX idx_ig_dashboard_acl_user
  ON ig_dashboard_acl (username);

CREATE TABLE ig_dashboard_version (
  id            VARCHAR(40)  PRIMARY KEY,
  dashboard_id  VARCHAR(40)  NOT NULL REFERENCES ig_dashboard(id),
  version       INTEGER      NOT NULL,
  snapshot_json TEXT         NOT NULL,
  created_by    VARCHAR(64),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_ig_dashboard_version
  ON ig_dashboard_version (dashboard_id, version);
