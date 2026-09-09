package com.datagov.runtime.service;

import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按作业种类挑执行引擎,并把引擎的回调转交给 {@link ExecutionService}。
 *
 * <p>它存在的理由是打断一个循环:ExecutionService 要提交给引擎,引擎要把结果
 * 回报给 ExecutionService。两者直接互相注入会形成构造期循环依赖。Registry 站在
 * 中间,{@code @Lazy} 只用在回报这一个方向上 —— 提交方向是构造期就要确定的。
 *
 * <p><b>选择策略故意保持愚蠢:</b> 一个 jobRefType 对一个引擎,先注册的赢。
 * 真正的资源选择(按负载、按标签、按空间配额)属于 Control 的 ResourceRequirement,
 * 不属于这里。在 Runtime 里做智能调度,等于把 Control 的职责搬进了执行侧。
 */
@Component
public class EngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    private final Map<JobRefType, ExecutionEngine> byType = new EnumMap<>(JobRefType.class);
    private final ExecutionEngine.EngineCallback callback;

    public EngineRegistry(List<ExecutionEngine> engines, @Lazy ExecutionService executionService) {
        for (ExecutionEngine engine : engines) {
            for (JobRefType type : engine.supportedTypes()) {
                ExecutionEngine existing = byType.putIfAbsent(type, engine);
                if (existing != null) {
                    log.warn("作业种类 {} 已由 {} 承接,忽略 {}",
                            type, existing.engineKind(), engine.engineKind());
                }
            }
        }
        log.info("执行引擎注册完成: {} 个引擎覆盖 {} 种作业", engines.size(), byType.size());

        this.callback = new ExecutionEngine.EngineCallback() {
            @Override
            public void onStarted(String attemptId, String engineJobId) {
                executionService.onStarted(attemptId, engineJobId);
            }

            @Override
            public void onProgress(String attemptId, ExecutionEngine.EngineMetric metric) {
                executionService.onProgress(attemptId, metric);
            }

            @Override
            public void onSucceeded(String attemptId, ExecutionEngine.EngineMetric metric) {
                executionService.onSucceeded(attemptId, metric);
            }

            @Override
            public void onFailed(String attemptId, String message, String errorCode, String detail,
                                 boolean unsafeToRetry) {
                executionService.onFailed(attemptId, message, errorCode, detail, unsafeToRetry);
            }

            @Override
            public void onCanceled(String attemptId) {
                executionService.onCanceled(attemptId);
            }
        };
    }

    /** @return 承接该种作业的引擎;没有则 null,由调用方落 DispatchRejected */
    public ExecutionEngine select(JobRefType type) {
        return byType.get(type);
    }

    public ExecutionEngine.EngineCallback callback() {
        return callback;
    }

    /** 已覆盖的作业种类,供健康检查与自检使用 */
    public java.util.Set<JobRefType> coveredTypes() {
        return java.util.Set.copyOf(byType.keySet());
    }
}
