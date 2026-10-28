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
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * IBM Db2 连接器。
 *
 * <p>主要服务两类现场:金蝶 EAS 老版本(官方支持 SQL Server / Oracle / DB2 /
 * MySQL / PostgreSQL / DM)与部分用友 NC 部署。Db2 的库是连接级概念,一个连接
 * 只能看见当前数据库,因此这里不使用“切库”路径,模式由 {@code getSchemas} 给出。
 *
 * <p>驱动不在默认依赖里:Db2 驱动体积大、分发许可需客户确认。驱动缺失时
 * {@code DefaultDataAccessGateway} 不会把 DB2 暴露到可用类型列表,而不是等
 * 用户建完数据源才报错。
 */
@Component
public class Db2Connector extends AbstractJdbcConnector {

    /** Db2 自带的系统模式。统一大写比较,避免大小写策略差异导致过滤失效。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYSIBM", "SYSCAT", "SYSSTAT", "SYSFUN", "SYSPROC",
            "SYS", "NULLID", "SQLJ", "SYSIBMADM", "SYSIBMINTERNAL");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.DB2);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        return "jdbc:db2://%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), config.database());
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // Db2 驱动的 loginTimeout 单位是秒
        props.setProperty("loginTimeout",
                String.valueOf(Math.max(1, config.connectTimeoutMillis() / 1000)));
        props.setProperty("queryTimeout",
                String.valueOf(Math.max(1, config.readTimeoutMillis() / 1000)));
    }

    /** Db2 没有跨库查询,库由连接本身决定。 */
    @Override
    protected String listDatabasesSql() {
        return null;
    }

    @Override
    protected String metadataCatalog(ConnectionConfig config, CatalogPath path) {
        return null;
    }

    @Override
    public List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config,
                                        CatalogPath path) {
        return super.listSchemas(type, config, path).stream()
                .filter(schema -> schema.name() != null
                        && !SYSTEM_SCHEMAS.contains(schema.name().toUpperCase(Locale.ROOT)))
                .toList();
    }
}
