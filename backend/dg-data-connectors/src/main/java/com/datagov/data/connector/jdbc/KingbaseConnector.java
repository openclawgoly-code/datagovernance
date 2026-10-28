package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.mapping.TypeMapper;
import com.datagov.data.connector.mapping.TypeMappers;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogPath;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 人大金仓 KingbaseES 连接器。
 *
 * <p>KingbaseES 以 PostgreSQL 为底座,大部分目录探测与类型系统可以复用
 * {@link PostgreSqlConnector};差异集中在三处:JDBC URL 前缀、系统模式清单、
 * 以及为了兼容 Oracle 应用而提供的 VARCHAR2 / NUMBER / CLOB 等类型别名。
 *
 * <p>不把 Kingbase 合并进 PostgreSQL 类型:URL 与驱动不同,合并后
 * {@code if (type == KINGBASE)} 会渗透到每个方法 —— 这正是 R7 要避免的形状。
 */
@Component
public class KingbaseConnector extends PostgreSqlConnector {

    /** Kingbase 自带的系统模式(在 PG 系统模式之外)。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "SYS_CATALOG", "SYSAUDIT", "SYSMAC", "XLOG", "SYSIBM");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.KINGBASE);
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String database = config.database() == null || config.database().isBlank()
                ? "kingbase"
                : config.database();
        return "jdbc:kingbase8://%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), database);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
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
