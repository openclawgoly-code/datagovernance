package com.datagov.data.connector.parquet;

import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

/**
 * DuckDB 与对象存储的轻量适配。
 *
 * <p>Parquet 目录采集只需要读 footer、schema 和分区统计,不需要一个完整的
 * Hadoop/Spark 运行时。DuckDB 的 `read_parquet` / `parquet_file_metadata`
 * 足够做这件事,而且对 S3/MinIO 的 httpfs 支持可以直接复用。
 *
 * <p>明确边界:它只用于 <b>catalog 采集/预览</b>,不是生产查询引擎。
 * 生产查询应委托给 StarRocks/Doris/Trino/Spark。
 */
public final class DuckDbParquetSupport {

    private DuckDbParquetSupport() {
    }

    /**
     * 把数据源 + rootPath 归一成一个 DuckDB 可读的 location。
     *
     * <p>LOCAL 用绝对路径;S3 系列用 {@code s3://bucket/prefix}。rootPath 已带
     * 协议前缀时原样保留,便于客户直接填 {@code s3://...}。
     */
    public static String location(DataSourceType type, ConnectionConfig config,
                                  String rootPath) {
        String root = rootPath == null || rootPath.isBlank() ? null : rootPath.trim();
        if (root != null && (root.startsWith("s3://") || root.startsWith("file://"))) {
            return trimTrailingSlash(root);
        }
        if (type == DataSourceType.LOCAL) {
            String path = root != null ? root : config.database();
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("本地目录未配置");
            }
            return Path.of(path).toAbsolutePath().normalize().toString();
        }

        String bucket = config.database();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("对象存储 Bucket 未配置");
        }
        String prefix = root == null ? "" : root.replaceAll("^/+", "");
        return trimTrailingSlash("s3://" + bucket + (prefix.isEmpty() ? "" : "/" + prefix));
    }

    /** 打开一个内存 DuckDB 连接;S3 类型会先加载 httpfs 并注入凭据。 */
    public static Connection open(DataSourceType type, ConnectionConfig config)
            throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        if (isS3(type)) {
            configureS3(connection, type, config);
        }
        return connection;
    }

    /** 供 ObjectStorageConnector 的“测试连接”使用。 */
    public static ConnectivityResult test(DataSourceType type, ConnectionConfig config) {
        long started = System.currentTimeMillis();
        try {
            if (type == DataSourceType.LOCAL) {
                Path path = Path.of(location(type, config, null));
                if (!Files.exists(path)) {
                    return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED,
                            elapsed(started), "目录不存在: " + path, null);
                }
                if (!Files.isDirectory(path)) {
                    return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED,
                            elapsed(started), "不是目录: " + path, null);
                }
                return ConnectivityResult.success(elapsed(started), "local");
            }

            try (Connection connection = open(type, config);
                 Statement statement = connection.createStatement()) {
                String pattern = sqlLiteral(location(type, config, null) + "/*");
                statement.executeQuery("SELECT 1 FROM glob(" + pattern + ") LIMIT 1").close();
                return ConnectivityResult.success(elapsed(started), type.displayName());
            }
        } catch (Exception e) {
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED,
                    elapsed(started), "对象存储连接失败: " + e.getMessage(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static boolean isS3(DataSourceType type) {
        return type == DataSourceType.S3 || type == DataSourceType.MINIO
                || type == DataSourceType.OSS;
    }

    /** 转义 DuckDB 字符串字面量。路径里出现单引号时不能拼坏 SQL。 */
    public static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static void configureS3(Connection connection, DataSourceType type,
                                    ConnectionConfig config) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            loadHttpfs(statement);

            String endpoint = firstNonBlank(config.host(), config.baseUrl());
            boolean useSsl = true;
            if (endpoint != null) {
                String lower = endpoint.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://")) {
                    useSsl = false;
                    endpoint = endpoint.substring("http://".length());
                } else if (lower.startsWith("https://")) {
                    endpoint = endpoint.substring("https://".length());
                }
                endpoint = trimTrailingSlash(endpoint);
                statement.execute("SET s3_endpoint = " + sqlLiteral(endpoint));
            }

            Map<String, String> props = config.properties();
            if (config.username() != null && !config.username().isBlank()) {
                statement.execute("SET s3_access_key_id = " + sqlLiteral(config.username()));
            }
            if (config.password() != null && !config.password().isBlank()) {
                statement.execute("SET s3_secret_access_key = " + sqlLiteral(config.password()));
            }
            statement.execute("SET s3_region = "
                    + sqlLiteral(props.getOrDefault("region", "us-east-1")));

            String urlStyle = props.getOrDefault("s3_url_style",
                    (type == DataSourceType.MINIO || type == DataSourceType.OSS)
                            ? "path" : "vhost");
            statement.execute("SET s3_url_style = " + sqlLiteral(urlStyle));

            String ssl = props.get("s3_use_ssl");
            if (ssl == null) {
                ssl = String.valueOf(useSsl && (config.port() == null || config.port() == 443
                        || type == DataSourceType.S3 || type == DataSourceType.OSS));
            }
            statement.execute("SET s3_use_ssl = " + Boolean.parseBoolean(ssl));
        }
    }

    private static void loadHttpfs(Statement statement) throws SQLException {
        try {
            statement.execute("LOAD httpfs");
        } catch (SQLException first) {
            // DuckDB 1.x 的扩展可能未预装;首次运行允许自动安装。
            statement.execute("INSTALL httpfs");
            statement.execute("LOAD httpfs");
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, System.currentTimeMillis() - started);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
