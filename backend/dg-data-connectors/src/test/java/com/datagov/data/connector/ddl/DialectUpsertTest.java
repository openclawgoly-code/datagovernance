package com.datagov.data.connector.ddl;

import com.datagov.data.spi.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按主键写入的语句生成(功能 11 的 UPSERT 写入模式)。
 *
 * <p>与 {@link DialectDdlTest} 同一个理由:生成是纯字符串操作,不需要任何目标端,
 * 所以最容易出错的那一半可以被完整测到 —— 而这一半里有六种互不相同的写法,
 * 其中三种(Oracle / SQL Server / 达梦)在本仓库的验证环境里根本没有实例可跑。
 * 那三种<b>只有</b>这里的断言看着,所以断言写得比别处细。
 */
@DisplayName("按主键写入的语句生成")
class DialectUpsertTest {

    private static final List<String> COLUMNS = List.of("id", "name", "amount");
    private static final List<String> KEYS = List.of("id");

    private static String sql(DataSourceType type) {
        return DialectUpsert.generate(type, table(type), COLUMNS, KEYS).sql();
    }

    private static String table(DataSourceType type) {
        return DialectDdl.quote(type, "orders");
    }

    @Nested
    @DisplayName("方言语法")
    class Dialects {

        @Test
        @DisplayName("PostgreSQL 用 ON CONFLICT DO UPDATE,以 EXCLUDED 取待插入的值")
        void postgres() {
            assertThat(sql(DataSourceType.POSTGRESQL)).isEqualTo(
                    "INSERT INTO \"orders\" (\"id\", \"name\", \"amount\") VALUES (?, ?, ?)"
                            + " ON CONFLICT (\"id\")"
                            + " DO UPDATE SET \"name\" = EXCLUDED.\"name\","
                            + " \"amount\" = EXCLUDED.\"amount\"");
        }

        @Test
        @DisplayName("MySQL 用 ON DUPLICATE KEY UPDATE,以 VALUES() 取待插入的值")
        void mysql() {
            assertThat(sql(DataSourceType.MYSQL)).isEqualTo(
                    "INSERT INTO `orders` (`id`, `name`, `amount`) VALUES (?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`),"
                            + " `amount` = VALUES(`amount`)");
        }

        @Test
        @DisplayName("Doris/StarRocks 写的是普通 INSERT —— 去重靠表模型,不靠语句")
        void doris() {
            for (DataSourceType type : List.of(DataSourceType.DORIS, DataSourceType.STARROCKS)) {
                DialectUpsert.UpsertStatement statement =
                        DialectUpsert.generate(type, table(type), COLUMNS, KEYS);
                assertThat(statement.sql())
                        .isEqualTo("INSERT INTO `orders` (`id`, `name`, `amount`) "
                                + "VALUES (?, ?, ?)")
                        .doesNotContain("DUPLICATE KEY UPDATE", "CONFLICT", "MERGE");
                // 语句合法但结果未必对 —— 这是唯一一种必须靠 warning 说清楚的情形
                assertThat(statement.warnings())
                        .anyMatch(w -> w.contains("表模型") && w.contains("明细模型"));
            }
        }

        @Test
        @DisplayName("Oracle 用 MERGE ... FROM dual,值只在 USING 里绑一次")
        void oracle() {
            assertThat(sql(DataSourceType.ORACLE)).isEqualTo(
                    "MERGE INTO \"orders\" tgt"
                            + " USING (SELECT ? AS \"id\", ? AS \"name\", ? AS \"amount\""
                            + " FROM dual) src"
                            + " ON (tgt.\"id\" = src.\"id\")"
                            + " WHEN MATCHED THEN UPDATE SET \"name\" = src.\"name\","
                            + " \"amount\" = src.\"amount\""
                            + " WHEN NOT MATCHED THEN INSERT (\"id\", \"name\", \"amount\")"
                            + " VALUES (src.\"id\", src.\"name\", src.\"amount\")");
        }

        @Test
        @DisplayName("达梦与 Oracle 同形 —— 连 dual 都有,所以共用一份实现")
        void dameng() {
            assertThat(sql(DataSourceType.DAMENG)).isEqualTo(sql(DataSourceType.ORACLE));
        }

        @Test
        @DisplayName("SQL Server 用 MERGE ... USING (VALUES ...),并以分号结尾")
        void sqlServer() {
            assertThat(sql(DataSourceType.SQLSERVER)).isEqualTo(
                    "MERGE INTO [orders] AS tgt"
                            + " USING (VALUES (?, ?, ?)) AS src ([id], [name], [amount])"
                            + " ON tgt.[id] = src.[id]"
                            + " WHEN MATCHED THEN UPDATE SET [name] = src.[name],"
                            + " [amount] = src.[amount]"
                            + " WHEN NOT MATCHED THEN INSERT ([id], [name], [amount])"
                            + " VALUES (src.[id], src.[name], src.[amount]);");
        }

        @Test
        @DisplayName("SQL Server 的 MERGE 少了结尾分号会报语法错,所以它是断言的一部分")
        void sqlServerNeedsTerminator() {
            assertThat(sql(DataSourceType.SQLSERVER)).endsWith(";");
            // 别的方言不该跟着加
            assertThat(sql(DataSourceType.ORACLE)).doesNotEndWith(";");
            assertThat(sql(DataSourceType.POSTGRESQL)).doesNotEndWith(";");
        }
    }

    @Nested
    @DisplayName("跨方言的共同约定")
    class Invariants {

        /**
         * 参数顺序恒等于 columns —— 调用方只有一套绑定顺序。
         *
         * <p>MERGE 之所以也能做到,是因为值先进 USING 成为一行"源",后面的
         * UPDATE 与 INSERT 都引用它而不再重复绑定。写错的话占位符会多出来,
         * 而多出来的占位符在批量执行时报的是"参数个数不符",离真正的原因很远。
         */
        @ParameterizedTest
        @EnumSource(value = DataSourceType.class,
                names = {"POSTGRESQL", "MYSQL", "DORIS", "STARROCKS", "ORACLE",
                        "SQLSERVER", "DAMENG"})
        @DisplayName("占位符个数恰好等于列数(每个值只绑一次)")
        void placeholderCountEqualsColumnCount(DataSourceType type) {
            String generated = sql(type);
            long placeholders = generated.chars().filter(c -> c == '?').count();
            assertThat(placeholders)
                    .as("方言 %s 的语句: %s", type, generated)
                    .isEqualTo(COLUMNS.size());
        }

        /**
         * 只看 UPDATE 的赋值段,不看整条语句。
         *
         * <p>整条语句里到处都有 {@code "id" = src."id"} —— MERGE 的 ON 子句就是
         * 这个形状。拿整条语句做 doesNotContain,断言永远是红的,而红的原因与
         * 要验的东西无关。第一版就是这么写的。
         */
        private String updateClause(DataSourceType type, String generated) {
            return switch (type) {
                case POSTGRESQL -> after(generated, "DO UPDATE SET ");
                case MYSQL -> after(generated, "ON DUPLICATE KEY UPDATE ");
                default -> {
                    String tail = after(generated, "WHEN MATCHED THEN UPDATE SET ");
                    int end = tail.indexOf(" WHEN NOT MATCHED");
                    yield end < 0 ? tail : tail.substring(0, end);
                }
            };
        }

        private String after(String text, String marker) {
            int at = text.indexOf(marker);
            assertThat(at).as("语句里找不到 %s: %s", marker, text).isGreaterThanOrEqualTo(0);
            return text.substring(at + marker.length());
        }

        @ParameterizedTest
        @EnumSource(value = DataSourceType.class,
                names = {"POSTGRESQL", "MYSQL", "ORACLE", "SQLSERVER", "DAMENG"})
        @DisplayName("主键列不出现在 UPDATE 的赋值里")
        void primaryKeyIsNeverUpdated(DataSourceType type) {
            // Oracle 明令禁止(ORA-38104:ON 子句里的列不能被更新),其余方言
            // 允许但没有意义 —— 更新主键等于把这一行变成另一行
            String clause = updateClause(type, sql(type));
            assertThat(clause)
                    .as("方言 %s 的 UPDATE 赋值段", type)
                    .doesNotContain(DialectDdl.quote(type, "id"))
                    .contains(DialectDdl.quote(type, "name"))
                    .contains(DialectDdl.quote(type, "amount"));
        }

        @ParameterizedTest
        @EnumSource(value = DataSourceType.class,
                names = {"POSTGRESQL", "MYSQL", "ORACLE", "SQLSERVER", "DAMENG"})
        @DisplayName("复合主键的条件用 AND 串起来,一个都不能少")
        void compositeKeys(DataSourceType type) {
            String generated = DialectUpsert.generate(type, table(type),
                    List.of("tenant", "id", "name"), List.of("tenant", "id")).sql();
            String tenant = DialectDdl.quote(type, "tenant");
            String id = DialectDdl.quote(type, "id");
            assertThat(generated).contains(tenant).contains(id);
            if (type == DataSourceType.POSTGRESQL) {
                assertThat(generated).contains("ON CONFLICT (" + tenant + ", " + id + ")");
            } else if (type != DataSourceType.MYSQL) {
                // MySQL 认的是唯一索引本身,语句里不列出键
                assertThat(generated).contains("tgt." + tenant + " = src." + tenant
                        + " AND tgt." + id + " = src." + id);
            }
        }

        /**
         * 所有列都是主键:没有可更新的内容。
         *
         * <p>空的 {@code UPDATE SET} 是语法错误,所以每种方言都得有自己的退路 ——
         * 这一条不测的话,一张"全是主键"的表(维度表里很常见)会在运行时才炸。
         */
        @Test
        @DisplayName("所有列都是主键时,各方言各有各的退路,且都带 warning")
        void allColumnsArePrimaryKeys() {
            List<String> all = List.of("tenant", "id");

            DialectUpsert.UpsertStatement pg = DialectUpsert.generate(
                    DataSourceType.POSTGRESQL, table(DataSourceType.POSTGRESQL), all, all);
            assertThat(pg.sql()).endsWith("DO NOTHING");

            DialectUpsert.UpsertStatement mysql = DialectUpsert.generate(
                    DataSourceType.MYSQL, table(DataSourceType.MYSQL), all, all);
            // 拿主键给自己赋值 —— 效果等同于什么都不做
            assertThat(mysql.sql()).endsWith("ON DUPLICATE KEY UPDATE `tenant` = `tenant`");

            for (DataSourceType type : List.of(DataSourceType.ORACLE, DataSourceType.SQLSERVER,
                    DataSourceType.DAMENG)) {
                String generated = DialectUpsert.generate(type, table(type), all, all).sql();
                assertThat(generated)
                        .as("方言 %s", type)
                        .doesNotContain("WHEN MATCHED")
                        .contains("WHEN NOT MATCHED");
            }

            assertThat(pg.warnings()).anyMatch(w -> w.contains("没有可更新的内容"));
        }
    }

    @Nested
    @DisplayName("拦住配不出正确语句的输入")
    class Rejections {

        @Test
        @DisplayName("没有主键 —— 「按主键更新」这句话本身就不成立")
        void noPrimaryKeys() {
            assertThatThrownBy(() -> DialectUpsert.generate(
                    DataSourceType.POSTGRESQL, "\"t\"", COLUMNS, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须指定主键");
        }

        @Test
        @DisplayName("主键不在写入列里 —— 它插入时是 NULL,永远匹配不上")
        void primaryKeyNotWritten() {
            assertThatThrownBy(() -> DialectUpsert.generate(
                    DataSourceType.POSTGRESQL, "\"t\"", List.of("name"), List.of("id")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("永远匹配不上");
        }

        @Test
        @DisplayName("主键填重了不会生成重复的比较条件")
        void duplicateKeysAreDeduplicated() {
            String generated = DialectUpsert.generate(DataSourceType.ORACLE,
                    table(DataSourceType.ORACLE), COLUMNS, List.of("id", "id")).sql();
            assertThat(generated).containsOnlyOnce("tgt.\"id\" = src.\"id\"");
        }

        @Test
        @DisplayName("非关系型数据源直接拒绝,不生成一条跑不了的语句")
        void nonRelational() {
            assertThatThrownBy(() -> DialectUpsert.generate(
                    DataSourceType.FTP, "t", COLUMNS, KEYS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不支持 UPSERT");
        }
    }
}
