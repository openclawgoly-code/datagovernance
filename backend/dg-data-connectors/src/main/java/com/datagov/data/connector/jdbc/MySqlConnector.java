package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.mapping.TypeMapper;
import com.datagov.data.connector.mapping.TypeMappers;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogPath;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.Set;

/**
 * MySQL 连接器(功能 1)。
 *
 * <p>MySQL 的 database 与 schema 是同一个东西,所以目录树只有两级(库→表)。
 * {@link com.datagov.data.spi.ConnectorCapabilities#hasSchemaLevel()} 声明为 false,
 * 前端据此少画一层 —— 而不是靠前端写 {@code if (type === 'MYSQL')}。
 */
@Component
public class MySqlConnector extends AbstractJdbcConnector {

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.MYSQL);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithoutSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String database = config.database() == null ? "" : config.database();
        return "jdbc:mysql://%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), database);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // MySQL 驱动的这两个参数单位都是毫秒
        props.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));
        props.setProperty("socketTimeout", String.valueOf(config.readTimeoutMillis()));
        // 探测只读结构,不需要驱动去猜时区;显式关掉可避免 serverTimezone 相关的启动失败
        props.putIfAbsent("useSSL", "false");
        props.putIfAbsent("allowPublicKeyRetrieval", "true");
    }

    /**
     * 排除系统库。用户来这里是找自己的业务库的,把 mysql / sys 列出来只会碍事。
     */
    @Override
    protected String listDatabasesSql() {
        return """
               SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
               WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
               ORDER BY SCHEMA_NAME
               """;
    }

    /** MySQL 用 catalog 表达库,schema 恒为 null。 */
    @Override
    protected String metadataSchema(ConnectionConfig config, CatalogPath path) {
        return null;
    }
}
