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
 * 达梦 DM8 连接器(功能 1)。信创关系库主力。
 *
 * <p>达梦在设计上高度兼容 Oracle:同样以 schema(=user)为组织单位、
 * 没有独立的库概念、类型系统里有 VARCHAR2/NUMBER/CLOB。因此这里的形状
 * 与 {@link OracleConnector} 一致,类型映射也以 Oracle 为底再覆盖差异。
 *
 * <p><b>为什么单独成类而不复用 Oracle 连接器</b>:URL 格式不同、系统 schema
 * 清单不同、驱动属性名不同。把它们合并会让类里满是 {@code if (type == DAMENG)},
 * 而这正是风险 R7 说的连接器矩阵开始失控的样子。
 */
@Component
public class DamengConnector extends AbstractJdbcConnector {

    /** 达梦自带的系统 schema */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "SYSDBA", "SYSAUDITOR", "SYSSSO", "SYSJOB", "CTISYS");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.DAMENG);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        // 达梦的 URL 里不带库名,库的概念由 schema 承担
        return "jdbc:dm://%s:%d".formatted(config.host(), portOrDefault(type, config));
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        props.setProperty("loginTimeout",
                String.valueOf(Math.max(1, config.connectTimeoutMillis() / 1000)));
        props.setProperty("socketTimeout", String.valueOf(config.readTimeoutMillis()));
        // 达梦默认大小写敏感策略与 Oracle 一致(标识符转大写),
        // 这里不做干预 —— 改它会让用户在达梦客户端里看到的表名和平台里的对不上。
    }

    @Override
    protected String listDatabasesSql() {
        return null;
    }

    @Override
    protected String metadataCatalog(ConnectionConfig config, CatalogPath path) {
        return null;
    }

    @Override
    public List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        return super.listSchemas(type, config, path).stream()
                .filter(schema -> schema.name() != null
                        && !SYSTEM_SCHEMAS.contains(schema.name().toUpperCase(Locale.ROOT)))
                .toList();
    }
}
