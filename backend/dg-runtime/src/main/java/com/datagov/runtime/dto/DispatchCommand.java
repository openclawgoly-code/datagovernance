package com.datagov.runtime.dto;

import com.datagov.runtime.domain.JobRefType;

import java.util.Map;

/**
 * Control → Runtime 的下发命令。
 *
 * <p><b>它必须自足。</b> Runtime 拿到这个对象之后不该、也没能力再回头去查任何
 * 定义 —— dg-runtime 的 pom 里根本没有 dg-metadata 依赖。这不是洁癖:一旦
 * 执行途中去读一次定义,Execution 就不再绑定它启动时的 {@link #defVersion},
 * 「可复现」这条保证当场失效,而失效的方式是悄无声息的。
 *
 * <p>因此 {@link #plan} 里装的是<b>编译好的物理计划</b>,不是定义的引用。
 * Control 负责把定义编译成计划,Runtime 负责照着计划跑。
 *
 * @param workspaceId  租户作用域,一路带到执行器
 * @param jobRefType   作业种类
 * @param jobRefId     定义 ID —— 只作为回查线索,Runtime 自己不解引用
 * @param jobName      定义名快照,给列表页显示用
 * @param defVersion   定义版本,钉死可复现性
 * @param plan         物理计划。结构由 jobRefType 决定,Runtime 原样透传给执行器
 * @param retryPolicy  重试策略。<b>由 Control 决定</b> —— Runtime 的 must_not_do
 *                     里写着「不得自行决定是否重试」
 * @param timeoutMs    执行超时;超过则进 TIMEOUT 而不是一直挂着
 * @param parentExecutionId 工作流节点的父执行;顶层为 null
 * @param triggerType  MANUAL / SCHEDULE / API / WORKFLOW / RETRY
 * @param triggeredBy  触发人;调度触发时是调度器的标识
 */
public record DispatchCommand(
        String workspaceId,
        JobRefType jobRefType,
        String jobRefId,
        String jobName,
        Integer defVersion,
        Map<String, Object> plan,
        RetryPolicy retryPolicy,
        Long timeoutMs,
        String parentExecutionId,
        String triggerType,
        String triggeredBy
) {

    /** 默认执行超时 2 小时。没有超时的执行会永远占着执行器的并发额度。 */
    public static final long DEFAULT_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    public DispatchCommand {
        plan = plan == null ? Map.of() : Map.copyOf(plan);
        retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
        timeoutMs = timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        triggerType = triggerType == null ? "MANUAL" : triggerType;
    }

    /**
     * 重试策略。
     *
     * <p>退避是必需的而不是可选的:目标库正忙的时候,立刻重试三次只会让它更忙。
     *
     * @param maxAttempts     总尝试次数(含首次)。1 表示不重试
     * @param backoffSeconds  首次重试前的等待
     * @param backoffMultiplier 退避倍数,每次重试后乘上去
     */
    public record RetryPolicy(int maxAttempts, int backoffSeconds, double backoffMultiplier) {

        public static final int MAX_ALLOWED_ATTEMPTS = 10;

        public RetryPolicy {
            if (maxAttempts < 1) {
                maxAttempts = 1;
            }
            if (maxAttempts > MAX_ALLOWED_ATTEMPTS) {
                maxAttempts = MAX_ALLOWED_ATTEMPTS;
            }
            if (backoffSeconds < 0) {
                backoffSeconds = 0;
            }
            if (backoffMultiplier < 1.0) {
                backoffMultiplier = 1.0;
            }
        }

        public static RetryPolicy none() {
            return new RetryPolicy(1, 0, 1.0);
        }

        /** 默认:最多 3 次,30 秒起步,每次翻倍(30s → 60s) */
        public static RetryPolicy defaults() {
            return new RetryPolicy(3, 30, 2.0);
        }

        public boolean allowsRetry(int attemptNo) {
            return attemptNo < maxAttempts;
        }

        /** 第 attemptNo 次尝试失败后,下一次该等多久 */
        public long backoffMillisAfter(int attemptNo) {
            if (backoffSeconds == 0) {
                return 0;
            }
            double seconds = backoffSeconds * Math.pow(backoffMultiplier, Math.max(0, attemptNo - 1));
            return (long) (seconds * 1000);
        }
    }
}
