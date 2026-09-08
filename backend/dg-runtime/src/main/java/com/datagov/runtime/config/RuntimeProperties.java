package com.datagov.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime 的可调参数。
 *
 * <p>默认值按"与 Web 同进程"设定(SPACE-MODEL.md I.4 的第一阶段部署形态):
 * 执行并发保持克制,给 Web 请求留出余量。拆进程之后这些值应当调大。
 */
@ConfigurationProperties(prefix = "dg.runtime")
public class RuntimeProperties {

    private int corePoolSize = 2;
    private int maxPoolSize = 8;
    private int queueCapacity = 64;

    /** 执行超时巡检间隔(毫秒) */
    private long timeoutSweepIntervalMs = 60_000;
    /** 每次巡检最多处理多少条,避免一次扫描拖住整个事务 */
    private int timeoutSweepBatchSize = 100;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getTimeoutSweepIntervalMs() {
        return timeoutSweepIntervalMs;
    }

    public void setTimeoutSweepIntervalMs(long timeoutSweepIntervalMs) {
        this.timeoutSweepIntervalMs = timeoutSweepIntervalMs;
    }

    public int getTimeoutSweepBatchSize() {
        return timeoutSweepBatchSize;
    }

    public void setTimeoutSweepBatchSize(int timeoutSweepBatchSize) {
        this.timeoutSweepBatchSize = timeoutSweepBatchSize;
    }
}
