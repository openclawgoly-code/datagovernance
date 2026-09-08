package com.datagov.data.connector;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.ddl.DdlGateway;
import com.datagov.data.spi.ddl.DdlGenerator;
import com.datagov.data.spi.ddl.TableDdl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DdlGateway} 的默认实现 —— Runtime Space 看到的写面。
 *
 * <p>与 {@code DefaultDataAccessGateway} 分开的理由在 {@link DdlGateway} 的注释里:
 * 读面与写面是两个不同的 Space 边界,合成一个网关会让注入读面的人顺带拿到写能力。
 */
@Component
public class DefaultDdlGateway implements DdlGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultDdlGateway.class);

    private final Map<DataSourceType, DdlGenerator> registry = new EnumMap<>(DataSourceType.class);

    public DefaultDdlGateway(List<DataSourceConnector> connectors) {
        for (DataSourceConnector connector : connectors) {
            // 只有声明了建表能力的连接器进这张表。FTP / RestAPI 不实现
            // DdlGenerator,于是它们连出现在这里的机会都没有 —— 这比让它们
            // 实现一个只会抛异常的方法要好:错误在编译期就不存在。
            if (connector instanceof DdlGenerator generator) {
                for (DataSourceType type : connector.supportedTypes()) {
                    registry.put(type, generator);
                }
            }
        }
        log.info("建表能力注册完成: {} 种类型支持建表 -> {}", registry.size(), registry.keySet());
    }

    @Override
    public TableDdl.GeneratedDdl generateCreateTable(DataSourceType type,
                                                     TableDdl.CreateTableSpec spec) {
        return require(type).generateCreateTable(type, spec);
    }

    @Override
    public void executeDdl(DataSourceType type, ConnectionConfig config, List<String> statements)
            throws Exception {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        require(type).executeDdl(type, config, statements);
    }

    @Override
    public boolean supportsDdl(DataSourceType type) {
        return registry.containsKey(type);
    }

    private DdlGenerator require(DataSourceType type) {
        DdlGenerator generator = registry.get(type);
        if (generator == null) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                    "%s 不支持建表".formatted(type.displayName()),
                    "整库迁移的目标端必须是关系型或 MPP 数据源");
        }
        return generator;
    }
}
