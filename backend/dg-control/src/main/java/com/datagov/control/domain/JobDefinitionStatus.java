package com.datagov.control.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务定义的状态,与 SPACE-MODEL.md E.2 一致。
 *
 * <p><b>一套状态机覆盖序号 9、11-14、18、20、22 的全部任务类型。</b>
 * 整库迁移、离线同步、文件解析、接口解析、实时开发、批处理、工作流 —— 它们的
 * 定义态生命周期是同一条:草稿 → 编译校验 → 发布 → 调度 → 下线。按菜单给每种
 * 任务各写一套状态机,会得到七份几乎相同、但迟早各自跑偏的实现。
 *
 * <p>区别不在定义态而在<b>运行态</b>:流任务(序号 18)常驻,批任务周期触发。
 * 那个区别落在 Runtime 的两套运行状态机上(风险 R5),不在这里。
 */
public enum JobDefinitionStatus {

    /** 草稿。可以随意改,不会被调度到 */
    DRAFT("草稿"),

    /** 编译通过。可以发布,但还没发布 */
    VALIDATED("已校验"),

    /** 已发布。可以手工触发,也可以绑定调度 */
    PUBLISHED("已发布"),

    /** 调度中。Cron 到点自动触发 */
    SCHEDULING("调度中"),

    /** 已暂停。定义仍在,只是不再触发 —— 与 OFFLINE 的区别是"随时可恢复" */
    PAUSED("已暂停"),

    /** 已下线。不再触发,也不能直接恢复,要重新发布 */
    OFFLINE("已下线"),

    /** 已归档。终态 */
    ARCHIVED("已归档");

    private static final Set<JobDefinitionStatus> SCHEDULABLE =
            EnumSet.of(PUBLISHED, SCHEDULING, PAUSED);
    private static final Set<JobDefinitionStatus> RUNNABLE =
            EnumSet.of(PUBLISHED, SCHEDULING, PAUSED);

    private final String displayName;

    JobDefinitionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return this == ARCHIVED;
    }

    /** 能否绑定/修改调度 */
    public boolean isSchedulable() {
        return SCHEDULABLE.contains(this);
    }

    /**
     * 能否手工触发一次。
     *
     * <p>PAUSED 也能手工触发:暂停关掉的是<b>自动</b>触发。运维在排查问题时
     * 常常需要"先停掉自动调度,手工跑一次看看" —— 把手工触发一起禁掉,
     * 会逼他们把任务恢复成 SCHEDULING 再跑,那正是他们想避免的。
     */
    public boolean isRunnable() {
        return RUNNABLE.contains(this);
    }
}
