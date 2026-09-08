package com.datagov.runtime.domain;

import com.datagov.common.lifecycle.StateMachine;

import static com.datagov.runtime.domain.ExecutionStatus.CANCELED;
import static com.datagov.runtime.domain.ExecutionStatus.CANCELING;
import static com.datagov.runtime.domain.ExecutionStatus.DISPATCHED;
import static com.datagov.runtime.domain.ExecutionStatus.FAILED;
import static com.datagov.runtime.domain.ExecutionStatus.PENDING;
import static com.datagov.runtime.domain.ExecutionStatus.RUNNING;
import static com.datagov.runtime.domain.ExecutionStatus.SUCCEEDED;
import static com.datagov.runtime.domain.ExecutionStatus.TIMEOUT;

/**
 * Execution 状态机(SPACE-MODEL.md E.4)。
 *
 * <p>触发器名与 Contract 里的 Command / Event 名字面一致 —— 这样 E 章节(状态机)
 * 与 F 章节(Command/Query/Event)不会各说各话。
 */
public final class ExecutionLifecycle {

    public static final StateMachine<ExecutionStatus> MACHINE =
            StateMachine.builder(ExecutionStatus.class, PENDING)
                    .allow(PENDING, DISPATCHED, "DispatchExecution")
                    // 下发就被拒(没有健康执行器、配额不足)。必须是显式的失败,
                    // 不能让它停在 PENDING —— 那样"这个任务没跑"这件事会静默丢失,
                    // 而用户在列表里看到的是一个永远"待下发"的行。
                    .allow(PENDING, FAILED, "DispatchRejected")
                    // 还没下发出去就被取消:不需要惊动执行器,直接落终态
                    .allow(PENDING, CANCELED, "CancelExecution")

                    .allow(DISPATCHED, RUNNING, "ExecutionStarted")
                    // 执行器接了但起不来(镜像拉不动、JAR 缺失)
                    .allow(DISPATCHED, FAILED, "ExecutionFailed")
                    .allow(DISPATCHED, CANCELING, "CancelExecution")
                    .allow(DISPATCHED, TIMEOUT, "ExecutionTimedOut")

                    .allow(RUNNING, SUCCEEDED, "ExecutionSucceeded")
                    .allow(RUNNING, FAILED, "ExecutionFailed")
                    .allow(RUNNING, CANCELING, "CancelExecution")
                    .allow(RUNNING, TIMEOUT, "ExecutionTimedOut")

                    // 取消不是瞬时的:执行器要收尾(落 savepoint、断开连接)。
                    // 中间态存在的意义是让界面能显示"正在取消",而不是点完按钮
                    // 状态纹丝不动、用户以为没点上。
                    .allow(CANCELING, CANCELED, "ExecutionCanceled")
                    // 取消途中任务自己跑完了 —— 竞态,而且是常态。据实记录,
                    // 不能因为"我们下过取消命令"就把一个成功的执行记成 CANCELED。
                    .allow(CANCELING, SUCCEEDED, "ExecutionSucceeded")
                    .allow(CANCELING, FAILED, "ExecutionFailed")

                    .terminal(SUCCEEDED)
                    .terminal(FAILED)
                    .terminal(CANCELED)
                    .terminal(TIMEOUT)
                    .build();

    private ExecutionLifecycle() {
    }

    /**
     * 重试是否被允许。
     *
     * <p><b>重试不改变 Execution 的状态,它新增一次 {@code ExecutionAttempt}。</b>
     * 这条是 R4 的直接推论:如果重试新建一个 Execution,序号 24 的"执行总数"
     * 会被重试次数污染 —— 一个重试三次才成功的任务会被记成四个任务。
     *
     * <p>因此本方法回答的不是"能不能迁移到某个状态",而是"这个执行是否处在
     * 可以再试一次的终态"。取消过的不重试(是人叫停的),成功的不重试。
     */
    public static boolean retryable(ExecutionStatus status) {
        return status == FAILED || status == TIMEOUT;
    }
}
