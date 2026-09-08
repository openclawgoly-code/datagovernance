package com.datagov.data.spi.query;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读 SQL 护栏。
 *
 * <p>这是功能 7 里最该被钉死的一段代码:平台在这里用的是用户配置的数据源凭据,
 * 那往往是一个有写权限的账号。护栏失效 = 数据源管理页变成面向所有业务库的
 * 通用 SQL 客户端。
 */
@DisplayName("只读 SQL 护栏")
class ReadOnlySqlGuardTest {

    @Nested
    @DisplayName("允许的查询语句")
    class Allowed {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "SELECT * FROM t",
                "select id, name from users where id = 1",
                "WITH cte AS (SELECT 1 AS x) SELECT * FROM cte",
                "SHOW DATABASES",
                "DESC users",
                "DESCRIBE users",
                "EXPLAIN SELECT * FROM t",
                "  \n  SELECT 1  \n ",
                "(SELECT 1) UNION (SELECT 2)",
                "SELECT * FROM t;",
        })
        void queryStatementsPass(String sql) {
            assertThatCode(() -> ReadOnlySqlGuard.requireReadOnly(sql)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("结尾分号被去掉")
        void trailingSemicolonStripped() {
            assertThat(ReadOnlySqlGuard.requireReadOnly("SELECT 1;")).isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("字符串字面量里出现写关键字不影响判定")
        void keywordsInsideLiteralsAreFine() {
            // 这是最容易误杀的一类:一个正常的查询,只是数据里带了 'DELETE' 字样
            assertThatCode(() -> ReadOnlySqlGuard.requireReadOnly(
                    "SELECT * FROM audit WHERE action = 'DELETE FROM t'"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("必须拦掉的写操作")
    class Blocked {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "DELETE FROM users",
                "delete from users where id = 1",
                "UPDATE users SET name = 'x'",
                "INSERT INTO users VALUES (1)",
                "DROP TABLE users",
                "TRUNCATE TABLE users",
                "ALTER TABLE users ADD COLUMN x INT",
                "CREATE TABLE t (id INT)",
                "GRANT ALL ON t TO public",
                "CALL some_procedure()",
                "MERGE INTO t USING s ON (1=1)",
        })
        void writeStatementsRejected(String sql) {
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(sql))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo("DAT_SQL_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("多条语句被拒 —— 这是最常见的注入形态")
        void multipleStatementsRejected() {
            // 只校验第一条的护栏等于没有护栏
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(
                    "SELECT 1; DROP TABLE users"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("一次只能执行一条语句");
        }

        @Test
        @DisplayName("以注释开头掩盖真实语句被拒")
        void commentPrefixedWriteRejected() {
            // 不先去注释的话,"第一个词"会被判成注释内容
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(
                    "/* SELECT */ DELETE FROM users"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(
                    "-- SELECT 1\nDROP TABLE users"))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("CTE 里藏 DML 被拒 —— 它确实以 WITH 开头")
        void dmlInsideCteRejected() {
            // PostgreSQL 支持在 CTE 里写 DML,这是"以查询关键字开头"最典型的绕过
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(
                    "WITH d AS (DELETE FROM users RETURNING *) SELECT * FROM d"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("写操作");
        }

        @Test
        @DisplayName("SELECT ... INTO OUTFILE 被拒 —— 它会往磁盘写文件")
        void selectIntoOutfileRejected() {
            assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(
                    "SELECT * FROM users INTO OUTFILE '/tmp/dump.txt'"))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("空语句被拒")
        void blankRejected() {
            for (String sql : new String[]{null, "", "   ", ";", "-- 只有注释"}) {
                assertThatThrownBy(() -> ReadOnlySqlGuard.requireReadOnly(sql))
                        .as("输入: %s", sql)
                        .isInstanceOf(BizException.class);
            }
        }
    }

    @Nested
    @DisplayName("注释剥离")
    class CommentStripping {

        @Test
        @DisplayName("行注释与块注释都被去掉")
        void stripsBothCommentStyles() {
            assertThat(ReadOnlySqlGuard.stripComments("SELECT 1 -- 尾注释\nFROM t"))
                    .contains("SELECT 1").doesNotContain("尾注释");
            assertThat(ReadOnlySqlGuard.stripComments("SELECT /* 中间 */ 1"))
                    .contains("SELECT").doesNotContain("中间");
        }

        @Test
        @DisplayName("字符串里的 -- 与 /* 不当作注释")
        void doesNotStripInsideStringLiterals() {
            String sql = "SELECT '-- not a comment' AS a, '/* nor this */' AS b";
            assertThat(ReadOnlySqlGuard.stripComments(sql))
                    .contains("-- not a comment")
                    .contains("/* nor this */");
        }
    }

    @Nested
    @DisplayName("请求参数的强制上限")
    class RequestLimits {

        @Test
        @DisplayName("行数与超时都有硬上限,且非正数回落到默认值")
        void limitsAreClamped() {
            // 没有上限的查询能把一张亿级表整个拉进平台内存;
            // 没有超时的查询能占住目标库的连接与 CPU
            SqlQuery.Request huge = new SqlQuery.Request("SELECT 1", 999_999, 999_999);
            assertThat(huge.maxRows()).isEqualTo(SqlQuery.Request.MAX_ALLOWED_ROWS);
            assertThat(huge.timeoutSeconds()).isEqualTo(SqlQuery.Request.MAX_ALLOWED_TIMEOUT_SECONDS);

            SqlQuery.Request zero = new SqlQuery.Request("SELECT 1", 0, 0);
            assertThat(zero.maxRows()).isEqualTo(SqlQuery.Request.DEFAULT_MAX_ROWS);
            assertThat(zero.timeoutSeconds()).isEqualTo(SqlQuery.Request.DEFAULT_TIMEOUT_SECONDS);

            SqlQuery.Request negative = new SqlQuery.Request("SELECT 1", -5, -5);
            assertThat(negative.maxRows()).isEqualTo(SqlQuery.Request.DEFAULT_MAX_ROWS);
        }
    }
}
