package com.datagov.governance.domain;

/**
 * 告警状态(序号 25/26,SPACE-MODEL.md E.6)。
 *
 * <p>七个状态里有三个是"没送出去"的不同原因,这个区分是刻意的:
 * <ul>
 *   <li>{@link #SUPPRESSED} —— 在抑制窗口内,<b>平台主动不发</b>。这是配置生效,
 *       不是故障</li>
 *   <li>{@link #NOTIFY_FAILED} —— 渠道不可达,<b>想发但发不出去</b>。这是故障,
 *       而且是最危险的一种:告警系统自己坏了,而它坏了这件事没人会收到告警</li>
 *   <li>{@link #TRIGGERED} —— 刚生成,还没轮到它</li>
 * </ul>
 * 把这三者合成一个"未通知",值班的人就没法回答"昨晚那条告警为什么没收到"。
 */
public enum AlertStatus {

    /** 刚触发,还没决定要不要推送 */
    TRIGGERED("已触发"),

    /** 推送中 */
    NOTIFYING("推送中"),

    NOTIFIED("已通知"),

    /**
     * 被抑制 —— 抑制窗口内的同源告警。
     *
     * <p>序号 25 的「告警频率」就是这个:仍然记录(它是事实),但不推送
     * (一个每分钟触发一次的任务不该在两小时里发出 120 条短信)。
     */
    SUPPRESSED("已抑制"),

    /** 渠道不可达。告警系统自己坏了 —— 这件事必须能被查出来 */
    NOTIFY_FAILED("推送失败"),

    /** 有人认领了 */
    ACKNOWLEDGED("已认领"),

    /** 已解决:人工关闭,或触发条件自己恢复了 */
    RESOLVED("已解决");

    private final String displayName;

    AlertStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否还需要人关注。已抑制的也算 —— 它只是没推送,问题仍然存在 */
    public boolean isOpen() {
        return this != RESOLVED;
    }
}
