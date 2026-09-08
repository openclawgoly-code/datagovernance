package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.entity.ControlEntities.ScheduleFire;
import com.datagov.control.mapper.ScheduleFireMapper;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.dto.DispatchCommand;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.entity.RuntimeEntities.Execution;
import com.datagov.runtime.mapper.ExecutionMapper;
import com.datagov.runtime.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 把一份任务定义变成一次 Execution —— Control 与 Runtime 之间唯一的动作接缝。
 *
 * <p>手工触发(TriggerNow / RunOnce)与调度触发走同一条路径 {@link #dispatch}。
 * 两条路径分开写必然漂移,而漂移的表现形式通常是"手工能跑、定时跑不起来",
 * 排查起来极其费时。
 */
@Service
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobDefinitionService jobService;
    private final ExecutionService executionService;
    private final ExecutionMapper executionMapper;
    private final ScheduleFireMapper fireMapper;

    public JobExecutionService(JobDefinitionService jobService,
                               ExecutionService executionService,
                               ExecutionMapper executionMapper,
                               ScheduleFireMapper fireMapper) {
        this.jobService = jobService;
        this.executionService = executionService;
        this.executionMapper = executionMapper;
        this.fireMapper = fireMapper;
    }

    /**
     * TriggerNow / RunOnce —— 用户手工触发一次。
     */
    @Transactional
    public ExecutionView triggerNow(String jobDefinitionId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        JobDefinition definition = jobService.loadInternal(jobDefinitionId);
        if (!workspaceId.equals(definition.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, jobDefinitionId);
        }
        return dispatch(definition, "MANUAL", operator);
    }

    /**
     * 下发一次执行。
     *
     * <p>发布前的三道检查按"最可能出错"排序,让用户先看到最相关的那一条:
     * 状态 → 计划存在 → 计划是最新的。
     */
    @Transactional
    public ExecutionView dispatch(JobDefinition definition, String triggerType, String operator) {
        if (!definition.getStatus().isRunnable()) {
            throw new BizException(ErrorCode.CTL_JOB_NOT_RUNNABLE,
                    "任务当前为「%s」,不能执行".formatted(definition.getStatus().displayName()),
                    definition.getStatus() == JobDefinitionStatus.DRAFT
                            ? "请先编译并发布"
                            : "请先重新发布该任务");
        }

        Map<String, Object> plan = jobService.readPhysicalPlan(definition);
        if (plan.isEmpty()) {
            throw new BizException(ErrorCode.CTL_PLAN_STALE,
                    "任务尚未编译出物理计划", "请先编译并发布");
        }
        // 定义改过但没重新编译:此刻执行的会是一份过期的计划,而用户以为跑的是
        // 他刚改的那一版。宁可拒绝,也不要让一次执行的语义模糊不清。
        if (definition.getPlanDefVersion() == null
                || !definition.getPlanDefVersion().equals(definition.getVersion())) {
            throw new BizException(ErrorCode.CTL_PLAN_STALE,
                    "定义已修改(v%d)但物理计划仍是 v%s"
                            .formatted(definition.getVersion(), definition.getPlanDefVersion()),
                    "请重新编译并发布后再执行");
        }

        DispatchCommand command = new DispatchCommand(
                definition.getWorkspaceId(),
                definition.getJobType().runtimeType(),
                definition.getId(),
                definition.getName(),
                // 绑定的是<b>计划</b>的版本而不是定义的当前版本。两者此刻相等
                // (上面刚校验过),但显式取 planDefVersion 表达的是「这次跑的是
                // 哪一版编译产物」——那才是可复现性关心的东西。
                definition.getPlanDefVersion(),
                plan,
                retryPolicyOf(definition),
                definition.getTimeoutMs(),
                null,
                triggerType,
                operator);

        // 工作流父执行不交给引擎:它自己不跑任何东西,只是那几个节点的容器。
        // 交给引擎会立刻"成功"(没有活可干),而它应该一直 RUNNING 到最后
        // 一个节点结束 —— 由 WorkflowOrchestrator 判定终态
        ExecutionView execution = definition.getJobType() == JobType.WORKFLOW
                ? executionService.dispatchExternallyDriven(command)
                : executionService.dispatch(command);
        log.info("已下发执行 job={} type={} execution={} 触发={}",
                definition.getId(), definition.getJobType(), execution.id(), triggerType);
        return execution;
    }

    /**
     * 调度触发一次,并记录触发日志。
     *
     * <p>返回 null 表示<b>被跳过</b>(上一次还没跑完)。跳过也要落 ScheduleFire ——
     * 「为什么昨天的任务没跑」这个问题的答案就在那些跳过记录里,而 Execution 表
     * 里根本不存在它们。
     */
    @Transactional
    public ExecutionView fireScheduled(JobDefinition definition, Instant scheduledAt) {
        Instant now = Instant.now();

        String skipReason = shouldSkip(definition);
        if (skipReason != null) {
            recordFire(definition, scheduledAt, now, "SKIPPED", null, skipReason);
            log.info("调度跳过 job={} 原因={}", definition.getId(), skipReason);
            return null;
        }

        try {
            ExecutionView execution = dispatch(definition, "SCHEDULE", "system:scheduler");
            recordFire(definition, scheduledAt, now, "FIRED", execution.id(), null);
            return execution;
        } catch (BizException e) {
            // 触发失败(计划过期、任务被下线)不该让调度器停摆。记下来,
            // 推进到下一次触发点,继续服务其它任务。
            recordFire(definition, scheduledAt, now, "FAILED", null, e.getMessage());
            log.warn("调度触发失败 job={} 原因={}", definition.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 并发策略判定(功能 16 的 misfirePolicy)。
     *
     * @return 跳过的原因;null 表示可以触发
     */
    private String shouldSkip(JobDefinition definition) {
        String policy = definition.getMisfirePolicy();
        if ("CONCURRENT".equals(policy)) {
            return null;
        }

        Long running = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getJobRefId, definition.getId())
                .in(Execution::getStatus, ExecutionStatus.PENDING, ExecutionStatus.DISPATCHED,
                        ExecutionStatus.RUNNING, ExecutionStatus.CANCELING));
        if (running == null || running == 0) {
            return null;
        }

        // QUEUE 与 SKIP 在这一步的行为相同 —— 都不触发。区别在于 QUEUE 会在
        // 上一次结束时补跑一次,那是 ExecutionFinished 事件的消费者做的事,
        // 不在这里。写成同一个分支并注明,好过写两段一样的代码假装它们不同。
        return "上一次执行尚未结束(策略 %s,当前有 %d 个未完成实例)"
                .formatted(policy == null ? "SKIP" : policy, running);
    }

    private void recordFire(JobDefinition definition, Instant scheduledAt, Instant firedAt,
                            String outcome, String executionId, String reason) {
        ScheduleFire fire = new ScheduleFire();
        fire.setId(Ids.of("fire"));
        fire.setJobDefinitionId(definition.getId());
        fire.setWorkspaceId(definition.getWorkspaceId());
        fire.setScheduledAt(scheduledAt);
        fire.setFiredAt(firedAt);
        fire.setOutcome(outcome);
        fire.setExecutionId(executionId);
        fire.setReason(reason == null ? null
                : reason.length() > 512 ? reason.substring(0, 512) : reason);
        fireMapper.insert(fire);
    }

    private static DispatchCommand.RetryPolicy retryPolicyOf(JobDefinition definition) {
        if (definition.getRetryMaxAttempts() == null || definition.getRetryMaxAttempts() <= 1) {
            return DispatchCommand.RetryPolicy.none();
        }
        int backoff = definition.getRetryBackoffSeconds() == null
                ? 30 : definition.getRetryBackoffSeconds();
        return new DispatchCommand.RetryPolicy(definition.getRetryMaxAttempts(), backoff, 2.0);
    }
}
