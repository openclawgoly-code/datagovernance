package com.datagov.governance.service;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.AlertRule;
import com.datagov.governance.service.AlertRuleService.TriggerType;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.event.ExecutionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 告警评估:执行结束时,看有没有规则被触发(序号 25)。
 *
 * <p><b>它订阅 Runtime 的事件,不调用 Runtime 的接口</b> —— Governance 的
 * must_not_do 第二条是「不得向任何 Space 发出 Command」。这不是形式主义:
 * 一旦这里能调 Control,"失败三次就自动暂停这个任务"必然会被加进来,
 * 而那一行代码会让依赖图变成环。需要干预时它只发告警,由人来决定。
 *
 * <p>评估在<b>独立事务</b>里跑({@code REQUIRES_NEW}):告警落库失败不该
 * 让触发它的那次执行的状态写入一起回滚 —— 那会把一个下游问题变成上游故障。
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final AlertRuleService ruleService;
    private final AlertService alertService;

    public AlertEvaluator(AlertRuleService ruleService, AlertService alertService) {
        this.ruleService = ruleService;
        this.alertService = alertService;
    }

    /**
     * 执行结束。
     *
     * <p>四种触发方式在这里判定。注意 <b>EXECUTION_EMPTY</b>(成功但没写入
     * 任何数据):它在监控上是"成功",而实际往往是上游没数据或过滤条件写错 ——
     * 恰恰是最容易被忽略的一类问题,所以值得单独一种触发方式。
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onExecutionFinished(ExecutionEvents.ExecutionFinished event) {
        // 事件是在执行线程里发的,那里没有登录用户的上下文;而规则查询与告警
        // 落库都要空间隔离。用一个 system 调用者补上,不去借用触发者的身份 ——
        // 告警是平台自己的行为,不该记在某个用户名下
        WorkspaceContext.callAs(systemCaller(event.workspaceId()), () -> {
            try {
                evaluate(event);
            } catch (RuntimeException e) {
                // 告警评估失败绝不能影响别的事情。这里是最后一道防线
                log.error("告警评估失败 execution={}", event.executionId(), e);
            }
            return null;
        });
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDispatchRejected(ExecutionEvents.DispatchRejected event) {
        WorkspaceContext.callAs(systemCaller(event.workspaceId()), () -> {
            try {
                for (AlertRule rule : ruleService.activeRules(event.workspaceId(),
                        TriggerType.DISPATCH_REJECTED)) {
                    alertService.raise(rule,
                            sourceKey(rule.getId(), event.executionId()),
                            "任务下发被拒",
                            "执行 %s 无法下发:%s".formatted(event.executionId(), event.reason()),
                            "CRITICAL", event.executionId(), null, null);
                }
            } catch (RuntimeException e) {
                log.error("下发被拒的告警评估失败 execution={}", event.executionId(), e);
            }
            return null;
        });
    }

    private void evaluate(ExecutionEvents.ExecutionFinished event) {
        for (TriggerType trigger : TriggerType.values()) {
            if (trigger == TriggerType.DISPATCH_REJECTED) {
                continue;   // 它由另一个事件驱动
            }
            List<AlertRule> rules = ruleService.activeRules(event.workspaceId(), trigger);
            if (rules.isEmpty()) {
                continue;
            }
            for (AlertRule rule : rules) {
                if (!inScope(rule, event.jobRefId())) {
                    continue;
                }
                Matched matched = match(trigger, rule, event);
                if (matched != null) {
                    // sourceKey 里带上任务 ID:一个"全部任务"的规则,A 任务的
                    // 告警不该把 B 任务的失败压住
                    alertService.raise(rule,
                            sourceKey(rule.getId(), event.jobRefId()),
                            matched.title(), matched.content(), matched.severity(),
                            event.executionId(), event.jobRefId(), event.jobName());
                }
            }
        }
    }

    private record Matched(String title, String content, String severity) {
    }

    private Matched match(TriggerType trigger, AlertRule rule,
                          ExecutionEvents.ExecutionFinished event) {
        String job = event.jobName() == null ? event.jobRefId() : event.jobName();
        return switch (trigger) {
            case EXECUTION_FAILED -> event.status() == ExecutionStatus.FAILED
                    ? new Matched("任务执行失败:" + job,
                            "任务「%s」的执行 %s 失败(第 %d 次尝试)。%s"
                                    .formatted(job, event.executionId(), event.attemptCount(),
                                            event.message() == null ? "" : event.message()),
                            "CRITICAL")
                    : null;

            case EXECUTION_TIMEOUT -> event.status() == ExecutionStatus.TIMEOUT
                    ? new Matched("任务执行超时:" + job,
                            "任务「%s」的执行 %s 超时被强制结束,已运行 %s 毫秒。"
                                    .formatted(job, event.executionId(),
                                            String.valueOf(event.durationMs())),
                            "CRITICAL")
                    : null;

            case EXECUTION_SLOW -> {
                Long threshold = rule.getThresholdMs();
                Long duration = event.durationMs();
                // 只看跑完的:还在跑的没有最终耗时。超时的另有一种触发方式,
                // 不重复告警
                yield threshold != null && duration != null
                        && event.status() == ExecutionStatus.SUCCEEDED
                        && duration > threshold
                        ? new Matched("任务执行耗时超过阈值:" + job,
                                "任务「%s」本次耗时 %d 毫秒,超过阈值 %d 毫秒。"
                                        .formatted(job, duration, threshold),
                                "WARNING")
                        : null;
            }

            case EXECUTION_EMPTY -> {
                long written = event.rowsWritten() == null ? 0 : event.rowsWritten();
                // 只对"本该搬数据"的作业种类判定 —— 一个批处理 SQL 作业写 0 行
                // 是完全正常的(比如它只做了 DDL)
                yield event.status() == ExecutionStatus.SUCCEEDED
                        && written == 0
                        && DATA_MOVING_TYPES.contains(event.jobRefType().name())
                        ? new Matched("任务成功但没有写入数据:" + job,
                                "任务「%s」的执行 %s 报告成功,但写入行数为 0 —— "
                                        .formatted(job, event.executionId())
                                        + "多半是上游没有数据,或过滤条件把数据全滤掉了。",
                                "WARNING")
                        : null;
            }

            case DISPATCH_REJECTED -> null;
        };
    }

    /** 会搬数据的作业种类。写 0 行对它们才是可疑的 */
    private static final Set<String> DATA_MOVING_TYPES = Set.of(
            "MIGRATION", "OFFLINE_SYNC", "FILE_PARSE", "API_PARSE");

    /** 规则的作用范围:全部任务,还是只盯指定的那几个 */
    private boolean inScope(AlertRule rule, String jobRefId) {
        if (!"SPECIFIC".equals(rule.getScope())) {
            return true;
        }
        return jobRefId != null
                && ruleService.readList(rule.getTargetJobIdsJson()).contains(jobRefId);
    }

    private static String sourceKey(String ruleId, String jobRefId) {
        return ruleId + "#" + (jobRefId == null ? "-" : jobRefId);
    }

    private static Caller systemCaller(String workspaceId) {
        return new Caller("system", "system", workspaceId, true, Set.of());
    }
}
