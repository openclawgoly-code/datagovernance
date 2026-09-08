package com.datagov.runtime.domain;

import com.datagov.common.lifecycle.StateMachine;

import static com.datagov.runtime.domain.StreamingStatus.FAILED;
import static com.datagov.runtime.domain.StreamingStatus.PUBLISHED;
import static com.datagov.runtime.domain.StreamingStatus.RESTARTING;
import static com.datagov.runtime.domain.StreamingStatus.RUNNING;
import static com.datagov.runtime.domain.StreamingStatus.STARTING;
import static com.datagov.runtime.domain.StreamingStatus.STOPPED;
import static com.datagov.runtime.domain.StreamingStatus.STOPPING;

/**
 * 流任务运行态状态机(SPACE-MODEL.md E.3,序号 18)。
 *
 * <p>与 {@link ExecutionLifecycle} 并列而不是复用它 —— 见 {@link StreamingStatus}
 * 的类注释。两者唯一的共同点是"都用 StateMachine 声明",而那正是把它们写成
 * 两份的成本上限。
 */
public final class StreamingLifecycle {

    /**
     * 保活重启的次数上限。
     *
     * <p>超过它就落 {@link StreamingStatus#FAILED} 并告警,而不是无限重启:
     * 一个因为 SQL 写错而必然失败的作业,无限重启会在日志里刷出几十万行
     * 相同的堆栈,同时持续占着执行器的并发额度。
     */
    public static final int MAX_RESTART_ATTEMPTS = 5;

    public static final StateMachine<StreamingStatus> MACHINE =
            StateMachine.builder(StreamingStatus.class, PUBLISHED)
                    .allow(PUBLISHED, STARTING, "StartStreamingJob")

                    .allow(STARTING, RUNNING, "StreamingJobRunning")
                    // 提交上去就起不来(JAR 缺失、并行度超配额)。不经过 RESTARTING:
                    // 这类失败重启多少次都是同一个结果
                    .allow(STARTING, FAILED, "StreamingJobFailed")
                    // 启动过程中被叫停 —— 用户点了启动又马上后悔,是常见操作
                    .allow(STARTING, STOPPING, "StopStreamingJob")

                    .allow(RUNNING, RESTARTING, "StreamingJobFailed")
                    .allow(RUNNING, STOPPING, "StopStreamingJob")

                    // 退避重启成功
                    .allow(RESTARTING, RUNNING, "StreamingJobRunning")
                    // 重启途中又挂了 —— 自环。计数器加一,超过阈值才走 FAILED
                    .allow(RESTARTING, RESTARTING, "StreamingJobFailed")
                    .allow(RESTARTING, FAILED, "RestartBudgetExhausted")
                    // 重启途中用户主动停止:停止的意图优先于保活
                    .allow(RESTARTING, STOPPING, "StopStreamingJob")

                    .allow(STOPPING, STOPPED, "StreamingJobStopped")
                    // 停止过程中作业自己挂了。据实记为已停止 —— 用户要的结果
                    // 已经达到,报一个"停止失败"只会让人去点第二次停止
                    .allow(STOPPING, STOPPED, "StreamingJobFailed")

                    // 从 savepoint 恢复。STOPPED 因此不是终态
                    .allow(STOPPED, STARTING, "StartStreamingJob")
                    // 人工修好之后重新拉起
                    .allow(FAILED, STARTING, "StartStreamingJob")

                    // 唯一的终态是"平台已放弃自动恢复"—— 但它仍可被人工重启,
                    // 所以严格地说这个状态机没有不可逃逸的终态。这是流任务与
                    // 批执行最根本的区别,不是遗漏。
                    .build();

    private StreamingLifecycle() {
    }

    /** 失败之后该去哪:还有重启预算就退避重启,用光了就认输。 */
    public static StreamingStatus onFailure(StreamingStatus current, int restartCount) {
        if (current == STOPPING) {
            // 停止过程中的失败不算保活失败:用户已经不要它了
            return STOPPED;
        }
        if (current == STARTING) {
            return FAILED;
        }
        return restartCount >= MAX_RESTART_ATTEMPTS ? FAILED : RESTARTING;
    }

    /**
     * 第 n 次重启前该等多久(毫秒)。
     *
     * <p>指数退避,上限 5 分钟。不退避会在下游持续不可用时把重启变成一次
     * 对下游的压测 —— 而下游正是因为压力才不可用的。
     */
    public static long backoffMillis(int restartCount) {
        long base = 10_000L << Math.min(restartCount, 5);
        return Math.min(base, 300_000L);
    }
}
