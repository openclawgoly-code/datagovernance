package com.datagov.data.connector.jdbc;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Testcontainers 版本的 PostgreSQL 验证 —— 面向<b>用户的 CI 环境</b>。
 *
 * <p>与 {@link PostgreSqlConnectorLiveIT} 的分工:
 * <ul>
 *   <li>Live 版打外部真实数据库,在开发容器与本机开发都能跑</li>
 *   <li>本类自带容器,在有 Docker 的 CI 上一条命令即可复现,无需预置数据库</li>
 * </ul>
 *
 * <p><b>没有 Docker 时跳过而非失败</b>:本项目的开发容器可以运行 Docker 守护进程,
 * 但镜像仓库被网络策略封禁(拉取返回 403),因此这里必然是 skip。
 * 让它 skip 而不是 fail,是为了让"没有 Docker 也能跑完整套测试"这件事成立 ——
 * 否则每个开发者都要先装 Docker 才能验证一个纯粹的类型映射改动。
 */
@DisplayName("PostgreSQL 连接器 — Testcontainers(无 Docker 时跳过)")
class PostgreSqlConnectorContainerIT {

    private static final String IMAGE = "postgres:16-alpine";

    private final PostgreSqlConnector connector = new PostgreSqlConnector();

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(),
                "Docker 不可用(守护进程未运行或镜像不可拉取),跳过 Testcontainers 测试");
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Test
    @DisplayName("容器内 PostgreSQL 的连通性测试与结构浏览")
    void connectsAndBrowsesInsideContainer() throws SQLException {
        try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("dg_probe")
                .withUsername("probe")
                .withPassword("probe")) {

            container.start();

            seed(container);

            ConnectionConfig config = ConnectionConfig.builder()
                    .host(container.getHost())
                    .port(container.getFirstMappedPort())
                    .database("dg_probe")
                    .username("probe")
                    .password("probe")
                    .connectTimeoutMillis(5_000)
                    .readTimeoutMillis(15_000)
                    .build();

            ConnectivityResult result = connector.testConnection(DataSourceType.POSTGRESQL, config);
            assertThat(result.success()).isTrue();
            assertThat(result.serverVersion()).containsIgnoringCase("PostgreSQL");

            List<TableInfo> tables = connector.listTables(DataSourceType.POSTGRESQL, config,
                    CatalogPath.ofSchema("dg_probe", "public"));
            assertThat(tables).extracting(TableInfo::name).contains("t_demo");

            List<ColumnInfo> columns = connector.listColumns(DataSourceType.POSTGRESQL, config,
                    CatalogPath.ofTable("dg_probe", "public", "t_demo"));
            Map<String, ColumnInfo> byName = columns.stream()
                    .collect(Collectors.toMap(ColumnInfo::name, Function.identity()));

            assertThat(byName.get("id").canonicalType()).isEqualTo(CanonicalType.BIGINT);
            assertThat(byName.get("id").primaryKey()).isTrue();
            assertThat(byName.get("ts_zoned").canonicalType()).isEqualTo(CanonicalType.TIMESTAMP_TZ);
            assertThat(byName.get("ts_plain").canonicalType()).isEqualTo(CanonicalType.TIMESTAMP);
        }
    }

    private static void seed(PostgreSQLContainer<?> container) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", container.getUsername());
        props.setProperty("password", container.getPassword());

        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), props);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_demo (
                        id        bigserial PRIMARY KEY,
                        label     varchar(64),
                        ts_plain  timestamp,
                        ts_zoned  timestamptz
                    )
                    """);
        }
    }
}
