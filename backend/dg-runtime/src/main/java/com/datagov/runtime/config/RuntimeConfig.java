package com.datagov.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runtime Space 的运行期装配。
 */
@Configuration
@EnableConfigurationProperties(RuntimeProperties.class)
public class RuntimeConfig {

    /**
     * 执行线程池。
     *
     * <p>三个参数都不是随手填的:
     * <ul>
     *   <li><b>有界队列</b> —— 无界队列会把"执行器过载"这件事变成内存无声增长,
     *       直到 OOM 把整个进程带走(Web 请求一起没)。有界之后过载表现为
     *       明确的拒绝,那是可以被记成 DispatchRejected 并告诉用户的。</li>
     *   <li><b>AbortPolicy</b> —— 默认的 AbortPolicy 会抛 RejectedExecutionException,
     *       {@code ExecutionService} 把它记成失败终态。用 CallerRunsPolicy 则会在
     *       Web 线程上跑一个数据同步任务,那是最坏的一种"降级"。</li>
     *   <li><b>命名线程</b> —— 线程转储里能一眼看出是谁在跑,不必靠猜。</li>
     * </ul>
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService runtimeExecutorPool(RuntimeProperties properties) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("dg-exec-" + thread.threadId());
                    // 守护线程:关停时不阻止 JVM 退出。正在跑的执行会被记成
                    // 超时并在下次启动时由 sweepTimeouts 收尾,好过让进程关不掉。
                    thread.setDaemon(true);
                    return thread;
                },
                abortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static RejectedExecutionHandler abortPolicy() {
        return new ThreadPoolExecutor.AbortPolicy();
    }
}
