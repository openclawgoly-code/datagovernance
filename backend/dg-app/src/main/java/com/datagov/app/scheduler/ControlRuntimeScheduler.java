package com.datagov.app.scheduler;

import com.datagov.control.service.ScheduleTrigger;
import com.datagov.control.service.StreamingJobService;
import com.datagov.control.service.WorkflowOrchestrator;
import com.datagov.runtime.config.RuntimeProperties;
import com.datagov.runtime.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Control 调度触发 + Runtime 超时巡检的定时入口。
 *
 * <p>两个定时任务放在一个类里,因为它们共享同一个"多实例部署时该怎么办"的问题:
 * 调度触发必须 exactly-once(否则一个任务在同一时刻跑出 N 个实例),超时巡检
 * 则天然幂等(重复标记同一条超时记录只会失败在状态机上)。两者的答案不同,
 * 但要一起考虑,所以放在一起并在此写明。
 *
 * <p><b>定时任务的开关归装配层</b>,不归 Control 或 Runtime。理由见
 * {@code ScheduleTrigger} 的类注释:拆进程之后调度器可能只在其中一个副本上跑,
 * 那是部署决策,不是领域逻辑。
 */
@Component
@ConfigurationProperties(prefix = "dg.scheduler")
public class ControlRuntimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ControlRuntimeScheduler.class);

    private final ScheduleTrigger scheduleTrigger;
    private final ExecutionService executionService;
    private final RuntimeProperties runtimeProperties;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final StreamingJobService streamingJobService;

    /**
     * 总开关。
     *
     * <p>多实例部署时应只让一个实例开着 —— 见 ScheduleTrigger 的说明,
     * 它的并发保护是进程内的,不是分布式锁。
     */
    private boolean enabled = true;

    public ControlRuntimeScheduler(ScheduleTrigger scheduleTrigger,
                                   ExecutionService executionService,
                                   RuntimeProperties runtimeProperties,
                                   WorkflowOrchestrator workflowOrchestrator,
                                   StreamingJobService streamingJobService) {
        this.scheduleTrigger = scheduleTrigger;
        this.executionService = executionService;
        this.runtimeProperties = runtimeProperties;
        this.workflowOrchestrator = workflowOrchestrator;
        this.streamingJobService = streamingJobService;
    }

    /**
     * 每 10 秒看一次有没有到点的任务。
     *
     * <p>10 秒不是随手定的:Cron 的最小间隔是 1 分钟,扫描周期取它的六分之一,
     * 让调度延迟稳定在 10 秒以内。取更短会让空转的查询变多,取更长则一个
     * "每分钟执行"的任务可能被推迟接近一个周期。
     */
    @Scheduled(fixedDelayString = "${dg.scheduler.tick-interval-ms:10000}")
    public void tickSchedules() {
        if (!enabled) {
            return;
        }
        try {
            scheduleTrigger.tick();
        } catch (RuntimeException e) {
            // 调度线程里任何未捕获异常都会让后续调度停摆,必须兜住
            log.error("调度轮询失败,本轮跳过", e);
        }
    }

    /**
     * 超时巡检。
     *
     * <p>必须有:引擎可能整个失联,那时没人会来报告超时,执行会永远停在 RUNNING
     * 占着一个并发额度 —— 攒够并发上限之后,新任务全部下发被拒。
     */
    @Scheduled(fixedDelayString = "${dg.runtime.timeout-sweep-interval-ms:60000}")
    public void sweepTimeouts() {
        if (!enabled) {
            return;
        }
        try {
            int swept = executionService.sweepTimeouts(
                    Instant.now(), runtimeProperties.getTimeoutSweepBatchSize());
            if (swept > 0) {
                log.warn("本轮标记 {} 个超时执行", swept);
            }
        } catch (RuntimeException e) {
            log.error("超时巡检失败,本轮跳过", e);
        }
    }

    /**
     * 工作流推进(序号 22)。
     *
     * <p><b>拉模式,不是回调。</b> 回调看起来更实时,但它要求 Runtime 知道
     * "我是某个工作流的第三个节点" —— 那就把编排语义泄露进了执行引擎,而
     * Runtime 的 must_not_do 第一条正是「不得解释业务语义」。轮询的代价是
     * 几秒延迟,换来的是执行引擎完全不知道工作流的存在。
     */
    @Scheduled(fixedDelayString = "${dg.scheduler.workflow-tick-interval-ms:5000}")
    public void tickWorkflows() {
        if (!enabled) {
            return;
        }
        try {
            for (String[] row : executionService.activeWorkflowExecutions(50)) {
                try {
                    workflowOrchestrator.advanceAs(row[1], row[0]);
                } catch (RuntimeException e) {
                    // 一个工作流推进失败不该拖垮同一轮里的其他工作流
                    log.error("工作流推进失败 execution={},本轮跳过", row[0], e);
                }
            }
        } catch (RuntimeException e) {
            log.error("工作流轮询失败,本轮跳过", e);
        }
    }

    /**
     * 流任务运行态对账(序号 18)。
     *
     * <p>必须有:进程重启、回调丢失都会让运行态与执行记录脱节。没有对账,
     * 界面会永远显示一个"运行中"但其实早就死了的任务 —— 那比显示"失败"
     * 更糟,因为没人会去查一个看起来正常的任务。
     */
    @Scheduled(fixedDelayString = "${dg.scheduler.streaming-reconcile-interval-ms:30000}")
    public void reconcileStreaming() {
        if (!enabled) {
            return;
        }
        try {
            streamingJobService.reconcile();
        } catch (RuntimeException e) {
            log.error("流任务对账失败,本轮跳过", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
