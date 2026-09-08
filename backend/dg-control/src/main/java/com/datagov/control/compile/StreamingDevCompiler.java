package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.runtime.service.ArtifactService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实时开发任务(序号 18)的编译器。
 *
 * <p>与离线开发共享绝大部分校验(见 {@link DevJobCompiler}),区别只在这里的
 * 三条:checkpoint 间隔、重启策略、以及<b>不许绑 Cron</b>。
 *
 * <p>最后一条不是编译器的额外规矩,而是 {@code JobType.STREAMING.isSchedulable()}
 * 已经声明的事实 —— 一个常驻作业没有"每天两点再跑一次"这回事。这里只是把
 * 那个声明落到诊断上,让用户看到的是一句人话而不是一个 409。
 *
 * <p>配置形状:
 * <pre>
 * sourceKind        SQL | JAR | PYTHON
 * sql / artifactId + entryClass + programArgs
 * parallelism       并行度
 * checkpointIntervalMs   checkpoint 间隔
 * restartStrategy   FIXED_DELAY | EXPONENTIAL | NONE
 * engineConfig      引擎参数透传
 * </pre>
 */
@Component
public class StreamingDevCompiler extends DevJobCompiler {

    /**
     * checkpoint 间隔下限。
     *
     * <p>比这更密的 checkpoint 会让作业把大半时间花在存状态上。这是一个
     * 经验阈值,不是物理限制 —— 所以给的是警告而非错误。
     */
    private static final long MIN_CHECKPOINT_MS = 1_000L;

    private final ArtifactService artifactService;

    public StreamingDevCompiler(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Override
    public JobType jobType() {
        return JobType.STREAMING;
    }

    @Override
    protected String planKind() {
        return "STREAMING_DEV";
    }

    @Override
    protected boolean artifactExists(CompileContext context, String artifactId) {
        return artifactService.existsVisible(
                CompilerSupport.workspaceOf(context.definition()), artifactId);
    }

    @Override
    protected void validateSpecifics(CompileContext context, Map<String, Object> config,
                                     CompileResult.Collector collector) {
        // 常驻作业绑 Cron:定义上就不成立
        if (context.definition().getCronExpression() != null
                && !context.definition().getCronExpression().isBlank()) {
            collector.error(CompileStage.SCHEDULE_VALIDATION, "cronExpression",
                    "实时任务是常驻的,不能绑定周期调度",
                    "它启动后一直跑,「每天两点再跑一次」对它没有意义");
        }

        Long checkpoint = longValue(config.get("checkpointIntervalMs"));
        if (checkpoint != null && checkpoint < MIN_CHECKPOINT_MS) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "checkpointIntervalMs",
                    "checkpoint 间隔 %d 毫秒过密".formatted(checkpoint),
                    "作业会把大半时间花在存状态上;通常 10 秒到 5 分钟之间");
        }
        if (checkpoint == null) {
            // 没有 checkpoint 的流任务重启后从头开始读 —— 对绝大多数场景是错的,
            // 但确实有"只关心当前"的场景,所以是警告
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "checkpointIntervalMs",
                    "没有配置 checkpoint 间隔",
                    "重启后作业将从头开始消费,而不是从上次的位置继续");
        }

        String restart = str(config, "restartStrategy");
        if (restart != null && !restart.isBlank()
                && !java.util.Set.of("FIXED_DELAY", "EXPONENTIAL", "NONE").contains(restart)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "restartStrategy",
                    "未知的重启策略: " + restart,
                    "可选:FIXED_DELAY / EXPONENTIAL / NONE");
        }
    }

    @Override
    protected void enrichPlan(Map<String, Object> plan, Map<String, Object> config) {
        putIfPresent(plan, "checkpointIntervalMs", longValue(config.get("checkpointIntervalMs")));
        plan.put("restartStrategy", config.getOrDefault("restartStrategy", "EXPONENTIAL"));
        // 保活是运行态的事,但阈值由 Control 定并随计划下发 —— Runtime 不自行
        // 决定"重启几次算放弃",那是策略不是机制
        plan.put("maxRestartAttempts",
                com.datagov.runtime.domain.StreamingLifecycle.MAX_RESTART_ATTEMPTS);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
