package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.file.FtpConnector;
import com.datagov.data.connector.file.SftpConnector;
import com.datagov.data.connector.http.RestApiConnector;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器契约测试 —— 不需要真实目标端就能验证的部分。
 *
 * <p>对 Oracle / SQLServer / 达梦 / Doris / FTP / SFTP 这些本环境没有实例的类型,
 * 这是它们唯一的自动化保障。覆盖三件事:驱动能加载、URL 拼装正确、
 * 面对不可达目标时<b>返回失败结果而不是抛异常</b>。
 *
 * <p>最后一条是 SPI 的硬约定:调用方无需 try/catch 就能把结果直接呈现给用户。
 * 任何一个连接器违约,这里就会红。
 */
@DisplayName("连接器契约")
class ConnectorContractTest {

    // ── 驱动可用性 ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} 的驱动可加载")
    @EnumSource(value = DataSourceType.class,
            names = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "DAMENG", "DORIS", "STARROCKS"})
    @DisplayName("所有 JDBC 类型的驱动都在 classpath 上")
    void jdbcDriversArePresent(DataSourceType type) throws ClassNotFoundException {
        // 达梦驱动尤其值得断言:信创环境里它常常需要现场安装,
        // 一旦缺失,平台应当在类型列表里就体现出来,而不是等用户点了测试连接才报错。
        assertThat(type.driverClassName()).isNotNull();
        assertThat(Class.forName(type.driverClassName())).isNotNull();
    }

    @ParameterizedTest(name = "{0} 无 JDBC 驱动概念")
    @EnumSource(value = DataSourceType.class, names = {"FTP", "SFTP", "REST_API"})
    @DisplayName("非 JDBC 类型不声明驱动")
    void nonJdbcTypesHaveNoDriver(DataSourceType type) {
        assertThat(type.isJdbc()).isFalse();
        assertThat(type.driverClassName()).isNull();
    }

    // ── URL 拼装 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("各方言的 JDBC URL 拼装")
    void buildsDialectSpecificUrls() {
        ConnectionConfig config = ConnectionConfig.builder()
                .host("db.internal").port(1234).database("appdb").build();

        assertThat(new MySqlConnector().buildJdbcUrl(DataSourceType.MYSQL, config))
                .isEqualTo("jdbc:mysql://db.internal:1234/appdb");

        assertThat(new PostgreSqlConnector().buildJdbcUrl(DataSourceType.POSTGRESQL, config))
                .isEqualTo("jdbc:postgresql://db.internal:1234/appdb");

        // Oracle 用服务名写法而非老式的 host:port:SID
        assertThat(new OracleConnector().buildJdbcUrl(DataSourceType.ORACLE, config))
                .isEqualTo("jdbc:oracle:thin:@//db.internal:1234/appdb");

        assertThat(new SqlServerConnector().buildJdbcUrl(DataSourceType.SQLSERVER, config))
                .isEqualTo("jdbc:sqlserver://db.internal:1234;databaseName=appdb");

        // 达梦的 URL 里不带库名 —— 库的概念由 schema 承担
        assertThat(new DamengConnector().buildJdbcUrl(DataSourceType.DAMENG, config))
                .isEqualTo("jdbc:dm://db.internal:1234");

        // Doris 走 FE 的 MySQL 协议端口
        assertThat(new DorisConnector().buildJdbcUrl(DataSourceType.DORIS, config))
                .isEqualTo("jdbc:mysql://db.internal:1234/appdb");
    }

    @Test
    @DisplayName("未配置端口时回落到该类型的默认端口")
    void fallsBackToDefaultPort() {
        ConnectionConfig noPort = ConnectionConfig.builder().host("h").database("d").build();

        assertThat(new MySqlConnector().buildJdbcUrl(DataSourceType.MYSQL, noPort))
                .contains(":3306/");
        assertThat(new PostgreSqlConnector().buildJdbcUrl(DataSourceType.POSTGRESQL, noPort))
                .contains(":5432/");
        assertThat(new DamengConnector().buildJdbcUrl(DataSourceType.DAMENG, noPort))
                .endsWith(":5236");
        assertThat(new DorisConnector().buildJdbcUrl(DataSourceType.DORIS, noPort))
                .contains(":9030/");
    }

    @Test
    @DisplayName("PostgreSQL 未指定库时连默认库,而不是让驱动拿用户名当库名")
    void postgresDefaultsToPostgresDatabase() {
        ConnectionConfig noDatabase = ConnectionConfig.builder().host("h").port(5432).build();
        assertThat(new PostgreSqlConnector().buildJdbcUrl(DataSourceType.POSTGRESQL, noDatabase))
                .isEqualTo("jdbc:postgresql://h:5432/postgres");
    }

    @Test
    @DisplayName("Oracle 服务名前的斜杠会被规整掉")
    void oracleNormalizesLeadingSlashInService() {
        ConnectionConfig withSlash = ConnectionConfig.builder()
                .host("h").port(1521).database("/ORCLPDB").build();
        assertThat(new OracleConnector().buildJdbcUrl(DataSourceType.ORACLE, withSlash))
                .isEqualTo("jdbc:oracle:thin:@//h:1521/ORCLPDB");
    }

    // ── 能力声明 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("能力声明与引擎现实一致 —— UI 据此决定画几级树")
    void capabilitiesMatchEngineReality() {
        // 三级树
        for (var pair : List.of(
                new Object[]{new PostgreSqlConnector(), DataSourceType.POSTGRESQL},
                new Object[]{new OracleConnector(), DataSourceType.ORACLE},
                new Object[]{new SqlServerConnector(), DataSourceType.SQLSERVER},
                new Object[]{new DamengConnector(), DataSourceType.DAMENG})) {
            ConnectorCapabilities capabilities =
                    ((DataSourceConnector) pair[0]).capabilities((DataSourceType) pair[1]);
            assertThat(capabilities.hasSchemaLevel())
                    .as("%s 应有模式层", pair[1]).isTrue();
            assertThat(capabilities.canBrowseCatalog()).isTrue();
        }

        // 两级树:MySQL 的 database 与 schema 是同一个东西
        assertThat(new MySqlConnector().capabilities(DataSourceType.MYSQL).hasSchemaLevel()).isFalse();
        assertThat(new DorisConnector().capabilities(DataSourceType.DORIS).hasSchemaLevel()).isFalse();
        assertThat(new DorisConnector().capabilities(DataSourceType.STARROCKS).hasSchemaLevel()).isFalse();

        // 文件型:能浏览目录,不能浏览库表
        ConnectorCapabilities ftp = new FtpConnector().capabilities(DataSourceType.FTP);
        assertThat(ftp.canBrowseFiles()).isTrue();
        assertThat(ftp.canBrowseCatalog()).isFalse();

        // 接口型:只能验通
        ConnectorCapabilities rest = new RestApiConnector().capabilities(DataSourceType.REST_API);
        assertThat(rest.canTestConnection()).isTrue();
        assertThat(rest.canBrowseCatalog()).isFalse();
        assertThat(rest.canBrowseFiles()).isFalse();
    }

    @Test
    @DisplayName("Doris 与 StarRocks 由同一个连接器覆盖")
    void dorisConnectorCoversBothTypes() {
        assertThat(new DorisConnector().supportedTypes())
                .containsExactlyInAnyOrder(DataSourceType.DORIS, DataSourceType.STARROCKS);
    }

    // ── 不可达目标的行为 ────────────────────────────────────────────────

    static Stream<Object[]> allConnectors() {
        return Stream.of(
                new Object[]{new MySqlConnector(), DataSourceType.MYSQL},
                new Object[]{new PostgreSqlConnector(), DataSourceType.POSTGRESQL},
                new Object[]{new OracleConnector(), DataSourceType.ORACLE},
                new Object[]{new SqlServerConnector(), DataSourceType.SQLSERVER},
                new Object[]{new DamengConnector(), DataSourceType.DAMENG},
                new Object[]{new DorisConnector(), DataSourceType.DORIS},
                new Object[]{new FtpConnector(), DataSourceType.FTP},
                new Object[]{new SftpConnector(), DataSourceType.SFTP},
                new Object[]{new RestApiConnector(), DataSourceType.REST_API});
    }

    @ParameterizedTest(name = "{1} 面对不可达目标返回失败结果而非抛异常")
    @MethodSource("allConnectors")
    @DisplayName("testConnection 永不抛异常 —— 这是 SPI 的硬约定")
    void testConnectionNeverThrows(DataSourceConnector connector, DataSourceType type) {
        // 127.0.0.1:1 上不会有任何服务在监听
        ConnectionConfig unreachable = ConnectionConfig.builder()
                .host("127.0.0.1")
                .port(1)
                .database("nope")
                .username("u")
                .password("p")
                .baseUrl("http://127.0.0.1:1/health")
                .connectTimeoutMillis(2_000)
                .readTimeoutMillis(2_000)
                .build();

        ConnectivityResult result = connector.testConnection(type, unreachable);

        assertThat(result).as("%s 必须返回结果对象", type).isNotNull();
        assertThat(result.success()).as("%s 不该连上 127.0.0.1:1", type).isFalse();
        assertThat(result.errorCode()).as("%s 失败时必须给出错误码", type).isNotNull();
        assertThat(result.message()).as("%s 失败时必须给出可读结论", type).isNotBlank();
    }

    @Test
    @DisplayName("连接配置的 toString 与 masked 都不泄露口令")
    void connectionConfigNeverLeaksPassword() {
        ConnectionConfig config = ConnectionConfig.builder()
                .host("h").port(1).username("admin").password("super-secret-value").build();

        // 依赖"记得脱敏"是不可靠的,所以 toString 本身就被覆写成脱敏视图
        assertThat(config.toString()).doesNotContain("super-secret-value").contains("******");
        assertThat(config.masked()).doesNotContain("super-secret-value");
    }
}
