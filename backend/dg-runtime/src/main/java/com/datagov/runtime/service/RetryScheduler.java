package com.datagov.runtime.service;

import com.datagov.runtime.event.ExecutionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 按退避时长把失败的执行重新投出去。
 *
 * <p>为什么不在 {@code ExecutionService.onFailed} 里直接 sleep 再重投:那是一个
 * 事务方法,睡在里面会把数据库连接一起占住,退避 60 秒就占 60 秒。退避越保守,
 * 连接池被拖垮得越快 —— 一个为了保护目标库的机制反倒先压垮了自己。
 *
 * <p>P2 用进程内的 {@link TaskScheduler}。它的局限要说清楚:<b>进程重启会丢失
 * 待重试的排期</b>。可接受的理由是重试本身就是尽力而为,而丢失的那些会停在
 * 上一次失败的状态上 —— 用户看得见、能手工再触发,不会静默消失。真正的持久化
 * 重试队列属于 Control 的调度器,那里本来就有分布式锁。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ExecutionService executionService;

    public RetryScheduler(TaskScheduler taskScheduler, ExecutionService executionService) {
        this.taskScheduler = taskScheduler;
        this.executionService = executionService;
    }

    @EventListener
    public void onRetryScheduled(ExecutionEvents.ExecutionRetryScheduled event) {
        Instant runAt = Instant.now().plusMillis(event.backoffMillis());
        taskScheduler.schedule(() -> {
            try {
                executionService.retryNow(event.executionId());
            } catch (RuntimeException e) {
                // 重投本身失败不能把调度线程带走 —— 它还要为别的执行服务。
                // 但也<b>不能只记一行日志就算了</b>:此刻执行还停在 DISPATCHED /
                // RUNNING 上,引擎那边却一个线程都没有。没人再来动它,它会一直
                // "在跑"到 timeoutMs 到点被扫成 TIMEOUT —— 用户等的是小时级的
                // 时间,拿到的还是一句盖掉了真实原因的"执行超时"。
                log.error("重试投递失败 execution={}", event.executionId(), e);
                try {
                    executionService.failRetryDelivery(event.executionId(),
                            "重试投递失败: " + e.getMessage());
                } catch (RuntimeException fallback) {
                    log.error("重试投递失败后落终态也失败了 execution={}",
                            event.executionId(), fallback);
                }
            }
        }, runAt);

        log.debug("已排期重试 execution={} 第{}次尝试 于 {}",
                event.executionId(), event.nextAttemptNo(), runAt);
    }
}
