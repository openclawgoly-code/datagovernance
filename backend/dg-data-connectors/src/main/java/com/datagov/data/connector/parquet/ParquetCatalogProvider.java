package com.datagov.data.connector.parquet;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parquet 目录 Catalog Provider。
 *
 * <p>支持两类位置:
 * <ul>
 *   <li>{@code LOCAL} —— 本地/NFS 挂载目录</li>
 *   <li>{@code S3 / MINIO / OSS} —— S3 兼容对象存储</li>
 * </ul>
 *
 * <p>数据集识别规则:一个“不含 partition=value 段的目录”就是一个数据集,
 * 其下的 {@code key=value} 子目录是分区。这个规则与 Hive/Spark 写出的
 * Parquet 目录布局一致,也避免要求客户先部署 Hive Metastore。
 *
 * <p>只读 footer/schema/row count,不把数据拉进 PG。
 */
@Component
public class ParquetCatalogProvider implements CatalogProvider {

    private final ObjectMapper objectMapper;

    public ParquetCatalogProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<CatalogAssets.CatalogSourceType> supportedTypes() {
        return Set.of(CatalogAssets.CatalogSourceType.PARQUET_DIRECTORY);
    }

    @Override
    public CatalogAssets.Snapshot readCatalog(DataSourceType dataSourceType,
                                               ConnectionConfig config,
                                               String rootPath) {
        String location = DuckDbParquetSupport.location(dataSourceType, config, rootPath);
        List<String> files;
        try (Connection connection = DuckDbParquetSupport.open(dataSourceType, config)) {
            files = listParquetFiles(connection, location);
            if (files.isEmpty()) {
                return new CatalogAssets.Snapshot(
                        List.of(rootNamespace(location)), List.of());
            }

            Map<String, List<String>> byDataset = groupByDataset(files);
            List<CatalogAssets.Dataset> datasets = new ArrayList<>(byDataset.size());
            for (Map.Entry<String, List<String>> entry : byDataset.entrySet()) {
                datasets.add(readDataset(connection, location, entry.getKey(), entry.getValue()));
            }
            return new CatalogAssets.Snapshot(List.of(rootNamespace(location)), datasets);
        } catch (SQLException e) {
            throw new IllegalStateException("Parquet catalog 采集失败: " + e.getMessage(), e);
        }
    }

    // ── 文件发现与分组 ──────────────────────────────────────────────────

    private List<String> listParquetFiles(Connection connection, String location)
            throws SQLException {
        String pattern = location + "/**/*.parquet";
        List<String> files = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT file FROM glob(" + DuckDbParquetSupport.sqlLiteral(pattern)
                             + ") ORDER BY file")) {
            while (rs.next()) {
                files.add(rs.getString(1));
            }
        }
        return files;
    }

    private Map<String, List<String>> groupByDataset(List<String> files) {
        Map<String, List<String>> byDataset = new LinkedHashMap<>();
        for (String file : files) {
            byDataset.computeIfAbsent(datasetRoot(file), key -> new ArrayList<>()).add(file);
        }
        return byDataset;
    }

    /**
     * 去掉末尾的 partition=value 段,剩下的是数据集目录。
     *
     * <p>例:{@code /lake/orders/dt=2026-10-27/part.parquet} → {@code /lake/orders}。
     * 用“段里是否含 =”判定,而不是硬编码 dt/hour,因为客户的 Hive 分区列名
     * 可能是任意业务字段。
     */
    static String datasetRoot(String file) {
        String normalized = file.replace('\\', '/');
        String[] parts = normalized.split("/");
        int end = parts.length - 1; // 文件名本身
        while (end > 0 && parts[end - 1].contains("=")) {
            end--;
        }
        return String.join("/", Arrays.copyOfRange(parts, 0, end));
    }

    private CatalogAssets.Namespace rootNamespace(String location) {
        String name = lastSegment(location);
        return new CatalogAssets.Namespace(location, name, "DIRECTORY", null);
    }

    // ── 单个数据集 ──────────────────────────────────────────────────────

    private CatalogAssets.Dataset readDataset(Connection connection, String location,
                                              String datasetRoot, List<String> files) {
        String relative = relative(location, datasetRoot);
        if (relative.isBlank()) {
            relative = lastSegment(datasetRoot);
        }
        String name = lastSegment(datasetRoot);
        if (name.isBlank()) {
            name = relative;
        }

        Set<String> partitionKeys = partitionKeys(datasetRoot, files);
        List<CatalogAssets.Column> columns = readColumns(connection, datasetRoot, partitionKeys);
        Long rowCount = readRowCount(connection, datasetRoot);
        Long sizeBytes = localSize(files);
        List<CatalogAssets.Partition> partitions = readPartitions(datasetRoot, files);

        return new CatalogAssets.Dataset(
                location, name, relative, "PARQUET_DATASET", "PARQUET", datasetRoot,
                rowCount, sizeBytes, null, List.copyOf(partitionKeys), columns, partitions);
    }

    private List<CatalogAssets.Column> readColumns(Connection connection, String datasetRoot,
                                                   Set<String> partitionKeys) {
        String sql = "DESCRIBE SELECT * FROM read_parquet("
                + DuckDbParquetSupport.sqlLiteral(datasetRoot + "/**/*.parquet")
                + ", hive_partitioning=true)";
        List<CatalogAssets.Column> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int ordinal = 1;
            while (rs.next()) {
                // DESCRIBE 的输出列名里有一个叫 null —— 用序号取,避免和 SQL 关键字/驱动行为纠缠
                String name = rs.getString(1);
                String rawType = rs.getString(2);
                TypeInfo type = typeInfo(rawType);
                String nullableText = rs.getString(3);
                boolean nullable = nullableText == null
                        || "YES".equalsIgnoreCase(nullableText);
                columns.add(new CatalogAssets.Column(
                        name, ordinal++, rawType, type.canonicalType(), type.precision(),
                        type.scale(), nullable, false, partitionKeys.contains(name), null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Parquet schema 失败: "
                    + datasetRoot + " -> " + e.getMessage(), e);
        }
        return columns;
    }

    private Long readRowCount(Connection connection, String datasetRoot) {
        String sql = "SELECT sum(num_rows) FROM parquet_file_metadata("
                + DuckDbParquetSupport.sqlLiteral(datasetRoot + "/**/*.parquet") + ")";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
            return null;
        } catch (SQLException e) {
            // 行数只是展示项,读不到不该让整次采集失败
            return null;
        }
    }

    private Set<String> partitionKeys(String datasetRoot, List<String> files) {
        Set<String> keys = new LinkedHashSet<>();
        for (String file : files) {
            String relative = relative(datasetRoot, file);
            int slash = relative.lastIndexOf('/');
            String directory = slash < 0 ? "" : relative.substring(0, slash);
            for (String segment : directory.split("/")) {
                int eq = segment.indexOf('=');
                if (eq > 0) {
                    keys.add(segment.substring(0, eq));
                }
            }
        }
        return keys;
    }

    private List<CatalogAssets.Partition> readPartitions(String datasetRoot, List<String> files) {
        Map<String, List<String>> byPartition = new LinkedHashMap<>();
        for (String file : files) {
            String partitionPath = partitionPath(datasetRoot, file);
            if (!partitionPath.isBlank()) {
                byPartition.computeIfAbsent(partitionPath, key -> new ArrayList<>()).add(file);
            }
        }

        List<CatalogAssets.Partition> partitions = new ArrayList<>(byPartition.size());
        for (Map.Entry<String, List<String>> entry : byPartition.entrySet()) {
            Map<String, String> values = partitionValues(entry.getKey());
            partitions.add(new CatalogAssets.Partition(
                    datasetRoot + "/" + entry.getKey(),
                    writeJson(values),
                    (long) entry.getValue().size(),
                    localSize(entry.getValue()),
                    null));
        }
        return partitions;
    }

    private static String partitionPath(String datasetRoot, String file) {
        String relative = relative(datasetRoot, file);
        int slash = relative.lastIndexOf('/');
        String directory = slash < 0 ? "" : relative.substring(0, slash);
        List<String> kept = new ArrayList<>();
        for (String segment : directory.split("/")) {
            if (segment.contains("=")) {
                kept.add(segment);
            }
        }
        return String.join("/", kept);
    }

    private static Map<String, String> partitionValues(String partitionPath) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String segment : partitionPath.split("/")) {
            int eq = segment.indexOf('=');
            if (eq > 0) {
                values.put(segment.substring(0, eq), segment.substring(eq + 1));
            }
        }
        return values;
    }

    // ── 类型映射 ────────────────────────────────────────────────────────

    private record TypeInfo(CanonicalType canonicalType, Integer precision, Integer scale) {
    }

    static TypeInfo typeInfo(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("DECIMAL(") || type.startsWith("NUMERIC(")) {
            int open = type.indexOf('(');
            int comma = type.indexOf(',', open);
            int close = type.indexOf(')', comma);
            try {
                Integer precision = Integer.valueOf(type.substring(open + 1, comma).trim());
                Integer scale = Integer.valueOf(type.substring(comma + 1, close).trim());
                return new TypeInfo(CanonicalType.DECIMAL, precision, scale);
            } catch (RuntimeException ignored) {
                return new TypeInfo(CanonicalType.DECIMAL, null, null);
            }
        }
        if (type.startsWith("VARCHAR") || type.startsWith("CHAR")
                || type.startsWith("BPCHAR") || type.startsWith("STRING")) {
            return new TypeInfo(type.startsWith("CHAR") ? CanonicalType.CHAR
                    : CanonicalType.VARCHAR, null, null);
        }
        if (type.startsWith("TIMESTAMP")) {
            return new TypeInfo(type.contains("TIME ZONE")
                    ? CanonicalType.TIMESTAMP_TZ : CanonicalType.TIMESTAMP, null, null);
        }
        if (type.startsWith("ARRAY") || type.startsWith("LIST")) {
            return new TypeInfo(CanonicalType.ARRAY, null, null);
        }
        return switch (type) {
            case "BOOLEAN", "BOOL" -> new TypeInfo(CanonicalType.BOOLEAN, null, null);
            case "TINYINT", "INT1", "UTINYINT" -> new TypeInfo(CanonicalType.TINYINT, null, null);
            case "SMALLINT", "INT2", "USMALLINT" -> new TypeInfo(CanonicalType.SMALLINT, null, null);
            case "INTEGER", "INT", "INT4", "UINTEGER" -> new TypeInfo(CanonicalType.INT, null, null);
            case "BIGINT", "INT8" -> new TypeInfo(CanonicalType.BIGINT, null, null);
            case "HUGEINT", "UHUGEINT", "UBIGINT", "VARINT" ->
                    new TypeInfo(CanonicalType.DECIMAL, null, null);
            case "REAL", "FLOAT", "FLOAT4" -> new TypeInfo(CanonicalType.FLOAT, null, null);
            case "DOUBLE", "FLOAT8" -> new TypeInfo(CanonicalType.DOUBLE, null, null);
            case "DECIMAL", "NUMERIC" -> new TypeInfo(CanonicalType.DECIMAL, null, null);
            case "DATE" -> new TypeInfo(CanonicalType.DATE, null, null);
            case "TIME" -> new TypeInfo(CanonicalType.TIME, null, null);
            case "BLOB", "BYTEA" -> new TypeInfo(CanonicalType.BLOB, null, null);
            case "BIT" -> new TypeInfo(CanonicalType.BINARY, null, null);
            case "JSON" -> new TypeInfo(CanonicalType.JSON, null, null);
            case "UUID" -> new TypeInfo(CanonicalType.VARCHAR, null, null);
            case "STRUCT", "MAP", "UNION", "INTERVAL" ->
                    new TypeInfo(CanonicalType.UNKNOWN, null, null);
            default -> new TypeInfo(CanonicalType.UNKNOWN, null, null);
        };
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static String relative(String base, String path) {
        String prefix = base.endsWith("/") ? base : base + "/";
        if (path.equals(base)) {
            return "";
        }
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String lastSegment(String path) {
        String value = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static Long localSize(List<String> files) {
        long total = 0;
        boolean any = false;
        for (String file : files) {
            try {
                total += Files.size(Path.of(file));
                any = true;
            } catch (Exception ignored) {
                // S3 路径不是本地文件;大小留空即可,不影响 schema/血缘
            }
        }
        return any ? total : null;
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "{}";
        }
    }
}
