package com.datagov.app.runner;

import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实时开发任务(序号 18)的执行。
 *
 * <p><b>常驻作业没有本地实现,这里如实失败。</b> 一个流作业要跑起来需要 Flink
 * 集群 —— 那是 {@code ExecutionEngine}(用什么跑)的事,接入时新增一个
 * FlinkExecutionEngine 即可,本类与上层的编译、状态机、执行记录都不必改。
 *
 * <p>没有写成"返回成功、处理 0 行"的假实现,是因为那会让整条链路的验证失去
 * 意义:编译、发布、启动、执行记录、保活状态机全都"通过"了,而实际上什么
 * 都没发生 —— 而这个平台恰恰是靠"跑没跑"这件事来判断任务好坏的。
 *
 * <p>失败之后 {@code StreamingJobService} 那台状态机照常工作:STARTING → FAILED
 * (启动就起不来,不进重启)。那部分是平台<b>自己的</b>逻辑,不依赖 Flink,
 * 所以它是真的被验证过的。
 */
@Component
public class StreamingDevRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(StreamingDevRunner.class);

    @Override
    public JobRefType jobRefType() {
        return JobRefType.STREAMING_DEV;
    }

    @Override
    public RunResult run(RunContext context) {
        Map<String, Object> plan = context.plan();
        log.info("实时作业待提交给 Flink execution={} sourceKind={} parallelism={} checkpoint={}",
                context.executionId(), plan.get("sourceKind"), plan.get("parallelism"),
                plan.get("checkpointIntervalMs"));

        throw new UnsupportedOperationException(
                "实时任务需要 Flink 引擎,当前部署未接入(作业形态=%s,并行度=%s)"
                        .formatted(plan.getOrDefault("sourceKind", "SQL"),
                                plan.getOrDefault("parallelism", 1)));
    }

    @Override
    public String classify(Exception e) {
        // 环境缺件而非平台故障。这个区分决定了值班的人第一步该找运维还是找开发
        return e instanceof UnsupportedOperationException
                ? "RTM_EXECUTOR_UNAVAILABLE"
                : "SYS_INTERNAL_ERROR";
    }
}
