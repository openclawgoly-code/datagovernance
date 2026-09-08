package com.datagov.data.spi.catalog;

import java.time.Instant;
import java.util.List;

/**
 * 目录(库/模式/表/列/文件)的结构模型集合。
 *
 * <p>集中放在一个文件里是刻意的: 这些 record 只有数据没有行为,
 * 且总是被一起阅读、一起演进。拆成六个文件只会增加跳转成本。
 */
public final class CatalogModel {

    private CatalogModel() {
    }

    /**
     * 库。
     *
     * <p>不同引擎对"库"的理解并不一致 —— MySQL 的 database 等价于 schema,
     * Oracle 没有独立的 database 概念而以 user/schema 组织,PostgreSQL 则
     * database 与 schema 两级都有。连接器负责把各自的现实投影到这个模型上,
     * 并通过 {@link ConnectorCapabilities#hasSchemaLevel()} 告诉 UI 该画两级还是三级树。
     */
    public record DatabaseInfo(
            String name,
            String charset,
            String collation,
            String comment
    ) {
    }

    /** 模式。对 MySQL/Doris 这类无独立 schema 层的引擎,连接器可返回单个与库同名的占位模式。 */
    public record SchemaInfo(
            String name,
            String owner,
            String comment
    ) {
    }

    /** 表或视图。 */
    public record TableInfo(
            String name,
            TableKind kind,
            String comment,
            Long approximateRowCount,
            Long sizeInBytes,
            Instant createdAt
    ) {
    }

    public enum TableKind {
        TABLE,
        VIEW,
        MATERIALIZED_VIEW,
        EXTERNAL_TABLE,
        /** Doris/StarRocks 的物理模型无法归入上述任一类时使用 */
        OTHER
    }

    /**
     * 列。
     *
     * @param rawType     目标端原始类型名(如 {@code NUMBER(10,2)}、{@code varchar2})—— 必须保留,
     *                    它是排查错误类型映射时唯一的证据
     * @param canonicalType 规范化类型
     */
    public record ColumnInfo(
            String name,
            String rawType,
            CanonicalType canonicalType,
            Integer precision,
            Integer scale,
            boolean nullable,
            boolean primaryKey,
            String defaultValue,
            String comment,
            int ordinalPosition
    ) {
    }

    /** 文件条目(FTP/SFTP)。 */
    public record FileEntry(
            String name,
            String path,
            boolean directory,
            long sizeInBytes,
            Instant lastModified
    ) {
    }

    /** 一次目录浏览的结果,按层级返回其一。 */
    public record CatalogPage(
            List<DatabaseInfo> databases,
            List<SchemaInfo> schemas,
            List<TableInfo> tables,
            List<ColumnInfo> columns,
            List<FileEntry> files
    ) {
        public static CatalogPage ofDatabases(List<DatabaseInfo> v) {
            return new CatalogPage(v, List.of(), List.of(), List.of(), List.of());
        }

        public static CatalogPage ofSchemas(List<SchemaInfo> v) {
            return new CatalogPage(List.of(), v, List.of(), List.of(), List.of());
        }

        public static CatalogPage ofTables(List<TableInfo> v) {
            return new CatalogPage(List.of(), List.of(), v, List.of(), List.of());
        }

        public static CatalogPage ofColumns(List<ColumnInfo> v) {
            return new CatalogPage(List.of(), List.of(), List.of(), v, List.of());
        }

        public static CatalogPage ofFiles(List<FileEntry> v) {
            return new CatalogPage(List.of(), List.of(), List.of(), List.of(), v);
        }
    }
}
