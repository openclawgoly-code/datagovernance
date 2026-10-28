package com.datagov.data.spi.catalog;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;

/**
 * Catalog 采集网关。
 *
 * <p>与 {@code DataAccessGateway} 分开：数据查询是“读目标库”，catalog 采集是
 * “读元数据/文件 footer”。两条路径的失败语义、权限和性能特征都不同，混在一个
 * 接口里会让 Metadata 误以为自己可以直接查数据。
 */
public interface CatalogGateway {

    CatalogAssets.Snapshot readCatalog(CatalogAssets.CatalogSourceType sourceType,
                                        DataSourceType dataSourceType,
                                        ConnectionConfig config,
                                        String rootPath);
}
