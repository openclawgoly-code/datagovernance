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
 * Apache Doris / StarRocks 连接器(功能 3)。
 *
 * <p>一个实现覆盖两种类型:二者都源自同一分支,共用 MySQL 线协议与 MySQL 驱动,
 * 系统表布局与类型集也基本一致。但它们在 {@link DataSourceType} 里是两个取值 ——
 * 因为到了 P2 的异构建表阶段,分区语法、副本数、模型(明细/聚合/主键)都有差异,
 * 到那时再拆类型会牵动已存的数据源记录,现在分开的成本几乎为零。
 *
 * <p>目录树两级(库→表):与 MySQL 一样没有独立的 schema 层。
 */
@Component
public class DorisConnector extends AbstractJdbcConnector {

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.DORIS, DataSourceType.STARROCKS);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithoutSchema();
    }

    /**
     * 拼装 JDBC URL,支持多 FE 节点(功能 2)。
     *
     * <p>格式 {@code jdbc:mysql://h1:p1,h2:p2/db} —— MySQL 协议驱动原生支持多主机
     * 故障转移:第一个节点连不上时自动尝试后面的。Doris / StarRocks 的 FE 本来
     * 就是多副本部署,只配一个节点等于把对方已经做好的高可用白白扔掉。
     *
     * <p>走 FE 的 MySQL 协议端口(默认 9030),不是 HTTP 端口 8030 —— 这是
     * 新手最常配错的一处。
     */
    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String database = config.database() == null ? "" : config.database();

        StringBuilder hosts = new StringBuilder()
                .append(config.host()).append(':').append(portOrDefault(type, config));
        for (ConnectionConfig.Node node : config.nodes()) {
            if (node.host() != null && !node.host().isBlank()) {
                hosts.append(',').append(node.host()).append(':')
                        .append(node.port() > 0 ? node.port() : type.defaultPort());
            }
        }
        return "jdbc:mysql://%s/%s".formatted(hosts, database);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        props.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));
        props.setProperty("socketTimeout", String.valueOf(config.readTimeoutMillis()));
        props.putIfAbsent("useSSL", "false");
        props.putIfAbsent("allowPublicKeyRetrieval", "true");

        // 多节点时必须显式打开故障转移,否则驱动只用第一个主机 ——
        // 多配的节点形同虚设,而用户会以为自己配了高可用。
        if (!config.nodes().isEmpty()) {
            props.putIfAbsent("failOverReadOnly", "false");
            // 重试次数保持克制:MPP 查询往往较重,过多重试会放大目标端负载
            props.putIfAbsent("maxReconnects", "1");
        }
    }

    @Override
    protected String listDatabasesSql() {
        return """
               SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
               WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'sys', '__internal_schema')
               ORDER BY SCHEMA_NAME
               """;
    }

    @Override
    protected String metadataSchema(ConnectionConfig config, CatalogPath path) {
        return null;
    }
}
