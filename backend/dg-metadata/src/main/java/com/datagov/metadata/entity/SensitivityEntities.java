package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 敏感标签(P10.4)。证据只存脱敏后的命中信息。 */
public final class SensitivityEntities {

    private SensitivityEntities() {
    }

    @Data
    @TableName("md_sensitivity_rule")
    public static class SensitivityRule {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        /** PII / FINANCIAL / MEDICAL / CREDENTIAL */
        private String category;
        /** L1 / L2 / L3 / L4 */
        private String level;
        /** COLUMN_NAME / REGEX / LUHN / DICTIONARY */
        private String detectorType;
        private String pattern;
        private String validator;
        private Integer priority;
        private Boolean enabled;
        private Instant createdAt;
        private String createdBy;
    }

    @Data
    @TableName("md_column_sensitivity")
    public static class ColumnSensitivity {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String datasetId;
        private String columnId;
        private String category;
        private String level;
        private Double confidence;
        /** SUGGESTED / CONFIRMED / REJECTED */
        private String status;
        private String evidenceJson;
        /** RULE / SCAN / MANUAL / LINEAGE */
        private String detectedBy;
        private Instant detectedAt;
        private String reviewedBy;
        private Instant reviewedAt;
    }

    @Data
    @TableName("md_scan_run")
    public static class ScanRun {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String sourceId;
        private Instant startedAt;
        private Instant finishedAt;
        private String status;
        private String statsJson;
        private String errorMessage;
    }

    @Data
    @TableName("md_scan_finding")
    public static class ScanFinding {
        @TableId(type = IdType.INPUT)
        private String id;

        private String runId;
        private String datasetId;
        private String columnId;
        private String ruleId;
        private String category;
        private String level;
        private Long matchCount;
        private Long sampleCount;
        private Double confidence;
        private String sampleMasked;
        private Instant createdAt;
    }
}
