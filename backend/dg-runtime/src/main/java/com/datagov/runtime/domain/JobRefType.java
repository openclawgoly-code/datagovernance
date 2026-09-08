package com.datagov.runtime.domain;

/**
 * 执行所属的作业种类。
 *
 * <p><b>这个枚举是架构约束 R4 的落点。</b> 需求清单里有五处独立的「执行记录」页面
 * (序号 10 整库迁移记录、15 离线同步记录、19 实时任务记录、21 批处理记录、
 * 23 工作流记录),按菜单直译会得到五张结构几乎相同的表。那样做的代价不是冗余,
 * 而是<b>序号 24 的任务监控从此没有单一事实源</b> —— 「今天一共跑了多少个任务、
 * 失败率多少」需要 UNION 五张表,而这五张表的字段迟早会各自演化。
 *
 * <p>所以只有一张 {@code rt_execution},用本枚举区分种类。五个页面是同一个查询
 * 加不同的 {@code jobRefType} 过滤,监控是不加过滤的那一个。
 *
 * <p>注意这里<b>没有</b>业务语义方法(比如 {@code isSyncJob()})。Runtime 的
 * must_not_do 第一条是「不得解释业务级语义」;它只需要知道"这是哪一类,该发给
 * 哪个执行器",不需要知道整库迁移和离线同步在业务上有什么区别。
 */
public enum JobRefType {

    /** 序号 9/10:整库迁移 */
    MIGRATION("整库迁移"),

    /** 序号 11/15:离线同步 */
    OFFLINE_SYNC("离线同步"),

    /** 序号 12:文件解析入库 */
    FILE_PARSE("文件解析"),

    /** 序号 13:接口解析入库 */
    API_PARSE("接口解析"),

    /** 序号 18/19:实时开发任务 —— 常驻,状态机与批任务不同(R5) */
    STREAMING_DEV("实时任务"),

    /** 序号 20/21:离线(批)开发任务 */
    BATCH_DEV("批处理任务"),

    /** 序号 22/23:工作流整体 */
    WORKFLOW("工作流"),

    /** 工作流 DAG 里的单个节点;通过 parentExecutionId 挂在 WORKFLOW 之下 */
    WORKFLOW_NODE("工作流节点"),

    /** 序号 6:周期连通性检查 */
    CONNECTIVITY_PROBE("连通性检查"),

    /** 序号 34:Intelligence 的训练/预标注任务 */
    PYTHON_JOB("Python 任务");

    private final String displayName;

    JobRefType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 是否常驻作业。
     *
     * <p>这不是业务语义而是<b>调度语义</b>:常驻作业没有"跑完"这回事,它的终态
     * 只可能来自停止或失败。把它和批作业塞进同一套状态机是风险 R5。
     */
    public boolean isLongRunning() {
        return this == STREAMING_DEV;
    }
}
