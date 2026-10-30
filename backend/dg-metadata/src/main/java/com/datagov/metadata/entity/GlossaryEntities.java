package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 业务术语与资产绑定（P2）。 */
public final class GlossaryEntities {

    private GlossaryEntities() {
    }

    @Data
    @TableName("md_glossary_term")
    public static class GlossaryTerm {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String term;
        private String definition;
        /** JSON 数组：仅用于搜索/展示，不参与唯一键 */
        private String synonymsJson;
        private String domainId;
        /** DRAFT / PUBLISHED / DEPRECATED */
        private String status;
        private String ownerUsername;
        private String publishedBy;
        private Instant publishedAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_glossary_binding")
    public static class GlossaryBinding {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String termId;
        private String datasetId;
        /** 为空表示绑定整个数据集 */
        private String columnId;
        private String note;
        private Instant createdAt;
        private String createdBy;
    }
}
