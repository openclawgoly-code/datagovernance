-- P6:外部分析 Agent 的结论归口(见 docs/analysis-report.md)
--
-- 为什么不是 rt_artifact:制品是<b>可执行/可部署</b>内容,报告是<b>结论</b>。
-- 混在一起会让清理策略、权限、签名语义打架;更根本的是对象归属错位 ——
-- 报告是"关于事实的解释",与 Provenance/AuditRecord 同层,归 Governance。
--
-- 为什么单独一张版本表:报告不可变,修订必须是新版本。否则"上周的结论
-- 是什么"这个问题无从回答,而它恰恰是复盘时最常被问到的。


CREATE TABLE gv_analysis_report (
  id              VARCHAR(40)  PRIMARY KEY,
  workspace_id    VARCHAR(40)  NOT NULL,
  subject_type    VARCHAR(24)  NOT NULL,
  subject_id      VARCHAR(64),
  title           VARCHAR(200) NOT NULL,
  state           VARCHAR(24)  NOT NULL DEFAULT 'PUBLISHED',
  latest_version  INTEGER      NOT NULL DEFAULT 1,
  correlation_id  VARCHAR(80)  NOT NULL,
  created_by      VARCHAR(40)  NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  retracted_at    TIMESTAMPTZ,
  retracted_by    VARCHAR(40),

  -- 幂等键。先查后插在并发下会双写,唯一索引是唯一可靠的做法:
  -- 插入走 ON CONFLICT DO NOTHING,冲突时把既有报告查回来。
  CONSTRAINT uk_gv_report_corr UNIQUE (workspace_id, correlation_id),
  CONSTRAINT ck_gv_report_state CHECK (state IN ('PUBLISHED', 'RETRACTED')),
  CONSTRAINT ck_gv_report_subject
    CHECK (subject_type IN ('EXECUTION', 'ALERT', 'JOB_DEF', 'DATASET', 'NONE'))
);

-- 按对象找它的历次分析(告警/执行详情页的「解释」入口)
CREATE INDEX idx_gv_report_subject
  ON gv_analysis_report (workspace_id, subject_type, subject_id, created_at DESC);
-- 列表页默认按时间倒序,并排除已撤回
CREATE INDEX idx_gv_report_list
  ON gv_analysis_report (workspace_id, created_at DESC);


-- 版本内容不可变:没有 updated_at,没有 deleted 列。
-- 修订 = 新的一行;旧版本是否 CURRENT 由 latest_version 派生,不落列。
CREATE TABLE gv_analysis_report_version (
  id                  VARCHAR(40)  PRIMARY KEY,
  report_id           VARCHAR(40)  NOT NULL REFERENCES gv_analysis_report(id),
  workspace_id        VARCHAR(40)  NOT NULL,
  version             INTEGER      NOT NULL,
  supersedes_version  INTEGER,
  summary             VARCHAR(500) NOT NULL,
  body_md             TEXT         NOT NULL,
  conclusion          VARCHAR(24)  NOT NULL,
  insufficient_reason VARCHAR(500),
  evidence_json       TEXT         NOT NULL,
  model_json          TEXT         NOT NULL,
  session_ref         VARCHAR(128),
  max_sensitivity     VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
  content_digest      CHAR(64)     NOT NULL,
  generated_at        TIMESTAMPTZ,
  created_by          VARCHAR(40)  NOT NULL,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

  CONSTRAINT uk_gv_report_version UNIQUE (report_id, version),
  CONSTRAINT ck_gv_report_conclusion
    CHECK (conclusion IN ('CONFIRMED', 'INCONCLUSIVE')),
  CONSTRAINT ck_gv_report_sensitivity
    CHECK (max_sensitivity IN ('L1', 'L2', 'L3', 'L4', 'UNKNOWN')),
  -- 「证据不足」必须是一等终态,不是失败:结论为 INCONCLUSIVE 时必须说明原因
  CONSTRAINT ck_gv_report_inconclusive
    CHECK (conclusion = 'CONFIRMED' OR insufficient_reason IS NOT NULL)
);

CREATE INDEX idx_gv_report_version_report
  ON gv_analysis_report_version (report_id, version DESC);


-- ── 权限种子 ─────────────────────────────────────────────────────────
-- 不新增菜单:分析报告的主要入口是告警/执行详情页的「解释」,
-- 单独开 /ops/analysis 而前端没有对应路由,会变成一个死链。
-- UI 排期时再把这两条 ACTION 挂到新菜单下。
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:analysis:read',  '查看分析报告', 'ACTION', 'menu:ops:alert', 3, 'GOVERNANCE'),
  ('governance:analysis:write', '发布分析报告', 'ACTION', 'menu:ops:alert', 4, 'GOVERNANCE');

-- 写权限按「人」授予,不设服务账号 —— 调用方必须带自己的 token,
-- created_by 即 token 主体(与 GOVERNANCE-MODEL §G 一致)。
INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'governance:analysis:read'),
  ('role_builtin_ws_admin',     'governance:analysis:write'),
  ('role_builtin_ws_developer', 'governance:analysis:read'),
  ('role_builtin_ws_developer', 'governance:analysis:write'),
  ('role_builtin_ws_viewer',    'governance:analysis:read');
