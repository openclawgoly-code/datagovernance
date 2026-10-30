-- P2 External Catalog：HMS / Glue 只读目录来源。
--
-- 外部目录不是 md_datasource：它是元数据目录，不是可查询数据源。
-- data_source_id 在 V14 已经是可空；这里显式 DROP NOT NULL 以兼容
-- 早期环境，并增加平台凭据引用（secret 只从 pf_credential 解析）。

ALTER TABLE md_catalog_source ALTER COLUMN data_source_id DROP NOT NULL;
ALTER TABLE md_catalog_source ADD COLUMN credential_id VARCHAR(40);

CREATE INDEX idx_md_catalog_source_credential
    ON md_catalog_source (workspace_id, credential_id) WHERE deleted = FALSE;
