package com.datagov.metadata.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata Space 的配置注册。
 *
 * <p>每个 Space 自己登记它需要的配置属性,而不是让装配层去收集 ——
 * 否则新增一项配置就要改 dg-app,Space 的自治就打了折扣。
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class MetadataConfig {
}
