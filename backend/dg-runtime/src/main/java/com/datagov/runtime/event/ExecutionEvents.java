package com.datagov.runtime.event;

import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;

import java.time.Instant;

/**
 * Runtime 对外发布的执行事实事件。
 *
 * <p>P2 走 Spring 的进程内事件总线;Contract 里写的 Kafka topic {@code execution.*}
 * 是拆进程之后的事。这里刻意保持事件<b>自足</b>(不含只有 Runtime 才能解引用的
 * 句柄),这样搬到 Kafka 时改的是发布器,不是每一个消费者。
 *
 * <p><b>为什么终态事件只有一个 {@link ExecutionFinished} 而不是四个:</b>
 * 消费方(Governance 的监控与告警)关心的是"一次执行结束了,结果如何、指标多少",
 * 而不是"成功了"和"失败了"两件不同的事。拆成四个事件,每个消费者都得订阅四次
 * 并写四个几乎相同的处理器,而漏订一个的后果是某类结果在监控里凭空消失。
 */
public final class ExecutionEvents {

    private ExecutionEvents() {
    }

    /** 已受理,等待下发。这一步就落库,是为了"提交了但没跑起来"也有记录。 */
    public record ExecutionAccepted(
            String workspaceId, String executionId, JobRefType jobRefType,
            String jobRefId, String jobName, Instant occurredAt) {
    }

    public record ExecutionStarted(
            String workspaceId, String executionId, String attemptId,
            int attemptNo, Instant occurredAt) {
    }

    /** 进度事件,携带增量指标。高频,消费方需自行做聚合与节流。 */
    public record ExecutionProgressed(
            String workspaceId, String executionId, String attemptId,
            long rowsRead, long rowsWritten, long bytesProcessed, Instant occurredAt) {
    }

    /**
     * 执行到达终态。
     *
     * <p>序号 24(监控)与 25/26(告警)共用这一个事实源 —— 两者若各自采集,
     * 迟早会出现"监控说成功、告警说失败"这种无法解释的分歧。
     */
    public record ExecutionFinished(
            String workspaceId, String executionId, JobRefType jobRefType,
            String jobRefId, String jobName, ExecutionStatus status,
            int attemptCount, Long durationMs,
            Long rowsRead, Long rowsWritten, Long bytesProcessed,
            String message, String errorCode, Instant occurredAt) {

        /** 是否计入失败率。口径定义在 {@link ExecutionStatus#countsAsFailure()},此处不重复。 */
        public boolean failed() {
            return status.countsAsFailure();
        }
    }

    /**
     * 下发被拒:没有可用执行器、并发配额已满。
     *
     * <p>必须是显式事件。若只是让执行停在 PENDING,"这个任务根本没跑"这件事
     * 会静默丢失 —— 用户看到的是一个永远"待下发"的行,而没有任何人被告知。
     */
    public record DispatchRejected(
            String workspaceId, String executionId, String reason, Instant occurredAt) {
    }

    /** 重试已排期。带上等待时长,便于界面显示"将在 60 秒后重试"。 */
    public record ExecutionRetryScheduled(
            String workspaceId, String executionId, int nextAttemptNo,
            long backoffMillis, Instant occurredAt) {
    }
}
