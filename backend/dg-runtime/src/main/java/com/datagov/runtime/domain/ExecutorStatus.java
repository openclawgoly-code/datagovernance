package com.datagov.runtime.domain;

/**
 * 执行器状态(序号 31),与 Contract 的 {@code Executor: [REGISTERED, HEALTHY,
 * DRAINING, UNHEALTHY, REMOVED]} 一致。
 *
 * <p>{@code DRAINING} 单独成一个状态而不是"标记一下不再派活",是因为下线一个
 * 执行器必须等它手上的任务跑完 —— 直接摘掉等于把正在跑的任务连同它的执行记录
 * 一起丢掉,而那些记录是序号 24 的事实来源。
 */
public enum ExecutorStatus {

    /** 已注册,还没通过第一次健康检查 */
    REGISTERED("已注册"),

    HEALTHY("健康"),

    /** 排空中:不再接新任务,等手上的跑完 */
    DRAINING("排空中"),

    /** 心跳超时或健康检查失败 —— 上面的任务需要被重新调度 */
    UNHEALTHY("不健康"),

    /** 已移除。保留记录而不是删行:历史执行记录还指着它 */
    REMOVED("已移除");

    private final String displayName;

    ExecutorStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 能否接受新任务 */
    public boolean acceptsWork() {
        return this == HEALTHY;
    }
}
