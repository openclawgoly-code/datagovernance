package com.datagov.runtime.spi;

import com.datagov.runtime.domain.JobRefType;

import java.util.Set;

/**
 * 执行引擎 SPI —— Runtime 与"真正干活的东西"之间的唯一接缝。
 *
 * <p>P2 只有一个实现(进程内线程池)。Contract 里写的 Flink / K8s 是 P3 的事。
 * 现在就把接缝留出来,是因为它的形状由<b>调用方</b>的需要决定,而调用方现在就在
 * 写:提交、取消、查状态。等到接 Flink 时再抽象,接口形状会被 Flink 的 API 带跑,
 * 那正是 Runtime 的 must_not_do 最后一条要防的事 ——「不得让 Flink 的类型/异常/
 * JobID 语义穿透到 control 之上」。
 *
 * <p>实现<b>不得抛异常表达业务失败</b>:目标库连不上、SQL 写错了,都要通过
 * {@link EngineHandle} 的回调报告成失败结果。异常只留给"引擎自己坏了"。
 * 这与 dg-data-spi 的连接器契约是同一条规矩,理由也一样:调用方不必 try/catch
 * 就能把结果呈现给用户。
 */
public interface ExecutionEngine {

    /** 引擎标识,写进 {@code rt_execution_attempt.executor_id} 便于排障 */
    String engineKind();

    /** 本引擎能跑哪些作业种类。不支持的种类由网关分派给别的引擎 */
    Set<JobRefType> supportedTypes();

    /**
     * 提交一次尝试。
     *
     * <p><b>必须立即返回</b>,不得阻塞到执行结束 —— 调用它的是一个 HTTP 请求
     * 或一次调度触发,同步等待会让下发这一步的延迟等于任务本身的时长。
     * 进度与结果通过 {@code callback} 异步回报。
     *
     * @return 引擎侧的作业 ID,写进 attempt 便于对账;拿不到时返回 null
     */
    String submit(EngineTask task, EngineCallback callback);

    /**
     * 请求取消。
     *
     * <p>返回 true 只表示"取消请求已送达",不表示已经停下来 —— 所以状态机里
     * 有 CANCELING 这个中间态。真正停下来时由 {@code callback} 报告。
     */
    boolean cancel(String engineJobId);

    /** 一次待执行的尝试。plan 的结构由 jobRefType 约定,引擎自行解读。 */
    record EngineTask(
            String executionId,
            String attemptId,
            String workspaceId,
            JobRefType jobRefType,
            java.util.Map<String, Object> plan,
            long timeoutMs
    ) {
    }

    /**
     * 引擎回报执行事实的通道。
     *
     * <p>只有这四个回调,没有"报告任意状态"的通用方法:引擎能表达的事实是有限的,
     * 把它限死可以防止执行侧越过状态机直接改状态。
     */
    interface EngineCallback {

        /** 已经真的开始跑了(区别于"已受理") */
        void onStarted(String attemptId, String engineJobId);

        /** 进度。允许高频调用,实现方自行做节流 */
        void onProgress(String attemptId, EngineMetric metric);

        void onSucceeded(String attemptId, EngineMetric metric);

        /**
         * @param errorCode 归一化的错误分类,不是引擎原始错误码
         * @param detail    引擎原始报错,可以很长,由 Runtime 负责截断
         * @param unsafeToRetry 本次尝试是否已经提交了<b>重投会重复</b>的写入。
         *                      true 时 Runtime 不再重投 —— 重投会把已落盘的行
         *                      再写一遍,而任务最终还是失败的,用户看到的是
         *                      "任务失败了",不会想到它顺手往目标表塞了三份数据。
         *                      引擎报不出这个事实时填 false(等于维持原来的行为)
         */
        void onFailed(String attemptId, String message, String errorCode, String detail,
                      boolean unsafeToRetry);

        /** 取消已经生效 */
        void onCanceled(String attemptId);
    }

    /** 执行指标 —— 序号 24 监控的唯一数据来源 */
    record EngineMetric(long rowsRead, long rowsWritten, long bytesProcessed) {

        public static EngineMetric empty() {
            return new EngineMetric(0, 0, 0);
        }
    }
}
