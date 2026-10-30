package com.datagov.data.connector.glue;

import com.datagov.data.spi.catalog.CanonicalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.Partition;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Glue Catalog 映射")
class GlueCatalogProviderTest {

    private final GlueCatalogProvider provider = new GlueCatalogProvider(
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    @DisplayName("table/column/partition 映射到 CatalogAssets")
    void mapsTableAndPartitions() {
        StorageDescriptor sd = StorageDescriptor.builder()
                .columns(List.of(
                        Column.builder().name("id").type("bigint").comment("主键").build(),
                        Column.builder().name("amount").type("decimal(18,2)").build()))
                .location("s3://lake/dwd/orders")
                .build();
        Table table = Table.builder()
                .name("orders")
                .tableType("EXTERNAL_TABLE")
                .storageDescriptor(sd)
                .partitionKeys(List.of(Column.builder().name("dt").type("string").build()))
                .parameters(Map.of("numRows", "500", "totalSize", "4096"))
                .updateTime(Instant.parse("2026-10-30T00:00:00Z"))
                .build();
        Partition partition = Partition.builder()
                .values(List.of("2026-10-30"))
                .storageDescriptor(StorageDescriptor.builder()
                        .location("s3://lake/dwd/orders/dt=2026-10-30").build())
                .parameters(Map.of("numFiles", "2", "totalSize", "128"))
                .build();

        var dataset = provider.toDataset("dwd", table, List.of(partition));

        assertThat(dataset.qualifiedName()).isEqualTo("dwd.orders");
        assertThat(dataset.kind()).isEqualTo("EXTERNAL_TABLE");
        assertThat(dataset.format()).isEqualTo("GLUE");
        assertThat(dataset.location()).isEqualTo("s3://lake/dwd/orders");
        assertThat(dataset.rowCount()).isEqualTo(500L);
        assertThat(dataset.sizeBytes()).isEqualTo(4096L);
        assertThat(dataset.lastModifiedAt()).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(dataset.partitionColumns()).containsExactly("dt");
        assertThat(dataset.columns()).hasSize(3);
        assertThat(dataset.columns().get(0).canonicalType()).isEqualTo(CanonicalType.BIGINT);
        assertThat(dataset.columns().get(1).canonicalType()).isEqualTo(CanonicalType.DECIMAL);
        assertThat(dataset.columns().get(2).partitionKey()).isTrue();
        assertThat(dataset.partitions()).hasSize(1);
        assertThat(dataset.partitions().get(0).valuesJson())
                .isEqualTo("{\"dt\":\"2026-10-30\"}");
        assertThat(dataset.partitions().get(0).fileCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("VIEW 类型与空行数")
    void mapsView() {
        Table table = Table.builder()
                .name("order_view")
                .tableType("VIRTUAL_VIEW")
                .storageDescriptor(StorageDescriptor.builder().build())
                .parameters(Map.of())
                .build();

        var dataset = provider.toDataset("dwd", table, List.of());

        assertThat(dataset.kind()).isEqualTo("VIEW");
        assertThat(dataset.rowCount()).isNull();
        assertThat(dataset.sizeBytes()).isNull();
    }

    @Test
    @DisplayName("错误只回传根因")
    void rootMessageIsConcise() {
        Exception wrapper = new RuntimeException("sdk failure\n\tat x", 
                new IllegalStateException("Unable to load credentials"));

        assertThat(GlueCatalogProvider.rootMessage(wrapper))
                .isEqualTo("Unable to load credentials");
    }
}
