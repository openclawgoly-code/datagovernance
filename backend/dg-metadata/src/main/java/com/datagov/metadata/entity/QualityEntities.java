package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 资产质量规则与运行事实（P2）。 */
public final class QualityEntities {

    private QualityEntities() {
    }

    @Data
    @TableName("md_quality_rule")
    public static class QualityRule {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String datasetId;
        private String columnId;
        /** ROW_COUNT_RANGE / NOT_NULL / UNIQUE / FRESHNESS / SCHEMA_DRIFT */
        private String ruleType;
        private String configJson;
        /** CRITICAL / HIGH / MEDIUM / LOW */
        private String severity;
        private Boolean enabled;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_quality_run")
    public static class QualityRun {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String ruleId;
        private String datasetId;
        /** RUNNING / SUCCEEDED / FAILED */
        private String status;
        private Boolean passed;
        private String metricJson;
        private Long violations;
        private String errorMessage;
        private Instant startedAt;
        private Instant finishedAt;
        private Long durationMs;
        private String executedBy;
    }
}
