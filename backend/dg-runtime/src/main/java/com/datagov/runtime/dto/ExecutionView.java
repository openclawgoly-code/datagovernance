package com.datagov.runtime.dto;

import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.entity.RuntimeEntities.Execution;
import com.datagov.runtime.entity.RuntimeEntities.ExecutionAttempt;

import java.time.Instant;
import java.util.List;

/**
 * 执行记录的对外视图 —— 序号 10/15/19/21/23 五个页面共用。
 *
 * <p>不含 {@code planJson}:物理计划可能包含连接串一类的敏感信息,而列表页
 * 并不需要它。要看计划走单独的接口,并单独判权限。
 */
public record ExecutionView(
        String id,
        JobRefType jobRefType,
        String jobRefTypeDisplayName,
        String jobRefId,
        String jobName,
        Integer defVersion,
        ExecutionStatus status,
        String statusDisplayName,
        String parentExecutionId,
        /** 工作流节点 ID(序号 22/23);非工作流子执行为 null */
        String workflowNodeId,
        String triggerType,
        String triggeredBy,
        Integer attemptCount,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String message,
        String errorCode,
        Long rowsRead,
        Long rowsWritten,
        Long bytesProcessed
) {

    public static ExecutionView from(Execution e) {
        return new ExecutionView(
                e.getId(), e.getJobRefType(),
                e.getJobRefType() == null ? null : e.getJobRefType().displayName(),
                e.getJobRefId(), e.getJobName(), e.getDefVersion(),
                e.getStatus(), e.getStatus() == null ? null : e.getStatus().displayName(),
                e.getParentExecutionId(), e.getWorkflowNodeId(),
                e.getTriggerType(), e.getTriggeredBy(),
                e.getAttemptCount(), e.getSubmittedAt(), e.getStartedAt(), e.getFinishedAt(),
                e.getDurationMs(), e.getMessage(), e.getErrorCode(),
                e.getRowsRead(), e.getRowsWritten(), e.getBytesProcessed());
    }

    /**
     * 一次尝试的视图。
     *
     * <p>界面上默认只显示 Execution;点开才看到尝试列表。理由是绝大多数执行
     * 只有一次尝试,默认展开会让列表被无意义的层级淹没 —— 而重试过的那些,
     * 恰恰是用户最想逐次看清楚的。
     */
    public record AttemptView(
            String id,
            Integer attemptNo,
            ExecutionStatus status,
            String statusDisplayName,
            String executorId,
            String engineJobId,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String message,
            String errorCode,
            String errorDetail,
            Long rowsRead,
            Long rowsWritten,
            Long bytesProcessed
    ) {

        public static AttemptView from(ExecutionAttempt a) {
            return new AttemptView(
                    a.getId(), a.getAttemptNo(), a.getStatus(),
                    a.getStatus() == null ? null : a.getStatus().displayName(),
                    a.getExecutorId(), a.getEngineJobId(),
                    a.getStartedAt(), a.getFinishedAt(), a.getDurationMs(),
                    a.getMessage(), a.getErrorCode(), a.getErrorDetail(),
                    a.getRowsRead(), a.getRowsWritten(), a.getBytesProcessed());
        }
    }

    /** 执行详情 = 执行本身 + 全部尝试。 */
    public record Detail(ExecutionView execution, List<AttemptView> attempts) {
    }
}
