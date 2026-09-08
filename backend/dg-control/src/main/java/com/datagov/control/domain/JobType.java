package com.datagov.control.domain;

import com.datagov.runtime.domain.JobRefType;

/**
 * 任务定义的种类 —— 需求清单里的任务菜单项到 Runtime 作业种类的映射。
 *
 * <p>为什么不直接复用 {@link JobRefType}:两者的<b>粒度不同</b>。Runtime 只需要
 * 知道"该交给哪个引擎",Control 还要知道"该走哪条编译链、需要哪些定义字段"。
 * 目前是一对一,但工作流(WORKFLOW)在 Runtime 侧会展开成 WORKFLOW +
 * WORKFLOW_NODE 两种作业,一对一已经不成立了。
 */
public enum JobType {

    /** 序号 9:整库迁移。一次性任务,不进入 SCHEDULING */
    DB_MIGRATION("整库迁移", JobRefType.MIGRATION, false),

    /** 序号 11:数据库离线同步 */
    OFFLINE_SYNC("离线同步", JobRefType.OFFLINE_SYNC, true),

    /** 序号 12:文件解析入库 */
    FILE_PARSE("文件解析入库", JobRefType.FILE_PARSE, true),

    /** 序号 13:接口解析入库 */
    API_PARSE("接口解析入库", JobRefType.API_PARSE, true),

    /** 序号 18:实时开发任务 —— 常驻,不走周期调度(R5) */
    STREAMING("实时任务", JobRefType.STREAMING_DEV, false),

    /** 序号 20:离线(批)开发任务 */
    BATCH("批处理任务", JobRefType.BATCH_DEV, true),

    /** 序号 22:工作流 */
    WORKFLOW("工作流", JobRefType.WORKFLOW, true);

    private final String displayName;
    private final JobRefType runtimeType;
    private final boolean schedulable;

    JobType(String displayName, JobRefType runtimeType, boolean schedulable) {
        this.displayName = displayName;
        this.runtimeType = runtimeType;
        this.schedulable = schedulable;
    }

    public String displayName() {
        return displayName;
    }

    public JobRefType runtimeType() {
        return runtimeType;
    }

    /**
     * 能否绑定 Cron 调度。
     *
     * <p>整库迁移是一次性的(把一个库整体搬过去,搬完就完了);实时任务是常驻的
     * (启动后一直跑,没有"每天三点再跑一次"这回事)。给这两者绑 Cron 不是限制
     * 不够宽松,而是那个操作本身没有意义 —— 允许它只会制造出用户无法理解的行为。
     */
    public boolean isSchedulable() {
        return schedulable;
    }

    /** 是否常驻作业(序号 18)。它的运行态状态机与批任务不同。 */
    public boolean isLongRunning() {
        return runtimeType.isLongRunning();
    }
}
