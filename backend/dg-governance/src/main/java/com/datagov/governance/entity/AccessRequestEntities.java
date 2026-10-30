package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 敏感数据临时访问申请与授权（P2）。 */
public final class AccessRequestEntities {

    private AccessRequestEntities() {
    }

    @Data
    @TableName("gv_access_request")
    public static class AccessRequest {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String requester;
        /** PII / FINANCIAL / MEDICAL / CREDENTIAL */
        private String category;
        /** L1 / L2 / L3 / L4 */
        private String maxLevel;
        private String reason;
        private Integer durationHours;
        /** PENDING / APPROVED / REJECTED / CANCELLED */
        private String status;
        private String approver;
        private Instant decidedAt;
        private String decisionNote;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @TableName("gv_access_grant")
    public static class AccessGrant {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String requestId;
        private String username;
        private String category;
        private String maxLevel;
        private Instant expiresAt;
        private Instant revokedAt;
        private String revokedBy;
        private Instant createdAt;
    }
}
