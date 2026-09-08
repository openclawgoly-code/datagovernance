package com.datagov.control.service;

import com.datagov.control.compile.CompileResult;
import com.datagov.control.compile.CompileStage;
import com.datagov.control.compile.JobCompiler;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按任务类型挑编译器。
 */
@Component
public class CompilerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompilerRegistry.class);

    private final Map<JobType, JobCompiler> byType = new EnumMap<>(JobType.class);
    private final JobCompiler.MetadataLookup metadataLookup;

    public CompilerRegistry(List<JobCompiler> compilers, JobCompiler.MetadataLookup metadataLookup) {
        this.metadataLookup = metadataLookup;
        for (JobCompiler compiler : compilers) {
            JobCompiler existing = byType.putIfAbsent(compiler.jobType(), compiler);
            if (existing != null) {
                log.warn("任务类型 {} 已有编译器 {},忽略 {}", compiler.jobType(),
                        existing.getClass().getSimpleName(), compiler.getClass().getSimpleName());
            }
        }
        log.info("编译器注册完成: {} 种任务类型", byType.size());
    }

    public CompileResult compile(JobDefinition definition, Map<String, Object> config) {
        JobCompiler compiler = byType.get(definition.getJobType());
        if (compiler == null) {
            // 没有编译器不是"编译通过",而是明确的失败。若返回 success,
            // 一个没人会执行的任务会被标成已发布,直到有人问"为什么它从不运行"。
            return CompileResult.failure(List.of(CompileResult.Diagnostic.error(
                    CompileStage.STRUCTURAL_VALIDATION, "jobType",
                    "尚未实现 %s 的编译器".formatted(definition.getJobType().displayName()),
                    "该任务类型将在后续版本支持")));
        }
        try {
            return compiler.compile(new JobCompiler.CompileContext(definition, config, metadataLookup));
        } catch (RuntimeException e) {
            // 编译器自己坏了。SPI 约定编译失败走返回值,所以走到这里说明是 bug ——
            // 但也不能让一个 bug 把用户的保存操作整个打断。
            log.error("编译器异常 id={} type={}", definition.getId(), definition.getJobType(), e);
            return CompileResult.failure(List.of(CompileResult.Diagnostic.error(
                    CompileStage.STRUCTURAL_VALIDATION, "-",
                    "编译器内部错误: " + e.getMessage(), "请联系平台管理员")));
        }
    }

    public java.util.Set<JobType> supportedTypes() {
        return java.util.Set.copyOf(byType.keySet());
    }
}
