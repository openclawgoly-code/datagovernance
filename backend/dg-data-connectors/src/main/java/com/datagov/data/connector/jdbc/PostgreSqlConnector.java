package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.mapping.TypeMapper;
import com.datagov.data.connector.mapping.TypeMappers;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogPath;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * PostgreSQL 连接器(功能 1)。
 *
 * <p>三级目录树(库→模式→表)。与 MySQL 最大的差别在于:
 * <b>一个 PostgreSQL 连接只能看见一个库</b>,要列 X 库的模式就必须重新连到 X 库上。
 * 这就是 {@link #configForPath} 存在的理由 —— 把这条引擎现实收敛在连接器内部,
 * 不让它泄漏到 Metadata Space 去。
 */
@Component
public class PostgreSqlConnector extends AbstractJdbcConnector {

    /** 系统模式。用户找的是业务表,不是 pg_catalog。 */
    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("pg_catalog", "information_schema", "pg_toast");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.POSTGRESQL);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String database = config.database() == null || config.database().isBlank()
                ? "postgres"    // 未指定库时连默认库,否则驱动会用用户名当库名而报错
                : config.database();
        return "jdbc:postgresql://%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), database);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // 注意单位: PostgreSQL 驱动这两个参数是「秒」,与 MySQL 的毫秒不同。
        // 直接照抄 MySQL 的写法会得到 10000 秒的超时,等于没有超时。
        props.setProperty("connectTimeout",
                String.valueOf(Math.max(1, config.connectTimeoutMillis() / 1000)));
        props.setProperty("socketTimeout",
                String.valueOf(Math.max(1, config.readTimeoutMillis() / 1000)));
    }

    @Override
    protected String listDatabasesSql() {
        return """
               SELECT datname FROM pg_database
               WHERE datistemplate = false AND datallowconn = true
               ORDER BY datname
               """;
    }

    /**
     * PostgreSQL 驱动的 {@code getSchemas/getTables} 只认当前连接所在的库,
     * 传库名进去反而可能匹配不上。传 null 让驱动用当前库。
     */
    @Override
    protected String metadataCatalog(ConnectionConfig config, CatalogPath path) {
        return null;
    }

    /** 下钻到某个库时,把连接切到那个库上。 */
    @Override
    protected ConnectionConfig configForPath(ConnectionConfig config, CatalogPath path) {
        if (path.database() == null || path.database().equals(config.database())) {
            return config;
        }
        return ConnectionConfig.builder()
                .host(config.host())
                .port(config.port())
                .database(path.database())
                .username(config.username())
                .password(config.password())
                .properties(config.properties())
                .baseUrl(config.baseUrl())
                .connectTimeoutMillis(config.connectTimeoutMillis())
                .readTimeoutMillis(config.readTimeoutMillis())
                // 刻意不带 jdbcUrlOverride: 直填的 URL 锁死了库名,
                // 带过去会导致"切库"静默失效 —— 用户以为在看 X 库,实际看的是 URL 里那个库。
                .build();
    }

    @Override
    public List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        return super.listSchemas(type, config, path).stream()
                .filter(schema -> !SYSTEM_SCHEMAS.contains(schema.name()))
                .toList();
    }
}
