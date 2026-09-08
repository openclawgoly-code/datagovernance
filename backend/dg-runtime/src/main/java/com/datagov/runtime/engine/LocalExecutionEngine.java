package com.datagov.runtime.engine;

import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内执行引擎 —— P2 唯一的实现。
 *
 * <p><b>它自己不知道怎么跑任何一种作业。</b> 具体怎么把一个物理计划变成对目标库
 * 的读写,由 {@link JobRunner} 的实现提供;本类只负责线程、取消、超时与回调这些
 * 与"跑什么"无关的事。这条分工线是刻意的:接 Flink 时替换的是本类,而 JobRunner
 * 里的业务逻辑原样保留。
 *
 * <p>合并部署阶段(SPACE-MODEL.md I.4)执行与控制在同一进程里,因此线程池必须
 * <b>有界</b>:一个失控的同步任务不该把 Web 请求线程一起饿死。
 */
@Component
public class LocalExecutionEngine implements ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionEngine.class);

    private final ExecutorService pool;
    private final Map<JobRefType, JobRunner> runners;

    /** engineJobId → 该次运行的控制句柄。跑完即移除,不长期占内存。 */
    private final Map<String, RunHandle> running = new ConcurrentHashMap<>();

    public LocalExecutionEngine(ExecutorService runtimeExecutorPool, List<JobRunner> runnerList) {
        this.pool = runtimeExecutorPool;
        this.runners = runnerList.stream().collect(
                java.util.stream.Collectors.toMap(JobRunner::jobRefType, r -> r, (a, b) -> a,
                        () -> new java.util.EnumMap<>(JobRefType.class)));
        log.info("本地执行引擎就绪,承接 {} 种作业: {}", runners.size(), runners.keySet());
    }

    @Override
    public String engineKind() {
        return "LOCAL";
    }

    @Override
    public Set<JobRefType> supportedTypes() {
        return Set.copyOf(runners.keySet());
    }

    @Override
    public String submit(EngineTask task, EngineCallback callback) {
        JobRunner runner = runners.get(task.jobRefType());
        if (runner == null) {
            // 走到这里说明 EngineRegistry 的路由与本引擎的能力声明不一致。
            // 直接回报失败而不是抛异常:SPI 约定业务失败走回调。
            callback.onFailed(task.attemptId(),
                    "本地引擎不支持 " + task.jobRefType(), "RTM_EXECUTOR_UNAVAILABLE", null);
            return null;
        }

        String engineJobId = "local-" + task.attemptId();
        AtomicBoolean canceled = new AtomicBoolean(false);

        Future<?> future = pool.submit(() -> {
            try {
                callback.onStarted(task.attemptId(), engineJobId);
                JobRunner.RunContext context = new JobRunner.RunContext(
                        task.executionId(), task.attemptId(), task.workspaceId(),
                        task.plan(), canceled::get,
                        metric -> callback.onProgress(task.attemptId(), metric));

                JobRunner.RunResult result = runner.run(context);

                if (canceled.get()) {
                    callback.onCanceled(task.attemptId());
                } else {
                    callback.onSucceeded(task.attemptId(), result.metric());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onCanceled(task.attemptId());
            } catch (Exception e) {
                // JobRunner 允许抛异常(它是内部实现,不是 SPI 边界),
                // 由这里统一翻译成失败回调 —— 每个 runner 各写一遍 try/catch
                // 必然会有人漏掉,而漏掉的后果是任务永远停在 RUNNING。
                log.warn("作业执行失败 execution={} type={}",
                        task.executionId(), task.jobRefType(), e);
                callback.onFailed(task.attemptId(), rootMessage(e),
                        runner.classify(e), summarize(e));
            } finally {
                running.remove(engineJobId);
            }
        });

        running.put(engineJobId, new RunHandle(future, canceled));
        return engineJobId;
    }

    @Override
    public boolean cancel(String engineJobId) {
        RunHandle handle = running.get(engineJobId);
        if (handle == null) {
            return false;       // 已经跑完了
        }
        // 先立协作取消的旗,再中断。只中断的话,一个正在等 JDBC 响应的线程
        // 收到中断也未必会停 —— JDBC 驱动大多不响应 interrupt。旗子让 runner
        // 能在自己的循环边界上主动退出,这是唯一可靠的部分。
        handle.canceled().set(true);
        handle.future().cancel(true);
        return true;
    }

    private record RunHandle(Future<?> future, AtomicBoolean canceled) {
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String summarize(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        StackTraceElement[] trace = e.getStackTrace();
        for (int i = 0; i < Math.min(15, trace.length); i++) {
            sb.append("\n\tat ").append(trace[i]);
        }
        return sb.toString();
    }
}
