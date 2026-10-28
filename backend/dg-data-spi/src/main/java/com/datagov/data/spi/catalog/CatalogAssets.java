package com.datagov.data.spi.catalog;

import java.time.Instant;
import java.util.List;

/**
 * Catalog 来源的批量快照模型。
 *
 * <p>与 {@link CatalogModel.CatalogPage} 的区别:后者是“点开树节点时的一页”，
 * 前者是“一次采集拿到的完整资产集”。1000 张表如果按页逐个调用，会产生
 * 上千次连接；批量快照让 Provider 自己决定用一次连接还是多次，调用方只关心结果。
 */
public final class CatalogAssets {

    private CatalogAssets() {
    }

    public enum CatalogSourceType {
        JDBC,
        PARQUET_DIRECTORY,
        HIVE_METASTORE,
        GLUE,
        ICEBERG_REST,
        ENGINE_CATALOG
    }

    public record Namespace(String path, String name, String kind, String comment) {
    }

    public record Column(String name, int ordinal, String rawType,
                         CanonicalType canonicalType, Integer precision, Integer scale,
                         boolean nullable, boolean primaryKey, boolean partitionKey,
                         String comment) {
    }

    public record Partition(String path, String valuesJson, Long fileCount,
                            Long sizeBytes, Instant lastModifiedAt) {
    }

    public record Dataset(String namespacePath, String name, String qualifiedName,
                          String kind, String format, String location,
                          Long rowCount, Long sizeBytes, Instant lastModifiedAt,
                          List<String> partitionColumns, List<Column> columns,
                          List<Partition> partitions) {
        public Dataset {
            partitionColumns = partitionColumns == null ? List.of() : List.copyOf(partitionColumns);
            columns = columns == null ? List.of() : List.copyOf(columns);
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }
    }

    public record Snapshot(List<Namespace> namespaces, List<Dataset> datasets) {
        public Snapshot {
            namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
        }
    }
}
