package com.datagov.metadata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结构浏览相关配置(对应 {@code dg.catalog.*})。
 */
@ConfigurationProperties(prefix = "dg.catalog")
public class CatalogProperties {

    /**
     * 快照有效期(分钟)。超过则重新探测目标库。
     *
     * <p>默认 30 分钟是个折中:库表结构不是高频变化的东西,但也不能永久缓存 ——
     * 用户在目标库加了一张表却在平台里看不到,会直接怀疑平台坏了。
     * 设为 0 表示不缓存,每次都实时探测(调试期有用,生产不建议:
     * 结构浏览会变成对生产库的高频元数据查询)。
     */
    private int snapshotTtlMinutes = 30;

    public int getSnapshotTtlMinutes() {
        return snapshotTtlMinutes;
    }

    public void setSnapshotTtlMinutes(int snapshotTtlMinutes) {
        this.snapshotTtlMinutes = snapshotTtlMinutes;
    }
}
