package com.datagov.runtime.domain;

/**
 * 流任务的运行态(序号 18,SPACE-MODEL.md E.3)。
 *
 * <p><b>这不是 {@link ExecutionStatus} 的别名。</b> 一次批执行的状态机围绕
 * "触发 → 完成",它有终态;流任务的状态机围绕"保活",它<b>正常情况下永远
 * 到不了终态</b> —— 一个跑了三个月的实时任务,问它"这次执行成功了吗"是没有
 * 意义的问题。
 *
 * <p>把两者塞进一个枚举是架构风险 R5:那样 RUNNING 会同时意味着"这一批正在
 * 跑"和"这条流还活着",而序号 24 的监控要统计的"失败数"会把每一次流任务的
 * 自动重启都记成一次失败。
 */
public enum StreamingStatus {

    /** 已发布但没启动过。定义就绪,等一次显式的 Start */
    PUBLISHED("未启动"),

    /** 启动中:作业已提交给引擎,还没收到"跑起来了"的回执 */
    STARTING("启动中"),

    RUNNING("运行中"),

    /**
     * 重启中。
     *
     * <p>流任务失败后自动退避重启是<b>常态而非异常</b>——上游断连、下游抖动
     * 都会触发它。所以它是一个独立状态,而不是"失败了再重新启动一次":
     * 记成失败会让序号 24 的失败数被正常的保活行为淹没。
     */
    RESTARTING("重启中"),

    /** 停止中:已请求停止,正在触发 savepoint */
    STOPPING("停止中"),

    /** 已停止。可从 savepoint 恢复,所以它不是终态 */
    STOPPED("已停止"),

    /**
     * 保活失败 —— 连续重启超过阈值仍起不来。
     *
     * <p>这才是流任务唯一的终态:平台已经放弃自动恢复,需要人介入。
     * 到达这里要发告警(序号 25),因为没有人会盯着一个"本该一直在跑"的
     * 任务的状态字段。
     */
    FAILED("保活失败");

    private final String displayName;

    StreamingStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否处于"平台认为它该活着"的状态。停止与保活失败都不是。 */
    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == RESTARTING;
    }

    /** 能否发起启动 */
    public boolean canStart() {
        return this == PUBLISHED || this == STOPPED || this == FAILED;
    }

    /** 能否发起停止 */
    public boolean canStop() {
        return isActive();
    }
}
