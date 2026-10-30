-- P11.8:平台用户 → SuperSonic 账号映射
--
-- 只存映射关系和 credentialId,不存 SuperSonic 口令;口令由 Platform 的
-- CredentialService 加密托管。这样“谁能查什么”由平台身份决定,而不是让
-- 所有用户共用 SuperSonic admin。
CREATE TABLE ig_supersonic_mapping (
  id                  VARCHAR(40)  PRIMARY KEY,
  workspace_id        VARCHAR(40)  NOT NULL,
  platform_username   VARCHAR(64)  NOT NULL,
  supersonic_username VARCHAR(64)  NOT NULL,
  credential_id       VARCHAR(40)  NOT NULL,
  enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by          VARCHAR(64),
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_ig_supersonic_mapping
  ON ig_supersonic_mapping (workspace_id, platform_username);

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('insight:mapping:read',   '查看问数账号映射', 'ACTION', 'menu:insight', 6, 'GOVERNANCE'),
  ('insight:mapping:manage', '管理问数账号映射', 'ACTION', 'menu:insight', 7, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin', 'insight:mapping:read'),
  ('role_builtin_ws_admin', 'insight:mapping:manage');
