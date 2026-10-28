package com.datagov.data.spi.catalog;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;

import java.util.Set;

/**
 * Catalog 采集 SPI。
 *
 * <p>实现方负责把一种来源(JDBC / Parquet 目录 / Hive Metastore / Glue …)
 * 变成统一的 {@link CatalogAssets.Snapshot};调用方(Metadata)只负责落库。
 * 这样 P10.2 加对象存储、P10.3 加 HMS/Glue 时，不需要改 Catalog 表结构。
 */
public interface CatalogProvider {

    Set<CatalogAssets.CatalogSourceType> supportedTypes();

    /**
     * 读取一次完整 catalog。
     *
     * @param dataSourceType 连接类型(JDBC 时是数据库;Parquet 时是 LOCAL/S3/MINIO/OSS)
     * @param config         连接配置;对象存储的口令只在这里出现
     * @param rootPath       对象存储根路径;JDBC 来源可为空
     */
    CatalogAssets.Snapshot readCatalog(DataSourceType dataSourceType,
                                       ConnectionConfig config,
                                       String rootPath);
}
