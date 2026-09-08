package com.datagov.control.workflow;

import com.datagov.control.workflow.ConditionEvaluator.Facts;
import com.datagov.control.workflow.WorkflowGraph.Condition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 条件节点求值(序号 22)。求值在 Control 发生,输入只有上游执行的事实。 */
class ConditionEvaluatorTest {

    private static Condition cond(String source, String op, String value) {
        return new Condition(source, op, value);
    }

    @Nested
    @DisplayName("按上游状态判断")
    class ByStatus {

        @Test
        @DisplayName("上游成功时走 TRUE 分支")
        void statusEquals() {
            Facts facts = Facts.of("SUCCEEDED", 100L, 100L);
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_STATUS", "EQ", "SUCCEEDED"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_STATUS", "EQ", "FAILED"), facts)).isFalse();
        }

        @Test
        @DisplayName("NE 可用于「只要没失败就继续」")
        void statusNotEquals() {
            Facts facts = Facts.of("SUCCEEDED", 0L, 0L);
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_STATUS", "NE", "FAILED"), facts)).isTrue();
        }

        @Test
        @DisplayName("状态不许用大小比较 —— 那几乎总是「本想比行数」的笔误")
        void statusRejectsOrdering() {
            assertThatThrownBy(() -> ConditionEvaluator.evaluate(
                    cond("UPSTREAM_STATUS", "GT", "SUCCEEDED"), Facts.of("SUCCEEDED", 0L, 0L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只能用 EQ / NE");
        }
    }

    @Nested
    @DisplayName("按上游行数判断")
    class ByRowCount {

        @Test
        @DisplayName("「写入行数 > 0 才继续」是最常用的判据")
        void rowsWrittenGreaterThanZero() {
            Condition c = cond("UPSTREAM_ROWS_WRITTEN", "GT", "0");
            assertThat(ConditionEvaluator.evaluate(c, Facts.of("SUCCEEDED", 42L, 42L))).isTrue();
            assertThat(ConditionEvaluator.evaluate(c, Facts.of("SUCCEEDED", 0L, 0L))).isFalse();
        }

        @Test
        @DisplayName("六个比较符都可用")
        void allOperators() {
            Facts facts = Facts.of("SUCCEEDED", 100L, 100L);
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "EQ", "100"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "NE", "100"), facts)).isFalse();
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "GT", "99"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "GTE", "100"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "LT", "101"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(cond("UPSTREAM_ROWS_WRITTEN", "LTE", "100"), facts)).isTrue();
        }

        @Test
        @DisplayName("读取行数与写入行数是两个独立的取值来源")
        void readAndWrittenAreDistinct() {
            // 读了 1000 行写了 0 行 —— 这正是「全被规则过滤掉了」该被发现的场景
            Facts facts = Facts.of("SUCCEEDED", 0L, 1000L);
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_ROWS_READ", "GT", "0"), facts)).isTrue();
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_ROWS_WRITTEN", "GT", "0"), facts)).isFalse();
        }

        @Test
        @DisplayName("行数为 null 时按 0 算 —— 不抛 NPE")
        void nullRowsAreZero() {
            Facts facts = Facts.of("SUCCEEDED", null, null);
            assertThat(ConditionEvaluator.evaluate(
                    cond("UPSTREAM_ROWS_WRITTEN", "EQ", "0"), facts)).isTrue();
        }

        @Test
        @DisplayName("比较值不是整数时明确报错")
        void rejectsNonNumericValue() {
            assertThatThrownBy(() -> ConditionEvaluator.evaluate(
                    cond("UPSTREAM_ROWS_WRITTEN", "GT", "很多"), Facts.of("SUCCEEDED", 1L, 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须是整数");
        }
    }

    @Nested
    @DisplayName("封闭的小语言")
    class ClosedLanguage {

        @Test
        @DisplayName("取值来源只有三种 —— 没有「任意表达式」这个口子")
        void onlyThreeSources() {
            assertThat(Condition.SOURCES).hasSize(3);
            assertThatThrownBy(() -> ConditionEvaluator.evaluate(
                    cond("BUSINESS_DATA", "EQ", "x"), Facts.of("SUCCEEDED", 1L, 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知的条件取值来源");
        }

        @Test
        @DisplayName("未知比较符明确报错,而不是默认返回 false")
        void unknownOperatorFails() {
            assertThatThrownBy(() -> ConditionEvaluator.evaluate(
                    cond("UPSTREAM_ROWS_WRITTEN", "MATCHES", "1"), Facts.of("SUCCEEDED", 1L, 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知的比较符");
        }
    }
}
