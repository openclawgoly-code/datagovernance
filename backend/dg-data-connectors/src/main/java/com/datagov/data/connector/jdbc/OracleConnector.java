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
 * Oracle 连接器(功能 1)。
 *
 * <p>Oracle 没有独立的「库」概念 —— 一个实例就是一个数据库,组织单位是 schema
 * (等同于 user)。为了让 UI 的目录树在各引擎间保持同一形状,这里把连接配置里的
 * 服务名/SID 当作唯一的"库"占位返回,再在其下列出 schema。
 *
 * <p>{@code database} 字段承载服务名或 SID:以 {@code /} 开头视为服务名,
 * 否则按服务名处理(现代 Oracle 推荐服务名);需要用 SID 时请用直填 JDBC URL。
 */
@Component
public class OracleConnector extends AbstractJdbcConnector {

    /**
     * Oracle 自带的系统 schema。数量多且对用户毫无意义,不过滤的话业务 schema
     * 会淹没在几十个系统账号里。
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "SYSTEM", "SYSAUX", "OUTLN", "DBSNMP", "APPQOSSYS", "CTXSYS",
            "MDSYS", "OLAPSYS", "ORDDATA", "ORDSYS", "ORDPLUGINS", "SI_INFORMTN_SCHEMA",
            "WMSYS", "XDB", "LBACSYS", "DVSYS", "DVF", "GSMADMIN_INTERNAL", "AUDSYS",
            "OJVMSYS", "GGSYS", "ANONYMOUS", "XS$NULL", "REMOTE_SCHEDULER_AGENT",
            "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "SYS$UMF", "DBSFWUSER");

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.ORACLE);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String service = config.database() == null ? "" : config.database().trim();
        if (service.startsWith("/")) {
            service = service.substring(1);
        }
        // 「//host:port/service」是服务名写法,比老式的 host:port:SID 更通用
        return "jdbc:oracle:thin:@//%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), service);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // Oracle 驱动不认 loginTimeout,必须用它自己的属性名,单位毫秒
        props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(config.connectTimeoutMillis()));
        props.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(config.readTimeoutMillis()));
        // 不加这个,取 REMARKS(表/列注释)时驱动会返回空
        props.putIfAbsent("remarksReporting", "true");
    }

    /** Oracle 没有多库,返回 null 让基类用连接配置里的服务名占位。 */
    @Override
    protected String listDatabasesSql() {
        return null;
    }

    /** Oracle 的 JDBC catalog 概念不适用,一律传 null。 */
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
