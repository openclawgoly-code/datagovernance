package com.datagov.app.runner;

import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Intelligence 的训练 / 预标注作业(序号 34 的契约第 2 条)。
 *
 * <p><b>与实时开发一样,这里如实失败。</b> 训练作业要跑在 K8s 上,当前部署
 * 没有接入 —— 那是一个 {@code ExecutionEngine}(用什么跑)的问题,接入时
 * 新增一个 K8sExecutionEngine 即可,本类与上层的编译、注册、监控都不必改。
 *
 * <p>没有写成"返回成功"的假实现:序号 34 的整个价值建立在"这个模型是用哪版
 * 数据、哪次执行训出来的"这条链上,而一个假成功会在那条链上留下一个不存在的
 * 环节 —— 那比缺一环更糟。
 *
 * <p>失败之前它<b>把契约声明记进日志</b>:输入数据集、输出注册项、资源申请。
 * 这几行日志是接入 K8s 之前唯一能验证"契约字段真的被下发到了执行侧"的东西。
 */
@Component
public class PythonJobRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonJobRunner.class);

    @Override
    public JobRefType jobRefType() {
        return JobRefType.PYTHON_JOB;
    }

    @Override
    public RunResult run(RunContext context) {
        Map<String, Object> plan = context.plan();
        Object inputs = plan.get("inputDatasetIds");
        Object output = plan.get("outputArtifactId");

        log.info("Python 作业待提交给 K8s execution={} entry={} 输入数据集={} 产出注册项={} 资源={}",
                context.executionId(), plan.get("entryModule"),
                inputs instanceof List<?> list ? list.size() + " 个" : "0 个",
                output == null || output.toString().isBlank() ? "(无)" : output,
                plan.get("resources"));

        throw new UnsupportedOperationException(
                "Python 训练作业需要 K8s 引擎,当前部署未接入(入口=%s,输入数据集 %s 个)"
                        .formatted(plan.getOrDefault("entryModule", "?"),
                                inputs instanceof List<?> list ? list.size() : 0));
    }

    @Override
    public String classify(Exception e) {
        // 环境缺件,不是平台故障 —— 与实时开发同一个判断
        return e instanceof UnsupportedOperationException
                ? "RTM_EXECUTOR_UNAVAILABLE"
                : "SYS_INTERNAL_ERROR";
    }
}
