package com.datagov.control.service;

import com.datagov.control.entity.ControlEntities.JobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度触发器 —— 到点把 SCHEDULING 中的任务变成一次 Execution。
 *
 * <p>由 dg-app 的定时任务按固定间隔调用 {@link #tick()}。为什么不用
 * {@code @Scheduled} 直接标在这里:Control 是一个库模块,定时任务的开关与频率
 * 属于装配层的决定 —— 拆进程之后调度器可能只在其中一个副本上跑。
 *
 * <p><b>关于 exactly-once 触发语义:</b> Contract 要求「调度触发需分布式锁保证
 * exactly-once 触发语义」。P2 是单实例部署,这里用一个进程内的 {@link AtomicBoolean}
 * 防止上一轮还没跑完就开下一轮 —— 那是<b>单实例</b>下的正确做法,但它<b>不是</b>
 * 分布式锁。多实例部署前必须换成 Redis 或数据库行锁,否则每个副本都会触发一次,
 * 同一个任务在同一时刻跑出 N 个实例。这个缺口是显式的、有边界的,而不是被
 * 一个"看起来像锁"的实现掩盖住 —— 后者更危险。
 */
@Component
public class ScheduleTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTrigger.class);

    /** 一轮最多处理多少个任务。防止一次积压把整轮拖成长事务。 */
    private static final int BATCH_SIZE = 50;

    private final JobDefinitionService jobService;
    private final JobExecutionService executionService;

    /** 上一轮是否还在跑。见类注释:这不是分布式锁。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public ScheduleTrigger(JobDefinitionService jobService, JobExecutionService executionService) {
        this.jobService = jobService;
        this.executionService = executionService;
    }

    /**
     * 跑一轮:把所有 nextFireAt 已到的任务触发掉。
     *
     * @return 本轮实际触发出的执行数(不含跳过与失败)
     */
    public int tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一轮调度尚未结束,跳过本轮");
            return 0;
        }
        try {
            Instant now = Instant.now();
            List<JobDefinition> due = jobService.findDueForFire(now, BATCH_SIZE);
            if (due.isEmpty()) {
                return 0;
            }

            int fired = 0;
            for (JobDefinition definition : due) {
                // 记下计划触发时刻再推进 —— 触发日志里"本该几点跑"和"实际几点跑"
                // 是两个不同的问题,合成一个就没法回答"调度延迟了多久"
                Instant scheduledAt = definition.getNextFireAt();
                try {
                    if (executionService.fireScheduled(definition, scheduledAt) != null) {
                        fired++;
                    }
                } catch (RuntimeException e) {
                    // 一个任务出问题不能带走整轮。它的 nextFireAt 照样推进,
                    // 否则下一轮会再次取到它,陷入一个永远失败的循环。
                    log.error("调度触发异常 job={}", definition.getId(), e);
                } finally {
                    jobService.advanceNextFire(definition, now);
                }
            }
            if (fired > 0) {
                log.info("本轮调度触发 {} 个执行(候选 {} 个)", fired, due.size());
            }
            return fired;
        } finally {
            ticking.set(false);
        }
    }
}
