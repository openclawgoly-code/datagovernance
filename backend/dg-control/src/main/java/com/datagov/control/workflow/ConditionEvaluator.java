package com.datagov.control.workflow;

import com.datagov.control.workflow.WorkflowGraph.Condition;

/**
 * 条件节点求值(序号 22)。
 *
 * <p><b>求值发生在 Control,不下发 Runtime。</b> 这不是分层洁癖:Runtime 的
 * must_not_do 第一条是「不得解释业务语义」,而「上游写了 0 行所以跳过下游」
 * 是彻头彻尾的业务语义。把它交给 Runtime,等于让执行引擎开始理解"什么叫
 * 数据为空"—— 下一步就会有人要求它理解"什么叫数据异常"。
 *
 * <p>求值的输入只有<b>上游执行的事实</b>(状态、行数),没有业务数据。这条
 * 边界是靠 {@link Facts} 这个只有三个字段的记录守住的。
 */
public final class ConditionEvaluator {

    /**
     * 求值所需的上游事实。
     *
     * <p>刻意只有三个字段。给它加一个 {@code Map<String,Object> extra} 会立刻
     * 让"把业务数据塞进条件"变成可能,而那正是这里要防的。
     */
    public record Facts(String status, long rowsWritten, long rowsRead) {

        public static Facts of(String status, Long rowsWritten, Long rowsRead) {
            return new Facts(status,
                    rowsWritten == null ? 0L : rowsWritten,
                    rowsRead == null ? 0L : rowsRead);
        }
    }

    private ConditionEvaluator() {
    }

    /**
     * 求值。
     *
     * @throws IllegalArgumentException 判据本身不合法(未知的 source/operator)。
     *                                  这是编译期就该拦下的,走到这里说明编译校验有漏
     */
    public static boolean evaluate(Condition condition, Facts facts) {
        return switch (condition.source()) {
            case "UPSTREAM_STATUS" -> compareText(facts.status(), condition.operator(),
                    condition.value());
            case "UPSTREAM_ROWS_WRITTEN" -> compareNumber(facts.rowsWritten(),
                    condition.operator(), condition.value());
            case "UPSTREAM_ROWS_READ" -> compareNumber(facts.rowsRead(),
                    condition.operator(), condition.value());
            default -> throw new IllegalArgumentException("未知的条件取值来源: " + condition.source());
        };
    }

    private static boolean compareText(String actual, String operator, String expected) {
        String left = actual == null ? "" : actual;
        String right = expected == null ? "" : expected;
        return switch (operator) {
            case "EQ" -> left.equals(right);
            case "NE" -> !left.equals(right);
            // 字符串上的大小比较几乎总是笔误(想比行数却选了状态)。让它明确报错,
            // 而不是按字典序给出一个谁也预料不到的结果
            case "GT", "GTE", "LT", "LTE" -> throw new IllegalArgumentException(
                    "状态只能用 EQ / NE 比较,不能用 " + operator);
            default -> throw new IllegalArgumentException("未知的比较符: " + operator);
        };
    }

    private static boolean compareNumber(long actual, String operator, String expected) {
        long right;
        try {
            right = Long.parseLong(expected == null ? "" : expected.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("行数条件的比较值必须是整数: " + expected);
        }
        return switch (operator) {
            case "EQ" -> actual == right;
            case "NE" -> actual != right;
            case "GT" -> actual > right;
            case "GTE" -> actual >= right;
            case "LT" -> actual < right;
            case "LTE" -> actual <= right;
            default -> throw new IllegalArgumentException("未知的比较符: " + operator);
        };
    }
}
