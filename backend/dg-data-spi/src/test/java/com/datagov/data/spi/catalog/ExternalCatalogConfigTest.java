package com.datagov.data.spi.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExternalCatalogConfig")
class ExternalCatalogConfigTest {

    @Test
    @DisplayName("toString 不暴露 accessKey/secretKey")
    void redactsSecrets() {
        ExternalCatalogConfig config = new ExternalCatalogConfig(
                CatalogAssets.CatalogSourceType.GLUE, "{\"region\":\"cn-north-1\"}",
                "AKIA_SECRET_ACCESS", "super-secret-key");

        String text = config.toString();

        assertThat(text).doesNotContain("AKIA_SECRET_ACCESS");
        assertThat(text).doesNotContain("super-secret-key");
        assertThat(text).contains("******");
    }
}
