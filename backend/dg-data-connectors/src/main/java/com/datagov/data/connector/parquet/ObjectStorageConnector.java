package com.datagov.data.connector.parquet;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 对象存储连接器。
 *
 * <p>它只负责“能不能连上”和“是不是对象存储”,不负责库表浏览 ——
 * Parquet 的 schema/分区由 {@link ParquetCatalogProvider} 采集。这样
 * {@code DataAccessGateway} 的关系型目录树不会被对象存储污染。
 *
 * <p>支持 LOCAL/S3/MINIO/OSS;HDFS 需要另接 Hadoop/WebHDFS,首期不混进来。
 */
@Component
public class ObjectStorageConnector implements DataSourceConnector {

    private static final Set<DataSourceType> TYPES = Set.of(
            DataSourceType.LOCAL, DataSourceType.S3,
            DataSourceType.MINIO, DataSourceType.OSS);

    @Override
    public Set<DataSourceType> supportedTypes() {
        return TYPES;
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.objectStorage();
    }

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        return DuckDbParquetSupport.test(type, config);
    }
}
