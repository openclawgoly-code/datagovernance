package com.datagov.data.connector;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.data.spi.RelationalCatalogReader;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DataAccessGateway} 的默认实现 —— Metadata Space 看到的那一面。
 *
 * <p>它做三件事:按类型找到连接器、按能力决定下钻返回哪一层、把驱动缺失
 * 挡在外面。除此之外不含任何业务判断 —— 数据源属于哪个空间、是否已通过
 * 连通性测试,都是 Metadata 的事,网关不该知道。
 */
@Component
public class DefaultDataAccessGateway implements DataAccessGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataAccessGateway.class);

    private final Map<DataSourceType, DataSourceConnector> registry = new EnumMap<>(DataSourceType.class);
    private final List<DataSourceType> availableTypes;

    public DefaultDataAccessGateway(List<DataSourceConnector> connectors) {
        for (DataSourceConnector connector : connectors) {
            for (DataSourceType type : connector.supportedTypes()) {
                DataSourceConnector existing = registry.put(type, connector);
                if (existing != null) {
                    // 静默取第一个会让"我明明改了连接器却没生效"变成一个极难排查的问题。
                    // 宁可启动失败。
                    throw new IllegalStateException(
                            "数据源类型 %s 存在多个连接器实现: %s 与 %s".formatted(
                                    type, existing.getClass().getName(), connector.getClass().getName()));
                }
            }
        }

        this.availableTypes = registry.keySet().stream()
                .filter(DefaultDataAccessGateway::driverPresent)
                .sorted()
                .toList();

        log.info("连接器注册完成: 已实现 {} 种类型,其中驱动可用 {} 种 -> {}",
                registry.size(), availableTypes.size(), availableTypes);
    }

    @Override
    public List<DataSourceType> availableTypes() {
        return availableTypes;
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return connector(type).capabilities(type);
    }

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        // 注意这里不 try/catch:SPI 已约定 testConnection 不抛异常。
        // 若某个实现违约,让它炸出来比悄悄吞掉更好 —— 那是实现的 bug,不是用户的输入问题。
        return connector(type).testConnection(type, config);
    }

    @Override
    public CatalogPage browse(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        DataSourceConnector connector = connector(type);
        ConnectorCapabilities capabilities = connector.capabilities(type);
        CatalogPath effectivePath = path == null ? CatalogPath.root() : path;

        if (capabilities.canBrowseFiles() && connector instanceof FileCatalogReader fileReader) {
            return CatalogPage.ofFiles(
                    fileReader.listEntries(type, config, effectivePath.path()));
        }

        if (capabilities.canBrowseCatalog() && connector instanceof RelationalCatalogReader reader) {
            return browseRelational(reader, type, config, effectivePath, capabilities);
        }

        throw new BizException(ErrorCode.DAT_CONNECTOR_NOT_FOUND,
                "%s 类型的数据源不支持结构浏览".formatted(type.displayName()),
                "capabilities: canBrowseCatalog=%s canBrowseFiles=%s"
                        .formatted(capabilities.canBrowseCatalog(), capabilities.canBrowseFiles()));
    }

    /**
     * 关系型的下钻规则。
     *
     * <p>三级树与两级树的区别集中在这一个 switch 里,而不是散落到每个连接器
     * 或前端 —— 加一种新引擎时只需正确声明 {@code hasSchemaLevel},
     * 下钻逻辑无需改动。
     */
    private CatalogPage browseRelational(RelationalCatalogReader reader, DataSourceType type,
                                         ConnectionConfig config, CatalogPath path,
                                         ConnectorCapabilities capabilities) {
        return switch (path.level()) {
            case ROOT -> CatalogPage.ofDatabases(reader.listDatabases(type, config));

            case DATABASE -> capabilities.hasSchemaLevel()
                    ? CatalogPage.ofSchemas(reader.listSchemas(type, config, path))
                    : CatalogPage.ofTables(reader.listTables(type, config, path));

            case SCHEMA -> CatalogPage.ofTables(reader.listTables(type, config, path));

            case TABLE -> CatalogPage.ofColumns(reader.listColumns(type, config, path));

            case PATH -> throw new BizException(ErrorCode.DAT_INTROSPECT_FAILED,
                    "%s 是关系型数据源,不能按文件路径浏览".formatted(type.displayName()));
        };
    }

    private DataSourceConnector connector(DataSourceType type) {
        DataSourceConnector connector = registry.get(type);
        if (connector == null) {
            throw new BizException(ErrorCode.DAT_CONNECTOR_NOT_FOUND,
                    "没有匹配的连接器实现: " + type);
        }
        if (!driverPresent(type)) {
            throw ConnectorExceptions.driverMissing(type.driverClassName(), null);
        }
        return connector;
    }

    /**
     * 驱动是否在 classpath 上。
     *
     * <p>非 JDBC 类型(FTP/SFTP/RestAPI)没有驱动概念,恒为 true。
     * 这个检查存在的意义在信创场景:达梦、人大金仓的驱动往往不能随开源包分发,
     * 需要现场放入 classpath。让"驱动没装"在类型列表里就体现出来,
     * 好过用户建完数据源、填完口令、点了测试连接才发现。
     */
    private static boolean driverPresent(DataSourceType type) {
        String driverClass = type.driverClassName();
        if (driverClass == null) {
            return true;
        }
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            log.warn("数据源类型 {} 的驱动未安装,该类型将不可用: {}", type, driverClass);
            return false;
        }
    }

    /** 便于测试与排障:列出所有已实现(不论驱动是否就绪)的类型。 */
    public List<DataSourceType> registeredTypes() {
        return new ArrayList<>(registry.keySet());
    }
}
