package com.datagov.control.dto;

import com.datagov.control.compile.CompileResult;
import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Control Space 的对外 DTO。
 */
public final class JobDefinitionDtos {

    private JobDefinitionDtos() {
    }

    /**
     * 新建 / 编辑任务定义。
     *
     * <p>{@code config} 是一个自由形状的 Map,由 {@link #jobType} 决定该有哪些键。
     * 用强类型的 sealed interface 会更漂亮,但代价是每加一种任务类型都要改这个
     * DTO、改控制器、改前端类型 —— 而任务类型正是这个系统里预期会持续增加的东西。
     * 校验由对应的 JobCompiler 负责,报错带字段定位,并不比类型系统弱。
     */
    public record UpsertRequest(
            @NotBlank(message = "任务名称不能为空")
            @Size(max = 128, message = "任务名称不得超过 128 字符")
            String name,

            @NotNull(message = "任务类型不能为空")
            JobType jobType,

            @Size(max = 512, message = "描述不得超过 512 字符")
            String description,

            Map<String, Object> config,

            /** Cron 表达式;为空表示不绑定调度 */
            String cronExpression,
            String cronTimezone,
            /** SKIP / QUEUE / CONCURRENT */
            String misfirePolicy,

            Long timeoutMs,
            Integer retryMaxAttempts,
            Integer retryBackoffSeconds
    ) {

        public UpsertRequest {
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    /** 任务定义视图。不含 physicalPlanJson —— 计划里可能有连接串,单独接口取。 */
    public record JobDefinitionView(
            String id,
            String name,
            JobType jobType,
            String jobTypeDisplayName,
            JobDefinitionStatus status,
            String statusDisplayName,
            String description,
            Map<String, Object> config,
            Integer version,

            Instant lastCompiledAt,
            Boolean lastCompileSucceeded,
            String lastCompileMessage,

            String cronExpression,
            String cronTimezone,
            String misfirePolicy,
            Instant nextFireAt,
            Instant lastFireAt,

            Long timeoutMs,
            Integer retryMaxAttempts,
            Integer retryBackoffSeconds,

            /** 物理计划是否与当前定义版本一致。false 表示改过定义还没重新编译 */
            boolean planUpToDate,

            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy
    ) {

        public static JobDefinitionView from(JobDefinition d, Map<String, Object> config) {
            return new JobDefinitionView(
                    d.getId(), d.getName(), d.getJobType(),
                    d.getJobType() == null ? null : d.getJobType().displayName(),
                    d.getStatus(), d.getStatus() == null ? null : d.getStatus().displayName(),
                    d.getDescription(), config, d.getVersion(),
                    d.getLastCompiledAt(), d.getLastCompileSucceeded(), d.getLastCompileMessage(),
                    d.getCronExpression(), d.getCronTimezone(), d.getMisfirePolicy(),
                    d.getNextFireAt(), d.getLastFireAt(),
                    d.getTimeoutMs(), d.getRetryMaxAttempts(), d.getRetryBackoffSeconds(),
                    d.getPlanDefVersion() != null && d.getPlanDefVersion().equals(d.getVersion()),
                    d.getCreatedAt(), d.getCreatedBy(), d.getUpdatedAt(), d.getUpdatedBy());
        }
    }

    /**
     * 编译结果的对外形状。
     *
     * <p>成功时也返回诊断:那些是 WARNING(有损的类型映射之类),用户该看见。
     * 只在失败时返回诊断,会让"能跑但会丢精度"这类问题永远无人知晓。
     */
    public record CompileResponse(
            boolean succeeded,
            String summary,
            List<CompileResult.Diagnostic> diagnostics
    ) {

        public static CompileResponse from(CompileResult result) {
            return new CompileResponse(result.succeeded(), result.summary(), result.diagnostics());
        }
    }

    /** 绑定调度的入参。 */
    public record ScheduleRequest(
            @NotBlank(message = "Cron 表达式不能为空") String cronExpression,
            String timezone,
            String misfirePolicy
    ) {
    }

    /** 调度预览:接下来几次会在什么时候跑。绑定前让用户确认自己写对了。 */
    public record SchedulePreview(
            String cronExpression,
            String timezone,
            String misfirePolicy,
            List<Instant> upcomingFireTimes
    ) {
    }
}
