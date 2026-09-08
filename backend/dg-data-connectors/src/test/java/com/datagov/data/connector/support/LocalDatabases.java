package com.datagov.data.connector.support;

import com.datagov.data.spi.ConnectionConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 真实数据库测试的连接参数解析。
 *
 * <p><b>为什么不用 Testcontainers 作为主路径</b>:本项目的开发容器允许运行
 * Docker 守护进程,但镜像仓库被网络策略封禁,拉不到镜像。用一台外部真实
 * PostgreSQL 反而更可靠,也更贴近实际部署 —— 探测代码面对的是真的
 * {@code DatabaseMetaData},不是模拟对象。
 *
 * <p>参数来源(优先级从高到低):系统属性 → 环境变量 → 默认值。
 * 连不上时测试<b>跳过而非失败</b> —— 没有数据库的环境里,连接器的契约测试
 * 仍然应该能跑。
 */
public final class LocalDatabases {

    public static final String PG_HOST = resolve("dg.test.pg.host", "DG_TEST_PG_HOST", "127.0.0.1");
    public static final int PG_PORT = Integer.parseInt(
            resolve("dg.test.pg.port", "DG_TEST_PG_PORT", "55432"));
    public static final String PG_DATABASE = resolve("dg.test.pg.database", "DG_TEST_PG_DATABASE", "dg_probe");
    public static final String PG_USER = resolve("dg.test.pg.user", "DG_TEST_PG_USER", "postgres");
    public static final String PG_PASSWORD = resolve("dg.test.pg.password", "DG_TEST_PG_PASSWORD", "postgres");

    private LocalDatabases() {
    }

    public static ConnectionConfig postgresConfig() {
        return ConnectionConfig.builder()
                .host(PG_HOST)
                .port(PG_PORT)
                .database(PG_DATABASE)
                .username(PG_USER)
                .password(PG_PASSWORD)
                .connectTimeoutMillis(5_000)
                .readTimeoutMillis(15_000)
                .build();
    }

    public static String postgresJdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(PG_HOST, PG_PORT, PG_DATABASE);
    }

    /** 供 {@code Assumptions.assumeTrue} 使用:目标 PostgreSQL 是否真的可用。 */
    public static boolean postgresAvailable() {
        try (Connection ignored = openPostgres()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static Connection openPostgres() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        props.setProperty("password", PG_PASSWORD);
        props.setProperty("connectTimeout", "5");
        return DriverManager.getConnection(postgresJdbcUrl(), props);
    }

    private static String resolve(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(environmentVariable);
        return value != null && !value.isBlank() ? value : fallback;
    }
}
