package com.datagov.data.connector.parquet;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogGateway;
import com.datagov.data.spi.catalog.CatalogProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CatalogProvider 注册表。
 *
 * <p>与 {@code DefaultDataAccessGateway} 同一条原则:一个来源类型只能有一个
 * Provider;重复注册在启动期失败,而不是在运行期静默选第一个。
 */
@Component
public class DefaultCatalogGateway implements CatalogGateway {

    private final Map<CatalogAssets.CatalogSourceType, CatalogProvider> providers =
            new EnumMap<>(CatalogAssets.CatalogSourceType.class);

    public DefaultCatalogGateway(List<CatalogProvider> providerList) {
        for (CatalogProvider provider : providerList) {
            for (CatalogAssets.CatalogSourceType type : provider.supportedTypes()) {
                CatalogProvider existing = providers.put(type, provider);
                if (existing != null) {
                    throw new IllegalStateException(
                            "Catalog 来源 %s 存在多个 Provider: %s 与 %s".formatted(
                                    type, existing.getClass().getName(),
                                    provider.getClass().getName()));
                }
            }
        }
    }

    @Override
    public CatalogAssets.Snapshot readCatalog(CatalogAssets.CatalogSourceType sourceType,
                                               DataSourceType dataSourceType,
                                               ConnectionConfig config,
                                               String rootPath) {
        CatalogProvider provider = providers.get(sourceType);
        if (provider == null) {
            throw new BizException(ErrorCode.DAT_CONNECTOR_NOT_FOUND,
                    "没有可用的 Catalog Provider: " + sourceType);
        }
        return provider.readCatalog(dataSourceType, config, rootPath);
    }
}
