package com.datagov.runtime.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 执行状态,与 SPACE-MODEL.md E.4 一致。
 *
 * <p>四个终态而不是两个:{@code SUCCEEDED / FAILED / CANCELED / TIMEOUT}。
 * 把取消和超时并进 FAILED 会让序号 24 的失败率虚高 —— 用户主动取消的任务
 * 不是失败,而超时与业务报错的处置方式完全不同(一个查资源,一个查逻辑)。
 */
public enum ExecutionStatus {

    /** 已受理,等待下发 */
    PENDING("待下发"),

    /** 已下发给执行器,尚未确认 */
    DISPATCHED("已下发"),

    RUNNING("执行中"),

    /** 取消已受理,等待执行器实际停下来 —— 取消不是瞬时的 */
    CANCELING("取消中"),

    SUCCEEDED("成功"),
    FAILED("失败"),
    CANCELED("已取消"),
    TIMEOUT("超时");

    private static final Set<ExecutionStatus> TERMINALS =
            EnumSet.of(SUCCEEDED, FAILED, CANCELED, TIMEOUT);

    private final String displayName;

    ExecutionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return TERMINALS.contains(this);
    }

    /** 是否已经交到执行器手上 —— 决定取消时要不要真的去通知执行器 */
    public boolean isDispatched() {
        return this == DISPATCHED || this == RUNNING || this == CANCELING;
    }

    /**
     * 是否计入序号 24 的「失败」口径。
     *
     * <p>超时算失败(任务确实没跑出结果),取消不算(是人主动叫停的)。
     * 这个口径写在这里而不是散落在各处统计 SQL 里,是为了让它只有一个定义。
     */
    public boolean countsAsFailure() {
        return this == FAILED || this == TIMEOUT;
    }
}
