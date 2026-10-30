package com.datagov.data.connector.hive;

import com.datagov.data.spi.catalog.CanonicalType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HMS Catalog 映射")
class HmsCatalogProviderTest {

    private final HmsCatalogProvider provider = new HmsCatalogProvider(
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    @DisplayName("database/table/view/column/partition 映射到 CatalogAssets")
    void mapsTableAndPartitions() {
        StorageDescriptor sd = new StorageDescriptor();
        sd.setCols(List.of(
                new FieldSchema("id", "bigint", "主键"),
                new FieldSchema("amount", "decimal(18,2)", "金额")));
        sd.setLocation("s3://lake/dwd/orders");
        Table table = new Table();
        table.setTableName("orders");
        table.setTableType("EXTERNAL_TABLE");
        table.setSd(sd);
        table.setPartitionKeys(List.of(new FieldSchema("dt", "string", "分区")));
        table.setParameters(Map.of("numRows", "1000", "totalSize", "2048"));

        Partition partition = new Partition();
        partition.setValues(List.of("2026-10-30"));
        StorageDescriptor partitionSd = new StorageDescriptor();
        partitionSd.setLocation("s3://lake/dwd/orders/dt=2026-10-30");
        partition.setSd(partitionSd);
        partition.setParameters(Map.of("numFiles", "2", "totalSize", "100",
                "transient_lastDdlTime", "1790000000"));

        var dataset = provider.toDataset("dwd", table, List.of(partition));

        assertThat(dataset.namespacePath()).isEqualTo("dwd");
        assertThat(dataset.qualifiedName()).isEqualTo("dwd.orders");
        assertThat(dataset.kind()).isEqualTo("EXTERNAL_TABLE");
        assertThat(dataset.format()).isEqualTo("HIVE");
        assertThat(dataset.location()).isEqualTo("s3://lake/dwd/orders");
        assertThat(dataset.rowCount()).isEqualTo(1000L);
        assertThat(dataset.sizeBytes()).isEqualTo(2048L);
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
    @DisplayName("VIEW 类型正确映射")
    void mapsView() {
        Table table = new Table();
        table.setTableName("order_view");
        table.setTableType("VIRTUAL_VIEW");
        table.setSd(new StorageDescriptor());
        table.setParameters(Map.of());

        var dataset = provider.toDataset("dwd", table, List.of());

        assertThat(dataset.kind()).isEqualTo("VIEW");
        assertThat(dataset.rowCount()).isNull();
        assertThat(dataset.sizeBytes()).isNull();
    }

    @Test
    @DisplayName("错误只回传根因，不带完整堆栈")
    void rootMessageIsConcise() {
        Exception root = new java.net.ConnectException("Connection refused");
        Exception wrapper = new RuntimeException("Could not connect\n\tat foo.bar(Baz.java:1)", root);

        assertThat(HmsCatalogProvider.rootMessage(wrapper)).isEqualTo("Connection refused");
    }
}
