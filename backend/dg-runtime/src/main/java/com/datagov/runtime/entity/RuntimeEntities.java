package com.datagov.runtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.ExecutorStatus;
import com.datagov.runtime.domain.JobRefType;
import lombok.Data;

import java.time.Instant;

/**
 * Runtime Space 的持久化实体。
 *
 * <p>集中在一个文件里,是因为这四张表必须一起读才说得通:
 * Execution 是事实,Attempt 是它的尝试,Metric 挂在 Attempt 上,Log 指向 Attempt。
 * 拆成四个文件会让人以为它们可以各自演化。
 */
public final class RuntimeEntities {

    private RuntimeEntities() {
    }

    /**
     * 执行事实 —— <b>全平台唯一的一张</b>(架构约束 R4)。
     *
     * <p>序号 10/15/19/21/23 五个「执行记录」页面查的都是这张表,只是
     * {@code jobRefType} 不同。序号 24 的任务监控查的是不加过滤的它。
     *
     * <p>这里<b>没有</b>业务字段(源表名、目标库、SQL 文本……)。那些属于定义,
     * 归 Metadata;Runtime 只持有 {@link #jobDefId} + {@link #defVersion} 这一对
     * 引用。要看"这次跑的到底是什么",拿这对引用回 Metadata 查 —— 而且因为
     * defVersion 被钉死,查到的一定是它当时执行的那一版,不是之后改过的版本。
     */
    @Data
    @TableName("rt_execution")
    public static class Execution {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;

        /** 作业种类 —— 五个执行记录页面靠它区分 */
        private JobRefType jobRefType;
        /** → 定义的 ID(md_datasource / ctl_job_definition 等,按 jobRefType 解释) */
        private String jobRefId;
        /** 冗余一份定义名,便于列表页不联表就能显示;定义改名不回溯修改历史记录 */
        private String jobName;

        /**
         * 启动时的定义版本。
         *
         * <p>钉死它才谈得上「可复现」:一个月后回看这条失败记录,能确定当时
         * 跑的是哪一版定义,而不是今天这一版。
         */
        private Integer defVersion;

        private ExecutionStatus status;

        /** 工作流节点的父执行(序号 22/23);顶层执行为 null */
        private String parentExecutionId;

        /**
         * 这条子执行对应工作流里的哪个节点(序号 22/23)。
         *
         * <p>存成一列而不是从计划快照里翻:推进逻辑每轮都要按节点归类子执行,
         * 而"从 JSON 里翻出节点 ID"没法建索引。
         */
        private String workflowNodeId;

        /** 触发来源:MANUAL / SCHEDULE / API / WORKFLOW / RETRY */
        private String triggerType;
        private String triggeredBy;

        /** 由 Control 下发、Runtime 原样保存的物理计划快照(JSON) */
        private String planJson;
        /** 由 Control 决定的重试策略(JSON);Runtime 只执行,不自行决定 */
        private String retryPolicyJson;

        /**
         * 执行超时(毫秒)。
         *
         * <p>逐条存而不是用一个全局值:整库迁移跑几小时是正常的,一次连通性检查
         * 超过十秒就该判死。用同一个阈值套两者,要么放过僵死的探测,要么半路杀掉
         * 正常的迁移。
         */
        private Long timeoutMs;

        /** 当前是第几次尝试,从 1 开始。与 rt_execution_attempt 的行数对应 */
        private Integer attemptCount;

        private Instant submittedAt;
        private Instant startedAt;
        private Instant finishedAt;
        /** 冗余的耗时(毫秒),避免监控每次都做时间差计算 */
        private Long durationMs;

        /** 终态摘要;失败时是错误信息的首行,成功时可为 null */
        private String message;
        /** 错误分类码,来自 ErrorCode 的 name();成功时 null */
        private String errorCode;

        // ── 指标汇总(取自最近一次成功或最后一次尝试)────────────────────
        // 冗余在这里是为了让序号 24 的监控页不必联表:那个页面是全表聚合,
        // 联表会把一次简单的 GROUP BY 变成大表 JOIN。
        private Long rowsRead;
        private Long rowsWritten;
        private Long bytesProcessed;

        private Instant createdAt;
        private Instant updatedAt;
    }

    /**
     * 一次执行尝试。
     *
     * <p>重试<b>新增一行 Attempt,不新建 Execution</b>。这是 R4 的直接推论:
     * 若重试新建 Execution,序号 24 的"执行总数"会被重试污染 —— 一个重试
     * 三次才成功的任务会被记成四个任务,失败率也随之失真。
     */
    @Data
    @TableName("rt_execution_attempt")
    public static class ExecutionAttempt {
        @TableId(type = IdType.INPUT)
        private String id;

        private String executionId;
        private String workspaceId;

        /** 第几次尝试,从 1 开始 */
        private Integer attemptNo;
        private ExecutionStatus status;

        /** 承接本次尝试的执行器;本地执行器为 null */
        private String executorId;
        /** 执行引擎侧的作业 ID(Flink JobID / K8s Job 名)——排障时的唯一入口 */
        private String engineJobId;

        private Instant startedAt;
        private Instant finishedAt;
        private Long durationMs;

        private String message;
        private String errorCode;
        /** 堆栈或引擎原始报错,单独存并截断 —— 它可能很长,不该混进 message */
        private String errorDetail;

        private Long rowsRead;
        private Long rowsWritten;
        private Long bytesProcessed;

        private Instant createdAt;
    }

    /**
     * 执行日志片段。
     *
     * <p>P2 落在 PostgreSQL;Contract 里写的是 OpenSearch。这里刻意不抽象出
     * 「日志存储」接口 —— 在只有一个实现的时候,那层抽象只会让人猜错它的形状。
     * 迁移时要改的是 {@code ExecutionLogService} 一个类。
     */
    @Data
    @TableName("rt_execution_log")
    public static class ExecutionLog {
        @TableId(type = IdType.INPUT)
        private String id;

        private String executionId;
        private String attemptId;
        private String workspaceId;

        /** 行序号,保证同一毫秒内的日志顺序稳定 —— 只按时间排会乱序 */
        private Long lineNo;
        /** INFO / WARN / ERROR */
        private String level;
        private String content;
        private Instant loggedAt;
    }

    /**
     * 执行器(序号 31)。
     *
     * <p>P2 只有一个内置的本地执行器。表先建起来是因为 {@code ExecutorAssignment}
     * 的分配逻辑要有落点,而不是等到接入 Flink 时再回来改执行事实表的结构。
     */
    @Data
    @TableName("rt_executor")
    public static class Executor {
        @TableId(type = IdType.INPUT)
        private String id;

        /** 全局唯一的执行器名 */
        private String name;
        /** LOCAL / FLINK / K8S */
        private String kind;
        private ExecutorStatus status;

        /** 空间隔离:null 表示平台共享执行器,非 null 表示专属某个空间 */
        private String workspaceId;

        private String endpoint;
        /** 并发上限;超过则新任务排队而不是压垮它 */
        private Integer maxConcurrency;
        private Integer runningCount;

        private Instant lastHeartbeatAt;
        private String labelsJson;

        private Instant createdAt;
        private Instant updatedAt;
    }
}
