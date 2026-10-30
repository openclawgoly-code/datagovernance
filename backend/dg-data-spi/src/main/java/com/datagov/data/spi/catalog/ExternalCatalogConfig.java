package com.datagov.data.spi.catalog;

/**
 * 外部目录采集参数（HMS / Glue）。
 *
 * <p>外部目录没有 JDBC ConnectionConfig：配置是 endpoint/region 等非秘密字段，
 * 凭据由 Metadata 从 Platform 解析后以 accessKey/secretKey 形式一次性传入。
 * secretKey 不进入 {@code configJson}，也不会被持久化。
 */
public record ExternalCatalogConfig(
        CatalogAssets.CatalogSourceType sourceType,
        String configJson,
        String accessKey,
        String secretKey
) {
    /** 凭据永远不进日志：一次 debug 打印就足以让 secret 落到日志文件。 */
    @Override
    public String toString() {
        return "ExternalCatalogConfig[sourceType=%s, configJson=%s, accessKey=%s, secretKey=%s]"
                .formatted(sourceType, configJson,
                        accessKey == null || accessKey.isBlank() ? "<none>" : "******",
                        secretKey == null || secretKey.isBlank() ? "<none>" : "******");
    }
}
