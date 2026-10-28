package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Catalog 规范化实体(P10.1)。
 *
 * <p>与 {@code md_catalog_snapshot} 的区别:后者是“点开树节点时缓存的一页
 * CatalogPage JSON”,这里是可搜索、可关联血缘/敏感标签的资产表。两者并存,
 * 现有目录浏览接口先不动。
 */
public final class CatalogAssetEntities {

    private CatalogAssetEntities() {
    }

    @Data
    @TableName("md_catalog_source")
    public static class CatalogSource {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        /** JDBC / PARQUET_DIRECTORY / HIVE_METASTORE / GLUE / ICEBERG_REST / ENGINE_CATALOG */
        private String sourceType;
        /** JDBC 来源指向 md_datasource */
        private String dataSourceId;
        /** JSON:端点是对象存储/HMS/Glue 时使用 */
        private String configJson;
        private String status;
        private Instant lastRefreshedAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;

        @TableLogic
        private Boolean deleted;
    }

    @Data
    @TableName("md_catalog_namespace")
    public static class CatalogNamespace {
        @TableId(type = IdType.INPUT)
        private String id;

        private String sourceId;
        private String parentId;
        private String name;
        /** CATALOG / DATABASE / SCHEMA / DIRECTORY */
        private String kind;
        private String path;
        private String comment;
        private Integer ordinal;
        private Instant createdAt;
        private Instant updatedAt;

        @TableLogic
        private Boolean deleted;
    }

    @Data
    @TableName("md_catalog_dataset")
    public static class CatalogDataset {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String sourceId;
        private String namespaceId;
        private String name;
        private String qualifiedName;
        private String kind;
        private String format;
        private String location;
        private String comment;
        private String schemaHash;
        private Integer columnCount;
        private Long rowCount;
        private Long sizeBytes;
        private Instant lastModifiedAt;
        private String partitionColumnsJson;
        private String propertiesJson;
        private Instant createdAt;
        private Instant updatedAt;

        @TableLogic
        private Boolean deleted;
    }

    @Data
    @TableName("md_catalog_column")
    public static class CatalogColumn {
        @TableId(type = IdType.INPUT)
        private String id;

        private String datasetId;
        private String name;
        private Integer ordinal;
        private String rawType;
        private String canonicalType;
        private Integer precision;
        private Integer scale;
        private Boolean nullable;
        private Boolean primaryKey;
        private Boolean partitionKey;
        private String defaultValue;
        private String comment;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @TableName("md_catalog_schema_version")
    public static class CatalogSchemaVersion {
        @TableId(type = IdType.INPUT)
        private String id;

        private String datasetId;
        private String schemaHash;
        private String columnsJson;
        private String changeType;
        private Instant capturedAt;
    }

    @Data
    @TableName("md_catalog_partition")
    public static class CatalogPartition {
        @TableId(type = IdType.INPUT)
        private String id;

        private String datasetId;
        private String partitionPath;
        private String partitionValuesJson;
        private Long fileCount;
        private Long sizeBytes;
        private Long rowCount;
        private Instant lastModifiedAt;
        private Instant createdAt;
    }

    @Data
    @TableName("md_catalog_snapshot_run")
    public static class CatalogSnapshotRun {
        @TableId(type = IdType.INPUT)
        private String id;

        private String sourceId;
        private Instant startedAt;
        private Instant finishedAt;
        private String status;
        private String statsJson;
        private String errorMessage;
    }
}
