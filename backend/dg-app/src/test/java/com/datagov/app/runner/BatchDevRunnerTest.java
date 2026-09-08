package com.datagov.app.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批作业的 SQL 切分(序号 20)。
 *
 * <p>朴素的 {@code split(";")} 会把 {@code WHERE name = 'a;b'} 切成两半,
 * 而那种数据真实存在 —— 地址、备注字段里带分号是常事。
 */
class BatchDevRunnerTest {

    @Nested
    @DisplayName("基本切分")
    class Basic {

        @Test
        @DisplayName("按分号切成多条")
        void splitsOnSemicolon() {
            assertThat(BatchDevRunner.splitStatements("SELECT 1; SELECT 2"))
                    .containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        @DisplayName("末尾的分号不产生空语句")
        void trailingSemicolonIgnored() {
            assertThat(BatchDevRunner.splitStatements("SELECT 1;")).containsExactly("SELECT 1");
        }

        @Test
        @DisplayName("连续分号与空白不产生空语句")
        void blankStatementsDropped() {
            assertThat(BatchDevRunner.splitStatements("SELECT 1;;  ;\n SELECT 2;"))
                    .containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        @DisplayName("空输入给空列表,不是一条空语句")
        void emptyInput() {
            assertThat(BatchDevRunner.splitStatements("")).isEmpty();
            assertThat(BatchDevRunner.splitStatements("   \n  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("字符串字面量")
    class StringLiterals {

        @Test
        @DisplayName("单引号里的分号不切 —— 地址、备注里带分号是常事")
        void semicolonInsideSingleQuotes() {
            assertThat(BatchDevRunner.splitStatements("SELECT * FROM t WHERE name = 'a;b'"))
                    .containsExactly("SELECT * FROM t WHERE name = 'a;b'");
        }

        @Test
        @DisplayName("双引号(标识符)里的分号不切")
        void semicolonInsideDoubleQuotes() {
            assertThat(BatchDevRunner.splitStatements("SELECT \"od;d\" FROM t"))
                    .containsExactly("SELECT \"od;d\" FROM t");
        }

        @Test
        @DisplayName("转义的单引号不会让字符串提前结束")
        void escapedQuote() {
            String sql = "SELECT 'it''s; fine' AS x; SELECT 2";
            assertThat(BatchDevRunner.splitStatements(sql))
                    .containsExactly("SELECT 'it''s; fine' AS x", "SELECT 2");
        }

        @Test
        @DisplayName("单引号里的双引号不影响状态")
        void quotesNestedInEachOther() {
            assertThat(BatchDevRunner.splitStatements("SELECT 'a\"b;c' FROM t"))
                    .containsExactly("SELECT 'a\"b;c' FROM t");
        }
    }

    @Nested
    @DisplayName("注释")
    class Comments {

        @Test
        @DisplayName("行注释里的分号不切,且注释本身被剥掉")
        void lineComment() {
            String sql = "SELECT 1 -- 这里有个分号; 不该切\nFROM t; SELECT 2";
            var statements = BatchDevRunner.splitStatements(sql);
            assertThat(statements).hasSize(2);
            assertThat(statements.get(0)).contains("SELECT 1").contains("FROM t")
                    .doesNotContain("不该切");
        }

        @Test
        @DisplayName("块注释里的分号不切")
        void blockComment() {
            String sql = "SELECT 1 /* a; b */ FROM t; SELECT 2";
            var statements = BatchDevRunner.splitStatements(sql);
            assertThat(statements).hasSize(2);
            assertThat(statements.get(0)).doesNotContain("a; b");
        }

        @Test
        @DisplayName("字符串里的 -- 不是注释")
        void dashesInsideStringAreNotComment() {
            assertThat(BatchDevRunner.splitStatements("SELECT '-- not a comment; x' FROM t"))
                    .containsExactly("SELECT '-- not a comment; x' FROM t");
        }
    }

    @Nested
    @DisplayName("多语句加工")
    class RealWorld {

        @Test
        @DisplayName("典型的一段加工 SQL")
        void typicalEtlScript() {
            String sql = """
                    -- 清空当日分区
                    DELETE FROM ods_order WHERE dt = '2026-09-08';

                    INSERT INTO ods_order (id, name, dt)
                    SELECT id, name, '2026-09-08' FROM stg_order
                    WHERE remark NOT LIKE '%;%';
                    """;
            var statements = BatchDevRunner.splitStatements(sql);
            assertThat(statements).hasSize(2);
            assertThat(statements.get(0)).startsWith("DELETE FROM ods_order");
            // 关键:LIKE '%;%' 里的分号没有把第二条切开
            assertThat(statements.get(1)).contains("NOT LIKE '%;%'");
        }
    }
}
