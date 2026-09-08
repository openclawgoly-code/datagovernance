package com.datagov.data.connector.ddl;

import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.ddl.TableDdl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 建表语句生成(功能 9)。
 *
 * <p>不需要任何目标端 —— 生成是纯字符串操作。这也是把生成与执行分成两个方法的
 * 好处之一:最容易出错的那一半可以被完整测到。
 */
@DisplayName("建表语句生成")
class DialectDdlTest {

    private static TableDdl.CreateTableSpec spec(TableDdl.ColumnSpec... columns) {
        return new TableDdl.CreateTableSpec("appdb", null, "orders",
                List.of(columns), List.of("id"), "订单表", Map.of());
    }

    private static TableDdl.ColumnSpec column(String name, CanonicalType type) {
        return new TableDdl.ColumnSpec(name, type, null, null, true, null);
    }

    private static TableDdl.ColumnSpec column(String name, CanonicalType type,
                                              Integer precision, Integer scale) {
        return new TableDdl.ColumnSpec(name, type, precision, scale, true, null);
    }

    @Nested
    @DisplayName("方言语法")
    class Dialects {

        @Test
        @DisplayName("MySQL:反引号、utf8mb4、内联注释")
        void mysql() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.MYSQL,
                    spec(column("id", CanonicalType.BIGINT),
                            new TableDdl.ColumnSpec("name", CanonicalType.VARCHAR, 64, null,
                                    true, "客户名")));

            String sql = ddl.asScript();
            assertThat(sql).contains("CREATE TABLE `appdb`.`orders`");
            assertThat(sql).contains("`id` BIGINT NOT NULL");     // 主键强制 NOT NULL
            assertThat(sql).contains("`name` VARCHAR(64) COMMENT '客户名'");
            assertThat(sql).contains("PRIMARY KEY (`id`)");
            // utf8 只有三字节,存不下 emoji 与部分生僻汉字
            assertThat(sql).contains("CHARSET=utf8mb4");
        }

        @Test
        @DisplayName("PostgreSQL:注释是独立语句,所以会返回多条")
        void postgres() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.POSTGRESQL,
                    spec(column("id", CanonicalType.BIGINT),
                            new TableDdl.ColumnSpec("name", CanonicalType.VARCHAR, 64, null,
                                    true, "客户名")));

            assertThat(ddl.statements()).hasSize(3);   // 建表 + 表注释 + 列注释
            assertThat(ddl.statements().get(0)).contains("CREATE TABLE \"appdb\".\"orders\"");
            assertThat(ddl.statements().get(1)).startsWith("COMMENT ON TABLE");
            assertThat(ddl.statements().get(2)).startsWith("COMMENT ON COLUMN");
        }

        @Test
        @DisplayName("Doris:必须声明数据模型、分桶与副本数")
        void doris() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.DORIS,
                    spec(column("id", CanonicalType.BIGINT), column("name", CanonicalType.VARCHAR)));

            String sql = ddl.asScript();
            assertThat(sql).contains("UNIQUE KEY(`id`)");
            assertThat(sql).contains("DISTRIBUTED BY HASH(`id`) BUCKETS 10");
            assertThat(sql).contains("\"replication_num\" = \"1\"");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("replication_num"));
        }

        @Test
        @DisplayName("Doris 无主键时用明细模型,并提醒不会去重")
        void dorisWithoutPrimaryKey() {
            TableDdl.CreateTableSpec noKey = new TableDdl.CreateTableSpec(
                    "dw", null, "events",
                    List.of(column("ts", CanonicalType.TIMESTAMP), column("payload", CanonicalType.TEXT)),
                    List.of(), null, Map.of());

            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.DORIS, noKey);
            assertThat(ddl.asScript()).contains("DUPLICATE KEY(`ts`)");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("不会去重"));
        }

        @Test
        @DisplayName("Doris 的选项可以被覆盖")
        void dorisOptionsAreOverridable() {
            TableDdl.CreateTableSpec withOptions = new TableDdl.CreateTableSpec(
                    "dw", null, "orders", List.of(column("id", CanonicalType.BIGINT)),
                    List.of("id"), null, Map.of("buckets", "32", "replication_num", "3"));

            String sql = DialectDdl.generate(DataSourceType.DORIS, withOptions).asScript();
            assertThat(sql).contains("BUCKETS 32");
            assertThat(sql).contains("\"replication_num\" = \"3\"");
        }

        @Test
        @DisplayName("SQL Server 用方括号引用标识符")
        void sqlServer() {
            String sql = DialectDdl.generate(DataSourceType.SQLSERVER,
                    spec(column("id", CanonicalType.BIGINT))).asScript();
            assertThat(sql).contains("[appdb].[orders]");
        }

        @ParameterizedTest(name = "{0} 能生成建表语句")
        @EnumSource(value = DataSourceType.class,
                names = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "DAMENG", "DORIS", "STARROCKS"})
        @DisplayName("七种关系型/MPP 类型都能生成")
        void allRelationalTypesGenerate(DataSourceType type) {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(type,
                    spec(column("id", CanonicalType.BIGINT), column("name", CanonicalType.VARCHAR)));
            assertThat(ddl.statements()).isNotEmpty();
            assertThat(ddl.statements().get(0)).startsWith("CREATE TABLE");
        }

        @Test
        @DisplayName("文件型/接口型没有表可建,直接报错")
        void nonRelationalTypesAreRejected() {
            assertThatThrownBy(() -> DialectDdl.generate(DataSourceType.FTP,
                    spec(column("id", CanonicalType.BIGINT))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不支持建表");
        }
    }

    @Nested
    @DisplayName("类型映射(R7 的写出方向)")
    class TypeMapping {

        @Test
        @DisplayName("Oracle 没有 BOOLEAN,降级为 NUMBER(1) 并提醒")
        void oracleHasNoBoolean() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.ORACLE,
                    spec(column("id", CanonicalType.BIGINT), column("flag", CanonicalType.BOOLEAN)));
            assertThat(ddl.asScript()).contains("NUMBER(1)");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("BOOLEAN"));
        }

        @Test
        @DisplayName("Oracle 的 VARCHAR2 超过 4000 时改用 CLOB")
        void oracleLongVarcharBecomesClob() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.ORACLE,
                    spec(column("id", CanonicalType.BIGINT),
                            column("body", CanonicalType.VARCHAR, 8000, null)));
            assertThat(ddl.asScript()).contains("CLOB");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("VARCHAR2 上限"));
        }

        @Test
        @DisplayName("Doris 的 VARCHAR 按字节算,中文列长度放大三倍")
        void dorisVarcharAccountsForBytes() {
            // 「姓名 VARCHAR(10)」在 Doris 上只能存三个汉字 —— 不放大就会截断
            String sql = DialectDdl.generate(DataSourceType.DORIS,
                    spec(column("id", CanonicalType.BIGINT),
                            column("name", CanonicalType.VARCHAR, 10, null))).asScript();
            assertThat(sql).contains("VARCHAR(30)");
        }

        @Test
        @DisplayName("MySQL 没有带时区的时间戳,降级时必须提醒 —— 时区会丢")
        void mysqlLosesTimezone() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.MYSQL,
                    spec(column("id", CanonicalType.BIGINT),
                            column("created_at", CanonicalType.TIMESTAMP_TZ)));
            assertThat(ddl.asScript()).contains("DATETIME");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("时区信息会丢失"));
        }

        @Test
        @DisplayName("PostgreSQL 保留时区")
        void postgresKeepsTimezone() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.POSTGRESQL,
                    spec(column("id", CanonicalType.BIGINT),
                            column("created_at", CanonicalType.TIMESTAMP_TZ)));
            assertThat(ddl.asScript()).contains("TIMESTAMP WITH TIME ZONE");
            assertThat(ddl.warnings()).noneMatch(w -> w.contains("时区"));
        }

        @Test
        @DisplayName("DECIMAL 缺精度时给默认值并提醒")
        void decimalWithoutPrecisionWarns() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.MYSQL,
                    spec(column("id", CanonicalType.BIGINT), column("amount", CanonicalType.DECIMAL)));
            assertThat(ddl.asScript()).contains("DECIMAL(18,4)");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("未给出精度"));
        }

        @Test
        @DisplayName("UNKNOWN 退化为 VARCHAR 并要求人工确认")
        void unknownTypeRequiresHumanReview() {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.MYSQL,
                    spec(column("id", CanonicalType.BIGINT), column("weird", CanonicalType.UNKNOWN)));
            assertThat(ddl.asScript()).contains("VARCHAR(255)");
            assertThat(ddl.warnings()).anyMatch(w -> w.contains("人工确认"));
        }

        @ParameterizedTest(name = "MySQL 能渲染 {0}")
        @EnumSource(CanonicalType.class)
        @DisplayName("每一种 CanonicalType 都渲染得出来 —— 不留漏网的类型")
        void everyCanonicalTypeRenders(CanonicalType type) {
            TableDdl.GeneratedDdl ddl = DialectDdl.generate(DataSourceType.MYSQL,
                    spec(column("id", CanonicalType.BIGINT), column("value", type)));
            assertThat(ddl.statements()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("标识符里的引用符被转义 —— 否则带反引号的表名能拼出任意 SQL")
    void identifiersAreEscaped() {
        assertThat(DialectDdl.quote(DataSourceType.MYSQL, "we`ird")).isEqualTo("`we``ird`");
        assertThat(DialectDdl.quote(DataSourceType.POSTGRESQL, "we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(DialectDdl.quote(DataSourceType.SQLSERVER, "we]ird")).isEqualTo("[we]]ird]");
    }

    @Test
    @DisplayName("没有列时给出明确的警告,而不是生成一条语法错误的语句")
    void emptyColumnsWarn() {
        TableDdl.CreateTableSpec empty = new TableDdl.CreateTableSpec(
                "db", null, "t", List.of(), List.of(), null, Map.of());
        assertThat(DialectDdl.generate(DataSourceType.MYSQL, empty).warnings())
                .anyMatch(w -> w.contains("没有任何列"));
    }
}
