package com.datagov.data.connector.jdbc;

import com.datagov.common.error.BizException;
import com.datagov.data.connector.support.LocalDatabases;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.query.SqlQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 自定义 SQL 查询对<b>真实 PostgreSQL</b> 的端到端验证(功能 7)。
 *
 * <p>护栏的单元测试在 {@code ReadOnlySqlGuardTest};这里验证的是护栏之外的部分:
 * 行数上限真的生效、超时真的设上、结果类型与结构浏览用的是同一套映射。
 */
@DisplayName("自定义 SQL 查询 — 真实数据库")
class SqlQueryLiveIT {

    private static final String SCHEMA = "dg_query_schema";

    private final PostgreSqlConnector connector = new PostgreSqlConnector();
    private final ConnectionConfig config = LocalDatabases.postgresConfig();

    @BeforeAll
    static void prepare() throws SQLException {
        assumeTrue(LocalDatabases.postgresAvailable(), "目标 PostgreSQL 不可用,跳过");

        try (Connection connection = LocalDatabases.openPostgres();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("""
                    CREATE TABLE %s.rows_demo (
                        id       int PRIMARY KEY,
                        label    varchar(32),
                        amount   numeric(10,2),
                        ts_zoned timestamptz
                    )
                    """.formatted(SCHEMA));
            // 插 50 行,用来验证 maxRows 截断
            statement.execute("""
                    INSERT INTO %s.rows_demo
                    SELECT g, 'row-' || g, g * 1.5, now()
                    FROM generate_series(1, 50) AS g
                    """.formatted(SCHEMA));
        }
    }

    @Test
    @DisplayName("查询返回列元信息与行数据")
    void returnsColumnsAndRows() {
        SqlQuery.Result result = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("SELECT id, label, amount, ts_zoned FROM %s.rows_demo ORDER BY id LIMIT 3"
                        .formatted(SCHEMA)));

        assertThat(result.columns()).extracting(SqlQuery.Column::name)
                .containsExactly("id", "label", "amount", "ts_zoned");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.rows().get(0)).element(1).isEqualTo("row-1");
        assertThat(result.truncated()).isFalse();
        assertThat(result.elapsedMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("结果列的规范类型与结构浏览一致")
    void columnTypesMatchCatalogBrowsing() {
        // 同一列在"浏览表结构"和"查询结果"里必须显示同一个类型,
        // 否则用户会怀疑其中一个是错的
        SqlQuery.Result result = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("SELECT id, amount, ts_zoned FROM %s.rows_demo LIMIT 1".formatted(SCHEMA)));

        assertThat(result.columns()).extracting(SqlQuery.Column::canonicalType)
                .containsExactly(CanonicalType.INT, CanonicalType.DECIMAL, CanonicalType.TIMESTAMP_TZ);
    }

    @Test
    @DisplayName("超过 maxRows 时截断,并如实告知")
    void truncatesAndReportsIt() {
        SqlQuery.Result result = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                new SqlQuery.Request("SELECT * FROM %s.rows_demo".formatted(SCHEMA), 10, 30));

        assertThat(result.rowCount()).isEqualTo(10);
        // 不告知截断的话,用户看到 10 行会以为表里就这么多,并据此得出错误结论
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("恰好等于上限时不误报截断")
    void exactLimitIsNotReportedAsTruncated() {
        SqlQuery.Result result = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                new SqlQuery.Request("SELECT * FROM %s.rows_demo ORDER BY id LIMIT 10".formatted(SCHEMA), 10, 30));

        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("写语句在真实连接上也被拒 —— 护栏先于执行")
    void writeStatementRejectedBeforeExecution() {
        assertThatThrownBy(() -> connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("DELETE FROM %s.rows_demo".formatted(SCHEMA))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("DAT_SQL_NOT_ALLOWED"));

        // 确认数据没被动过
        SqlQuery.Result count = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("SELECT count(*) AS c FROM %s.rows_demo".formatted(SCHEMA)));
        assertThat(count.rows().get(0).get(0)).isEqualTo("50");
    }

    @Test
    @DisplayName("SQL 语法错误时把数据库的原话带回来")
    void syntaxErrorSurfacesDatabaseMessage() {
        // 一句"执行失败"对写错 SQL 的用户毫无帮助
        assertThatThrownBy(() -> connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("SELECT * FROM %s.no_such_table".formatted(SCHEMA))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("查询执行失败")
                .hasMessageContaining("no_such_table");
    }

    @Test
    @DisplayName("NULL 值如实返回 null,不变成字符串 \"null\"")
    void nullsAreRealNulls() {
        SqlQuery.Result result = connector.executeQuery(DataSourceType.POSTGRESQL, config,
                SqlQuery.Request.of("SELECT NULL::varchar AS empty_col"));

        assertThat(result.rows().get(0).get(0)).isNull();
    }
}
