package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 资产治理实体（P2）。
 *
 * <p>刻意不放在 {@code md_catalog_dataset}：采集任务每次 refresh 都会
 * upsert 事实表，人工维护的 domain/owner/认证必须活在一张采集不会触碰的表里。
 */
public final class GovernanceEntities {

    private GovernanceEntities() {
    }

    @Data
    @TableName("md_business_domain")
    public static class BusinessDomain {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        /** workspace 内唯一，服务层统一规范为小写 */
        private String code;
        private String name;
        private String description;
        private String ownerUsername;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_asset_governance")
    public static class AssetGovernance {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String datasetId;
        private String domainId;
        private String ownerUsername;
        private String stewardUsername;
        /** NONE / CERTIFIED / DEPRECATED */
        private String certificationStatus;
        private String certifiedBy;
        private Instant certifiedAt;
        private String certificationNote;
        private Instant reviewDueAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }
}
