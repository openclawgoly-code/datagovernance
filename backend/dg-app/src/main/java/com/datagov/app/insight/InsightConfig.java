package com.datagov.app.insight;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InsightProperties.class)
public class InsightConfig {
}
