package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.support.LocalDatabases;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL 连接器对<b>真实数据库</b>的端到端验证。
 *
 * <p>这个测试是 P1 完成判据「通过连通性测试、浏览库表结构」的实际证据。
 * 它不 mock 任何东西:真的建库建表、真的走 JDBC、真的读 {@link java.sql.DatabaseMetaData}。
 *
 * <p>目标库不可用时整类跳过(见 {@link LocalDatabases}),不会让没有数据库的
 * 环境构建失败。
 */
@DisplayName("PostgreSQL 连接器 — 真实数据库端到端")
class PostgreSqlConnectorLiveIT {

    private static final String TEST_SCHEMA = "dg_probe_schema";
    private static final String TEST_TABLE = "probe_all_types";

    private final PostgreSqlConnector connector = new PostgreSqlConnector();
    private final ConnectionConfig config = LocalDatabases.postgresConfig();

    @BeforeAll
    static void prepareFixture() throws SQLException {
        assumeTrue(LocalDatabases.postgresAvailable(),
                "目标 PostgreSQL 不可用,跳过真实数据库测试(可用 -Ddg.test.pg.* 指定)");

        try (Connection connection = LocalDatabases.openPostgres();
             Statement statement = connection.createStatement()) {

            statement.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + TEST_SCHEMA);

            // 这张表刻意铺满类型映射里最容易出错的几种情况
            statement.execute("""
                    CREATE TABLE %s.%s (
                        id           bigserial PRIMARY KEY,
                        name         varchar(100) NOT NULL,
                        flag         boolean,
                        amount       numeric(12,2),
                        ratio        double precision,
                        ts_plain     timestamp,
                        ts_zoned     timestamptz,
                        only_date    date,
                        payload      jsonb,
                        tags         text[],
                        raw_bytes    bytea,
                        note         text
                    )
                    """.formatted(TEST_SCHEMA, TEST_TABLE));

            statement.execute("COMMENT ON TABLE %s.%s IS '类型映射探针表'"
                    .formatted(TEST_SCHEMA, TEST_TABLE));
            statement.execute("COMMENT ON COLUMN %s.%s.name IS '名称'"
                    .formatted(TEST_SCHEMA, TEST_TABLE));

            statement.execute("CREATE VIEW %s.probe_view AS SELECT id, name FROM %s.%s"
                    .formatted(TEST_SCHEMA, TEST_SCHEMA, TEST_TABLE));
        }
    }

    @Test
    @DisplayName("连通性测试成功并返回真实服务端版本与耗时")
    void testConnectionSucceedsAgainstRealServer() {
        ConnectivityResult result = connector.testConnection(DataSourceType.POSTGRESQL, config);

        assertThat(result.success()).as("应连通成功: %s", result.message()).isTrue();
        assertThat(result.serverVersion()).containsIgnoringCase("PostgreSQL");
        assertThat(result.errorCode()).isNull();
        assertThat(result.latencyMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("口令错误归类为认证失败,而不是笼统的连接失败")
    void wrongPasswordIsClassifiedAsAuthFailure() {
        ConnectionConfig wrong = ConnectionConfig.builder()
                .host(LocalDatabases.PG_HOST)
                .port(LocalDatabases.PG_PORT)
                .database(LocalDatabases.PG_DATABASE)
                .username(LocalDatabases.PG_USER)
                .password("definitely-not-the-password")
                .connectTimeoutMillis(5_000)
                .build();

        ConnectivityResult result = connector.testConnection(DataSourceType.POSTGRESQL, wrong);

        // 区分认证失败与网络不通是有实际价值的:两者对应完全不同的排查动作
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().code()).isEqualTo("DAT_AUTH_FAILED");
        assertThat(result.message()).contains("认证失败");
    }

    @Test
    @DisplayName("端口不可达时返回失败结果而不抛异常")
    void unreachablePortReturnsFailureNotException() {
        ConnectionConfig unreachable = ConnectionConfig.builder()
                .host(LocalDatabases.PG_HOST)
                .port(1)                       // 1 号端口不会有 PostgreSQL
                .database("whatever")
                .username("u").password("p")
                .connectTimeoutMillis(3_000)
                .build();

        ConnectivityResult result = connector.testConnection(DataSourceType.POSTGRESQL, unreachable);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isNotNull();
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    @DisplayName("逐层下钻:库 → 模式 → 表 → 列")
    void browsesCatalogHierarchy() {
        // 层级 1:库
        List<DatabaseInfo> databases = connector.listDatabases(DataSourceType.POSTGRESQL, config);
        assertThat(databases).extracting(DatabaseInfo::name)
                .contains(LocalDatabases.PG_DATABASE)
                .doesNotContain("template0", "template1");

        // 层级 2:模式(系统模式必须被过滤掉)
        List<SchemaInfo> schemas = connector.listSchemas(DataSourceType.POSTGRESQL, config,
                CatalogPath.ofDatabase(LocalDatabases.PG_DATABASE));
        assertThat(schemas).extracting(SchemaInfo::name)
                .contains(TEST_SCHEMA)
                .doesNotContain("pg_catalog", "information_schema", "pg_toast");

        // 层级 3:表与视图
        List<TableInfo> tables = connector.listTables(DataSourceType.POSTGRESQL, config,
                CatalogPath.ofSchema(LocalDatabases.PG_DATABASE, TEST_SCHEMA));
        assertThat(tables).extracting(TableInfo::name).contains(TEST_TABLE, "probe_view");

        TableInfo probeTable = tables.stream()
                .filter(t -> t.name().equals(TEST_TABLE)).findFirst().orElseThrow();
        assertThat(probeTable.comment()).isEqualTo("类型映射探针表");
    }

    @Test
    @DisplayName("字段规范化类型正确,尤其是带时区与不带时区的时间戳必须区分")
    void mapsColumnTypesToCanonicalTypes() {
        List<ColumnInfo> columns = connector.listColumns(DataSourceType.POSTGRESQL, config,
                CatalogPath.ofTable(LocalDatabases.PG_DATABASE, TEST_SCHEMA, TEST_TABLE));

        Map<String, ColumnInfo> byName = columns.stream()
                .collect(Collectors.toMap(ColumnInfo::name, Function.identity()));

        assertThat(byName.get("id").canonicalType()).isEqualTo(CanonicalType.BIGINT);
        assertThat(byName.get("id").primaryKey()).isTrue();

        assertThat(byName.get("name").canonicalType()).isEqualTo(CanonicalType.VARCHAR);
        assertThat(byName.get("name").nullable()).isFalse();
        assertThat(byName.get("name").comment()).isEqualTo("名称");

        assertThat(byName.get("flag").canonicalType()).isEqualTo(CanonicalType.BOOLEAN);

        assertThat(byName.get("amount").canonicalType()).isEqualTo(CanonicalType.DECIMAL);
        assertThat(byName.get("amount").precision()).isEqualTo(12);
        assertThat(byName.get("amount").scale()).isEqualTo(2);

        assertThat(byName.get("ratio").canonicalType()).isEqualTo(CanonicalType.DOUBLE);
        assertThat(byName.get("only_date").canonicalType()).isEqualTo(CanonicalType.DATE);

        // 这一对是跨库同步最容易静默出事的地方:时区语义丢失不会报错,只会算错
        assertThat(byName.get("ts_plain").canonicalType()).isEqualTo(CanonicalType.TIMESTAMP);
        assertThat(byName.get("ts_zoned").canonicalType()).isEqualTo(CanonicalType.TIMESTAMP_TZ);

        assertThat(byName.get("payload").canonicalType()).isEqualTo(CanonicalType.JSON);
        assertThat(byName.get("tags").canonicalType()).isEqualTo(CanonicalType.ARRAY);
        assertThat(byName.get("raw_bytes").canonicalType()).isEqualTo(CanonicalType.BLOB);
        assertThat(byName.get("note").canonicalType()).isEqualTo(CanonicalType.TEXT);

        // 原始类型名必须保留 —— 它是排查错误映射时唯一的证据
        assertThat(byName.get("ts_zoned").rawType()).isNotBlank();
        assertThat(columns).allSatisfy(c -> assertThat(c.rawType()).isNotBlank());
    }

    @Test
    @DisplayName("切库下钻:连接配置指向 A 库时仍能列出 B 库的模式")
    void switchesDatabaseWhenDrillingIntoAnother() {
        // 连接配置指向 dg_probe,但要求下钻 datagovernance 库。
        // PostgreSQL 一个连接只能看一个库,连接器必须在内部重连 —— 这正是
        // configForPath 存在的理由,也是不该泄漏给 Metadata Space 的引擎细节。
        List<DatabaseInfo> databases = connector.listDatabases(DataSourceType.POSTGRESQL, config);
        String otherDatabase = databases.stream()
                .map(DatabaseInfo::name)
                .filter(name -> !name.equals(LocalDatabases.PG_DATABASE))
                .findFirst()
                .orElse(null);
        assumeTrue(otherDatabase != null, "只有一个库,无法验证切库");

        List<SchemaInfo> schemas = connector.listSchemas(DataSourceType.POSTGRESQL, config,
                CatalogPath.ofDatabase(otherDatabase));

        assertThat(schemas).isNotNull();
        assertThat(schemas).extracting(SchemaInfo::name).doesNotContain("pg_catalog");
    }
}
