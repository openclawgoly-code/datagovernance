package com.datagov.runtime.engine;

import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.spi.ExecutionEngine;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一种作业的实际执行逻辑。
 *
 * <p>这是 Runtime 内部的扩展点,不是 Space 边界的 SPI —— 区别在于:
 * {@link ExecutionEngine} 面向"用什么跑"(本地 / Flink / K8s),JobRunner 面向
 * "跑什么"(整库迁移 / 离线同步 / 文件解析)。两者正交,所以是两个接口而不是一个。
 *
 * <p>实现方<b>可以</b>抛异常表达失败(与 SPI 边界的约定相反),由
 * {@link LocalExecutionEngine} 统一翻译成失败回调。让每个 runner 各写一遍
 * try/catch 必然有人漏,而漏掉的后果是任务永远停在 RUNNING。
 */
public interface JobRunner {

    /** 本 runner 承接的作业种类。一种作业只能有一个 runner。 */
    JobRefType jobRefType();

    RunResult run(RunContext context) throws Exception;

    /**
     * 把异常归类成平台错误码。
     *
     * <p>默认归为内部错误。实现方应当区分"目标端的问题"与"平台的问题" ——
     * 值班的人第一件事就是判断该找 DBA 还是找开发。
     */
    default String classify(Exception e) {
        return "SYS_INTERNAL_ERROR";
    }

    /**
     * 一次运行的上下文。
     *
     * @param canceled 协作取消旗。<b>长循环必须定期查它</b> —— 线程中断对
     *                 阻塞在 JDBC 上的调用基本无效,主动退出是唯一可靠的路径
     * @param progress 进度回报。实现方自行节流,不要每行都调
     * @param unsafeToRetrySink 见 {@link #markUnsafeToRetry()}
     */
    record RunContext(
            String executionId,
            String attemptId,
            String workspaceId,
            Map<String, Object> plan,
            BooleanSupplier canceled,
            Consumer<ExecutionEngine.EngineMetric> progress,
            Runnable unsafeToRetrySink
    ) {

        public boolean isCanceled() {
            return canceled.getAsBoolean();
        }

        /**
         * 声明本次尝试已经<b>提交</b>了重投会重复的写入。
         *
         * <p>为什么需要它:写入是分批提交的(几百万行不可能攒在一个事务里),
         * 而重试重投的是<b>整个任务</b> —— runner 从源端第一行重新读起,它没有
         * 断点。于是 APPEND 模式下第一次尝试提交过的行,会在第二次尝试里再插一遍。
         * 任务最终报 FAILED,目标表里却躺着几份重复数据。
         *
         * <p>调用它之后,这次失败就不再重投(Runtime 侧据此决定),失败原因里
         * 会说明为什么。
         *
         * <p><b>什么时候<u>不</u>该调</b>:写入本身幂等的时候。OVERWRITE 每次开写前
         * 先清空目标表,重投多少次结果都一样 —— 禁掉它的重试是白白的损失。连不上
         * 目标库、认证过期、源端超时这些失败一行都没写,更不该调:重试正是为它们
         * 准备的。
         *
         * <p>该在<b>第一次真正 commit 的地方</b>调,不是在抛异常的地方 ——
         * 后者要求每个 runner 都记得包一层,总会有人漏,而漏掉的后果是静默写重。
         */
        public void markUnsafeToRetry() {
            unsafeToRetrySink.run();
        }

        /** 取消时抛出,让调用栈直接退出。由引擎翻译成 onCanceled。 */
        public void throwIfCanceled() throws InterruptedException {
            if (isCanceled()) {
                throw new InterruptedException("执行已被取消");
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T planValue(String key, T defaultValue) {
            Object value = plan.get(key);
            return value == null ? defaultValue : (T) value;
        }

        public String requirePlanString(String key) {
            Object value = plan.get(key);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("物理计划缺少必填项: " + key);
            }
            return value.toString();
        }
    }

    /** 运行结果。失败走异常,所以这里只有成功的形状。 */
    record RunResult(ExecutionEngine.EngineMetric metric) {

        public static RunResult of(long rowsRead, long rowsWritten, long bytes) {
            return new RunResult(new ExecutionEngine.EngineMetric(rowsRead, rowsWritten, bytes));
        }

        public static RunResult empty() {
            return new RunResult(ExecutionEngine.EngineMetric.empty());
        }
    }
}
