package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.runtime.service.ArtifactService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 离线(批)开发任务(序号 20)的编译器。
 *
 * <p>与实时开发共享绝大部分校验(见 {@link DevJobCompiler})。它的特有部分很少
 * ——批作业没有 checkpoint、没有保活,只有"跑一遍然后结束"。Cron 由通用的
 * 调度校验负责,这里不重复。
 *
 * <p>配置形状:
 * <pre>
 * sourceKind        SQL | JAR | PYTHON
 * sql / artifactId + entryClass + programArgs
 * parallelism       并行度
 * engineConfig      引擎参数透传
 * </pre>
 */
@Component
public class BatchDevCompiler extends DevJobCompiler {

    private final ArtifactService artifactService;

    public BatchDevCompiler(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Override
    public JobType jobType() {
        return JobType.BATCH;
    }

    @Override
    protected String planKind() {
        return "BATCH_DEV";
    }

    @Override
    protected boolean artifactExists(CompileContext context, String artifactId) {
        return artifactService.existsVisible(
                CompilerSupport.workspaceOf(context.definition()), artifactId);
    }

    @Override
    protected void validateSpecifics(CompileContext context, Map<String, Object> config,
                                     CompileResult.Collector collector) {
        // SQL 形态要在某个数据源上跑。JAR 形态不需要 —— 连哪儿是 JAR 自己的事
        if ("SQL".equals(sourceKindOf(config))) {
            String dataSourceId = str(config, "dataSourceId");
            if (isBlank(dataSourceId)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "dataSourceId",
                        "SQL 作业必须指定在哪个数据源上执行");
            } else {
                CompilerSupport.requireAvailableDataSource(collector, context.metadata(),
                        CompilerSupport.workspaceOf(context.definition()), dataSourceId,
                        "dataSourceId", "执行数据源");
            }
        }

        // 批作业里出现 checkpoint 配置,说明用户把它当成流任务在配 —— 那个配置
        // 不会生效,而"配了但不生效"是最难排查的一类问题
        if (config.containsKey("checkpointIntervalMs")) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "checkpointIntervalMs",
                    "批任务不使用 checkpoint,这个配置不会生效",
                    "需要常驻消费的话,该建的是实时任务");
        }
    }

    /**
     * checkpointIntervalMs 列为已知键,是为了让它只触发上面那条说清了原委的
     * 「批任务不使用 checkpoint」提醒,而不是再叠一条泛泛的"平台不认识这个键"。
     */
    @Override
    protected java.util.Set<String> extraKnownKeys() {
        return java.util.Set.of("dataSourceId", "checkpointIntervalMs");
    }

    @Override
    protected void enrichPlan(Map<String, Object> plan, Map<String, Object> config) {
        putIfPresent(plan, "dataSourceId", str(config, "dataSourceId"));
    }

    private static String sourceKindOf(Map<String, Object> config) {
        String kind = str(config, "sourceKind");
        return kind == null || kind.isBlank() ? "SQL" : kind;
    }
}
