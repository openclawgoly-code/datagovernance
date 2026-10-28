package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 字段级血缘(P10.4)。边不删除,SQL 变化时旧边标 SUPERSEDED。 */
public final class LineageEntities {

    private LineageEntities() {
    }

    @Data
    @TableName("md_lineage_node")
    public static class LineageNode {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        /** TABLE / COLUMN / DATASET */
        private String nodeType;
        private String catalogDatasetId;
        private String catalogColumnId;
        private String qualifiedName;
        private String displayName;
        private Instant createdAt;
    }

    @Data
    @TableName("md_lineage_job")
    public static class LineageJob {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        /** FLINK / SPARK / DORIS / CDC / MANUAL */
        private String jobType;
        private String sourceRef;
        private String sqlHash;
        private Instant lastSeenAt;
        private Instant createdAt;
    }

    @Data
    @TableName("md_lineage_edge")
    public static class LineageEdge {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String fromNodeId;
        private String toNodeId;
        private String relation;
        private String transformExpr;
        private String jobId;
        private Double confidence;
        private String origin;
        private String sqlHash;
        private Instant discoveredAt;
        private String status;
    }
}
