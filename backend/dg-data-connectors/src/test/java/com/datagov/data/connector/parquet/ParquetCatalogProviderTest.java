package com.datagov.data.connector.parquet;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Parquet 目录 Catalog")
class ParquetCatalogProviderTest {

    @Test
    @DisplayName("识别数据集、分区列、行数与分区")
    void readsLocalParquetDirectory(@TempDir Path temp) throws Exception {
        Path orders = temp.resolve("orders/dt=2026-10-27");
        Path customers = temp.resolve("customers");
        java.nio.file.Files.createDirectories(orders);
        java.nio.file.Files.createDirectories(customers);

        createParquet(orders.resolve("part-0.parquet"),
                "SELECT 1 AS id, 'alice' AS name");
        createParquet(customers.resolve("part-0.parquet"),
                "SELECT 10 AS customer_id, '上海' AS city");

        ConnectionConfig config = ConnectionConfig.builder()
                .database(temp.toString())
                .build();
        ParquetCatalogProvider provider = new ParquetCatalogProvider(new ObjectMapper());

        CatalogAssets.Snapshot snapshot = provider.readCatalog(
                DataSourceType.LOCAL, config, temp.toString());

        assertThat(snapshot.datasets()).hasSize(2);
        CatalogAssets.Dataset order = snapshot.datasets().stream()
                .filter(d -> d.name().equals("orders")).findFirst().orElseThrow();
        assertThat(order.qualifiedName()).isEqualTo("orders");
        assertThat(order.kind()).isEqualTo("PARQUET_DATASET");
        assertThat(order.format()).isEqualTo("PARQUET");
        assertThat(order.rowCount()).isEqualTo(1L);
        assertThat(order.partitionColumns()).containsExactly("dt");
        assertThat(order.columns()).extracting(CatalogAssets.Column::name)
                .containsExactly("id", "name", "dt");
        assertThat(order.columns()).filteredOn(c -> c.name().equals("dt"))
                .singleElement().satisfies(c -> assertThat(c.partitionKey()).isTrue());
        assertThat(order.partitions()).hasSize(1);
        assertThat(order.partitions().get(0).path()).contains("dt=2026-10-27");
        assertThat(order.partitions().get(0).fileCount()).isEqualTo(1L);

        CatalogAssets.Dataset customer = snapshot.datasets().stream()
                .filter(d -> d.name().equals("customers")).findFirst().orElseThrow();
        assertThat(customer.partitions()).isEmpty();
        assertThat(customer.columns()).extracting(CatalogAssets.Column::name)
                .containsExactly("customer_id", "city");
    }

    @Test
    @DisplayName("分区目录剥离:任意 key=value 都算分区,不硬编码 dt/hour")
    void datasetRootStripsArbitraryPartitionSegments() {
        assertThat(ParquetCatalogProvider.datasetRoot(
                "/lake/orders/dt=2026-10-27/hour=09/part.parquet"))
                .isEqualTo("/lake/orders");
        assertThat(ParquetCatalogProvider.datasetRoot(
                "/lake/customers/part.parquet"))
                .isEqualTo("/lake/customers");
    }

    @Test
    @DisplayName("本地对象存储连通性测试")
    void localObjectStorageTest(@TempDir Path temp) {
        ConnectionConfig config = ConnectionConfig.builder()
                .database(temp.toString()).build();
        assertThat(DuckDbParquetSupport.test(DataSourceType.LOCAL, config).success())
                .isTrue();

        ConnectionConfig missing = ConnectionConfig.builder()
                .database(temp.resolve("nope").toString()).build();
        assertThat(DuckDbParquetSupport.test(DataSourceType.LOCAL, missing).success())
                .isFalse();
    }

    private static void createParquet(Path path, String select) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO "
                    + DuckDbParquetSupport.sqlLiteral(path.toString())
                    + " (FORMAT PARQUET)");
        }
    }
}
