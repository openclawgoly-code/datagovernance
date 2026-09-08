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
 * SQL Server 连接器(功能 1)。三级目录树(库→模式→表)。
 */
@Component
public class SqlServerConnector extends AbstractJdbcConnector {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "sys", "INFORMATION_SCHEMA", "guest", "db_owner", "db_accessadmin",
            "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader",
            "db_datawriter", "db_denydatareader", "db_denydatawriter");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.SQLSERVER);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://")
                .append(config.host()).append(':').append(portOrDefault(type, config));
        if (config.database() != null && !config.database().isBlank()) {
            url.append(";databaseName=").append(config.database());
        }
        return url.toString();
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // loginTimeout 单位是秒,socketTimeout 单位是毫秒 —— 同一个驱动里两种单位
        props.setProperty("loginTimeout",
                String.valueOf(Math.max(1, config.connectTimeoutMillis() / 1000)));
        props.setProperty("socketTimeout", String.valueOf(config.readTimeoutMillis()));

        // 新版驱动默认 encrypt=true,遇到自签证书会直接连不上。
        // 这里给一个可被用户覆盖的宽松默认值:内网自建实例占多数,
        // 需要严格校验证书的场景由用户在扩展参数里显式打开。
        props.putIfAbsent("encrypt", "false");
        props.putIfAbsent("trustServerCertificate", "true");
    }

    @Override
    protected String listDatabasesSql() {
        return """
               SELECT name FROM sys.databases
               WHERE state = 0 AND name NOT IN ('master', 'tempdb', 'model', 'msdb')
               ORDER BY name
               """;
    }

    @Override
    public List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        return super.listSchemas(type, config, path).stream()
                .filter(schema -> !SYSTEM_SCHEMAS.contains(schema.name()))
                .toList();
    }
}
