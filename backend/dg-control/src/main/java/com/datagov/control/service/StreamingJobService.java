package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.mapper.JobDefinitionMapper;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.StreamingLifecycle;
import com.datagov.runtime.domain.StreamingStatus;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 实时任务的启动与停止(序号 18)。
 *
 * <p><b>它没有复用 {@link JobExecutionService}。</b> 那个类下发的是"跑一次然后
 * 结束"的执行;这里管的是"让它一直活着"。两者的操作词看起来都像"启动",
 * 但语义相反:批任务的一次执行结束是成功,流任务的执行结束是故障。
 *
 * <p>启动仍然会产生一条 {@code Execution} 记录 —— 序号 19 要求实时任务也有
 * 执行记录,而 R4 要求那是同一张表。区别在于这条记录会 RUNNING 好几个月,
 * 而它的终止是由 {@link StreamingStatus} 那台状态机决定的,不是由执行本身。
 */
@Service
public class StreamingJobService {

    private static final Logger log = LoggerFactory.getLogger(StreamingJobService.class);

    private final JobDefinitionMapper jobMapper;
    private final JobDefinitionService jobService;
    private final JobExecutionService executionDispatcher;
    private final ExecutionService executionService;

    public StreamingJobService(JobDefinitionMapper jobMapper,
                               JobDefinitionService jobService,
                               JobExecutionService executionDispatcher,
                               ExecutionService executionService) {
        this.jobMapper = jobMapper;
        this.jobService = jobService;
        this.executionDispatcher = executionDispatcher;
        this.executionService = executionService;
    }

    /** 流任务运行态视图。 */
    public record StreamingStateView(
            String jobDefinitionId,
            String jobName,
            StreamingStatus status,
            String statusDisplayName,
            boolean active,
            int restartCount,
            int maxRestartAttempts,
            Instant startedAt,
            Instant stoppedAt,
            String executionId,
            String message
    ) {

        static StreamingStateView from(JobDefinition d) {
            StreamingStatus status = d.getStreamingStatus() == null
                    ? StreamingStatus.PUBLISHED : d.getStreamingStatus();
            return new StreamingStateView(d.getId(), d.getName(), status, status.displayName(),
                    status.isActive(),
                    d.getStreamingRestartCount() == null ? 0 : d.getStreamingRestartCount(),
                    StreamingLifecycle.MAX_RESTART_ATTEMPTS,
                    d.getStreamingStartedAt(), d.getStreamingStoppedAt(),
                    d.getStreamingExecutionId(), d.getStreamingMessage());
        }
    }

    public StreamingStateView state(String jobDefinitionId) {
        return StreamingStateView.from(requireStreamingJob(jobDefinitionId));
    }

    /** 本空间所有实时任务的运行态。实时开发页的列表就是它。 */
    public List<StreamingStateView> listStates() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                        .eq(JobDefinition::getWorkspaceId, workspaceId)
                        .eq(JobDefinition::getJobType, JobType.STREAMING)
                        .orderByDesc(JobDefinition::getUpdatedAt))
                .stream().map(StreamingStateView::from).toList();
    }

    /**
     * 启动。
     *
     * <p>幂等地拒绝而不是幂等地成功:一个已经在跑的流任务收到第二次启动请求,
     * 多半意味着有人以为它没起来。返回 200 会掩盖那个误解,而 409 会让他去看
     * 一眼当前状态。
     */
    @Transactional
    public StreamingStateView start(String jobDefinitionId) {
        JobDefinition definition = requireStreamingJob(jobDefinitionId);
        StreamingStatus current = currentStatus(definition);

        if (!current.canStart()) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "实时任务当前为「%s」,不能启动".formatted(current.displayName()),
                    current.isActive() ? "它已经在运行了" : "请先等待当前操作完成");
        }
        StreamingLifecycle.MACHINE.checkTransition(current, StreamingStatus.STARTING);

        // 复用批任务那套发布/计划新鲜度校验 —— 那部分两者确实相同:
        // 跑一份过期的计划,对流任务同样是错的
        ExecutionView execution = executionDispatcher.dispatch(definition, "MANUAL",
                WorkspaceContext.require().userId());

        definition.setStreamingStatus(StreamingStatus.STARTING);
        // 重启计数在每次<b>人工</b>启动时清零:用户已经介入过了,之前那轮的
        // 失败次数不该继续算在这一轮头上
        definition.setStreamingRestartCount(0);
        definition.setStreamingStartedAt(Instant.now());
        definition.setStreamingStoppedAt(null);
        definition.setStreamingExecutionId(execution.id());
        definition.setStreamingMessage(null);
        jobMapper.updateById(definition);

        log.info("实时任务启动 job={} execution={}", definition.getId(), execution.id());
        return StreamingStateView.from(definition);
    }

    /**
     * 停止。
     *
     * <p>两阶段:先进 STOPPING,等引擎收尾(触发 savepoint)后才是 STOPPED。
     * 中间态存在的意义是让界面能显示"正在停止" —— 一个大状态的流作业落
     * savepoint 要几十秒,期间状态纹丝不动会让人以为按钮没生效。
     */
    @Transactional
    public StreamingStateView stop(String jobDefinitionId) {
        JobDefinition definition = requireStreamingJob(jobDefinitionId);
        StreamingStatus current = currentStatus(definition);

        if (!current.canStop()) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "实时任务当前为「%s」,不能停止".formatted(current.displayName()));
        }
        StreamingLifecycle.MACHINE.checkTransition(current, StreamingStatus.STOPPING);

        definition.setStreamingStatus(StreamingStatus.STOPPING);
        jobMapper.updateById(definition);

        // 取消那条常驻的执行记录。取消本身也是两阶段的(CANCELING → CANCELED),
        // 两台状态机各自推进,不互相等待。
        //
        // 用 cancelIfActive 而不是 cancel + catch:cancel 抛异常时 Spring 已经把
        // 这个事务标成 rollback-only,catch 住也救不回来 —— 提交时会变成一个
        // 堆栈指不到原因的 500。判断必须发生在抛之前。
        if (definition.getStreamingExecutionId() != null
                && !executionService.cancelIfActive(definition.getStreamingExecutionId())) {
            // 执行记录已经是终态(作业自己先挂了)—— 停止的意图仍然成立,
            // 直接落 STOPPED。报错只会让用户去点第二次停止
            return finishStop(definition, "作业已不在运行");
        }
        log.info("实时任务停止中 job={}", definition.getId());
        return StreamingStateView.from(definition);
    }

    // ── 运行态回调 ──────────────────────────────────────────────────────

    /** 引擎报告作业跑起来了。 */
    @Transactional
    public void onRunning(String jobDefinitionId) {
        JobDefinition definition = jobMapper.selectById(jobDefinitionId);
        if (definition == null) {
            return;
        }
        StreamingStatus current = currentStatus(definition);
        if (!StreamingLifecycle.MACHINE.canTransition(current, StreamingStatus.RUNNING)) {
            return;
        }
        definition.setStreamingStatus(StreamingStatus.RUNNING);
        definition.setStreamingMessage(null);
        jobMapper.updateById(definition);
    }

    /**
     * 引擎报告作业挂了。
     *
     * <p>还有重启预算就进 RESTARTING,用光了才落 FAILED。这个区分是
     * {@link StreamingStatus} 存在的主要理由:把每次自动重启都记成失败,
     * 序号 24 的失败数会被正常的保活行为淹没。
     */
    @Transactional
    public void onFailure(String jobDefinitionId, String reason) {
        JobDefinition definition = jobMapper.selectById(jobDefinitionId);
        if (definition == null) {
            return;
        }
        StreamingStatus current = currentStatus(definition);
        int restarts = definition.getStreamingRestartCount() == null
                ? 0 : definition.getStreamingRestartCount();
        StreamingStatus next = StreamingLifecycle.onFailure(current, restarts);
        if (!StreamingLifecycle.MACHINE.canTransition(current, next)) {
            return;
        }

        if (next == StreamingStatus.RESTARTING) {
            definition.setStreamingRestartCount(restarts + 1);
            definition.setStreamingMessage("第 %d 次重启,%d 秒后重试:%s".formatted(
                    restarts + 1, StreamingLifecycle.backoffMillis(restarts) / 1000, reason));
        } else if (next == StreamingStatus.FAILED) {
            definition.setStreamingMessage("保活失败(已重启 %d 次):%s".formatted(restarts, reason));
            definition.setStreamingStoppedAt(Instant.now());
            // 到这里要发告警(序号 25)—— 没有人会盯着一个"本该一直在跑"的
            // 任务的状态字段。P4 接上告警后,这里是它的触发点之一
            log.warn("实时任务保活失败 job={} 重启={} 次 reason={}",
                    definition.getId(), restarts, reason);
        } else if (next == StreamingStatus.STOPPED) {
            definition.setStreamingStoppedAt(Instant.now());
            definition.setStreamingMessage(reason);
        }
        definition.setStreamingStatus(next);
        jobMapper.updateById(definition);
    }

    /** 引擎报告作业已经停干净了。 */
    @Transactional
    public void onStopped(String jobDefinitionId) {
        JobDefinition definition = jobMapper.selectById(jobDefinitionId);
        if (definition != null) {
            finishStop(definition, null);
        }
    }

    private StreamingStateView finishStop(JobDefinition definition, String message) {
        StreamingStatus current = currentStatus(definition);
        if (StreamingLifecycle.MACHINE.canTransition(current, StreamingStatus.STOPPED)) {
            definition.setStreamingStatus(StreamingStatus.STOPPED);
            definition.setStreamingStoppedAt(Instant.now());
            definition.setStreamingMessage(message);
            definition.setStreamingExecutionId(null);
            jobMapper.updateById(definition);
        }
        return StreamingStateView.from(definition);
    }

    /**
     * 对账:执行记录已经终止,但运行态还以为它活着。
     *
     * <p>进程重启、回调丢失都会造成这种不一致。没有这个对账,界面会永远显示
     * 一个"运行中"但其实早就死了的任务 —— 而那比显示"失败"更糟,因为没人
     * 会去查一个看起来正常的任务。
     */
    @Transactional
    public int reconcile() {
        List<JobDefinition> active = jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getJobType, JobType.STREAMING)
                .in(JobDefinition::getStreamingStatus,
                        StreamingStatus.STARTING, StreamingStatus.RUNNING,
                        StreamingStatus.STOPPING));
        int fixed = 0;
        for (JobDefinition definition : active) {
            String executionId = definition.getStreamingExecutionId();
            if (executionId == null) {
                continue;
            }
            ExecutionStatus status = executionService.statusOf(executionId);
            if (status == null || !status.isTerminal()) {
                continue;
            }
            if (definition.getStreamingStatus() == StreamingStatus.STOPPING
                    || status == ExecutionStatus.CANCELED) {
                finishStop(definition, "执行已结束");
            } else {
                onFailure(definition.getId(), "执行已结束于 " + status.displayName());
            }
            fixed++;
        }
        if (fixed > 0) {
            log.info("流任务运行态对账:修正 {} 个", fixed);
        }
        return fixed;
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static StreamingStatus currentStatus(JobDefinition definition) {
        return definition.getStreamingStatus() == null
                ? StreamingStatus.PUBLISHED : definition.getStreamingStatus();
    }

    private JobDefinition requireStreamingJob(String jobDefinitionId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        JobDefinition definition = jobService.loadInternal(jobDefinitionId);
        if (!workspaceId.equals(definition.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, jobDefinitionId);
        }
        if (definition.getJobType() != JobType.STREAMING) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "任务「%s」不是实时任务,没有启停操作".formatted(definition.getName()),
                    "批任务用「立即执行」,不是启动");
        }
        return definition;
    }
}
