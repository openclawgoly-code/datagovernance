package com.datagov.control.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import lombok.Data;

import java.time.Instant;

/**
 * Control Space 的持久化实体。
 */
public final class ControlEntities {

    private ControlEntities() {
    }

    /**
     * 任务定义 —— 序号 9、11-14、18、20、22 共用的一张表。
     *
     * <p>七种任务菜单项对应七个 {@link JobType},但只有一张表。它们的公共部分
     * (名称、状态、版本、调度、编译结果)占了定义的绝大多数;差异部分全部收进
     * {@link #configJson}。按菜单拆七张表,会得到七套几乎相同的 CRUD、七套状态机、
     * 以及一个无法回答"这个空间一共有多少个任务"的数据模型。
     *
     * <p><b>configJson 的结构由 jobType 决定,Control 不解释它</b> —— 解释它的是
     * 对应的 {@code JobCompiler}。这让新增一种任务类型不必改这张表。
     */
    @Data
    @TableName("ctl_job_definition")
    public static class JobDefinition {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        private JobType jobType;
        private JobDefinitionStatus status;
        private String description;

        /** 类型特有的配置。结构由 jobType 决定 */
        private String configJson;

        /**
         * 定义版本,每次修改 +1。
         *
         * <p>Execution 绑定它启动时的版本;SCHEDULING 中的任务在下一次触发时
         * 才用新版本。这两条合起来才是「可复现」。
         */
        private Integer version;

        // ── 编译结果(最近一次)────────────────────────────────────────
        private Instant lastCompiledAt;
        private Boolean lastCompileSucceeded;
        private String lastCompileMessage;
        /** 完整诊断列表(JSON 数组),供 UI 精确定位到出错的字段/节点 */
        private String lastCompileDiagnosticsJson;
        /** 编译产物。可丢弃可重建 —— 定义没变就能编译出一样的计划 */
        private String physicalPlanJson;
        /** 该物理计划是由哪一版定义编译出来的。与 version 不等时说明计划过期 */
        private Integer planDefVersion;

        // ── 调度(功能 16「支持 Cron 表达式的周期调度策略配置」)──────────
        private String cronExpression;
        /** IANA 时区名。跨时区团队里"每天凌晨两点"是谁的两点必须说清楚 */
        private String cronTimezone;
        private Instant nextFireAt;
        private Instant lastFireAt;

        /**
         * 上一次还没跑完时,到点了怎么办:SKIP / QUEUE / CONCURRENT。
         *
         * <p>默认 SKIP。一个每 5 分钟跑一次、单次要跑 20 分钟的同步任务,
         * 若允许并发会在一小时内堆出十几个实例一起冲击目标库 —— 那是把
         * 「调度配置」变成了一次拒绝服务攻击。
         */
        private String misfirePolicy;

        /** 执行超时(毫秒),随 DispatchCommand 下发 */
        private Long timeoutMs;
        private Integer retryMaxAttempts;
        private Integer retryBackoffSeconds;

        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;

        @TableLogic
        private Boolean deleted;
    }

    /**
     * 定义的版本快照。
     *
     * <p>与 {@code md_datasource_version} 同一个套路:改动留痕,而且留的是
     * 完整快照而非 diff。回滚时不必逐版重放,直接取那一版即可。
     */
    @Data
    @TableName("ctl_job_definition_version")
    public static class JobDefinitionVersion {
        @TableId(type = IdType.INPUT)
        private String id;

        private String jobDefinitionId;
        private String workspaceId;
        private Integer version;
        /** CREATED / UPDATED / PUBLISHED / STATUS_CHANGED */
        private String changeType;
        private String changeSummary;
        private String snapshotJson;
        private Instant changedAt;
        private String changedBy;
    }

    /**
     * 调度触发日志。
     *
     * <p>单独一张表而不是只看 Execution:<b>没能触发出 Execution 的那些才是关键</b>
     * ——上一次还没跑完所以跳过、定义已暂停、编译过期。这些事件在 Execution 表里
     * 根本不存在,而它们恰恰是"为什么昨天的任务没跑"这个问题的答案。
     */
    @Data
    @TableName("ctl_schedule_fire")
    public static class ScheduleFire {
        @TableId(type = IdType.INPUT)
        private String id;

        private String jobDefinitionId;
        private String workspaceId;

        /** 计划触发时刻(Cron 算出来的),与实际触发时刻分开记 */
        private Instant scheduledAt;
        private Instant firedAt;

        /** FIRED / SKIPPED / FAILED */
        private String outcome;
        /** 产生的 Execution;SKIPPED 时为 null */
        private String executionId;
        private String reason;
    }
}
