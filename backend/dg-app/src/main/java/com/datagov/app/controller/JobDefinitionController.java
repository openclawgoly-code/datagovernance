package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.control.compile.CompileResult;
import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import com.datagov.control.dto.JobDefinitionDtos.CompileResponse;
import com.datagov.control.dto.JobDefinitionDtos.JobDefinitionView;
import com.datagov.control.dto.JobDefinitionDtos.ScheduleRequest;
import com.datagov.control.dto.JobDefinitionDtos.SchedulePreview;
import com.datagov.control.dto.JobDefinitionDtos.UpsertRequest;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.control.service.JobExecutionService;
import com.datagov.runtime.dto.ExecutionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 任务定义 —— 序号 9、11-14、16、18、20、22。
 *
 * <p>一套接口覆盖全部任务类型。菜单上它们是七个不同的入口,但那是<b>投影</b>:
 * UI 按 jobType 过滤同一个列表接口即可(SPACE-MODEL.md I.2「菜单与模块是多对多」)。
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "任务定义", description = "编译、发布、调度与手工执行")
public class JobDefinitionController {

    private final JobDefinitionService jobService;
    private final JobExecutionService executionService;

    public JobDefinitionController(JobDefinitionService jobService,
                                   JobExecutionService executionService) {
        this.jobService = jobService;
        this.executionService = executionService;
    }

    /** 任务类型元数据 —— 前端的类型下拉与"能不能配调度"的判断都读它,不硬编码。 */
    @GetMapping("/types")
    @Operation(summary = "支持的任务类型")
    public ApiResponse<List<JobTypeInfo>> types() {
        return ApiResponse.ok(Arrays.stream(JobType.values())
                .map(t -> new JobTypeInfo(t.name(), t.displayName(),
                        t.runtimeType().name(), t.isSchedulable(), t.isLongRunning()))
                .toList());
    }

    public record JobTypeInfo(String type, String displayName, String runtimeType,
                              boolean schedulable, boolean longRunning) {
    }

    // ── CRUD ────────────────────────────────────────────────────────────

    @GetMapping
    @RequirePermission("control:job:read")
    @Operation(summary = "分页查询任务定义")
    public ApiResponse<PageResult<JobDefinitionView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) JobType jobType,
            @RequestParam(required = false) JobDefinitionStatus status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(jobService.list(page, size, jobType, status, keyword));
    }

    @GetMapping("/{id}")
    @RequirePermission("control:job:read")
    @Operation(summary = "查询单个任务定义")
    public ApiResponse<JobDefinitionView> get(@PathVariable String id) {
        return ApiResponse.ok(jobService.get(id));
    }

    @PostMapping
    @RequirePermission("control:job:create")
    @Operation(summary = "新建任务定义", description = "新建后为草稿,需编译并发布才能执行")
    public ApiResponse<JobDefinitionView> create(@Valid @RequestBody UpsertRequest request) {
        return ApiResponse.ok(jobService.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission("control:job:update")
    @Operation(summary = "编辑任务定义",
            description = "任何修改都会把状态打回草稿并作废物理计划,需重新编译发布")
    public ApiResponse<JobDefinitionView> update(@PathVariable String id,
                                                 @Valid @RequestBody UpsertRequest request) {
        return ApiResponse.ok(jobService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("control:job:delete")
    @Operation(summary = "删除任务定义", description = "调度中的任务需先暂停或下线")
    public ApiResponse<Void> delete(@PathVariable String id) {
        jobService.delete(id);
        return ApiResponse.ok();
    }

    // ── 编译与发布 ──────────────────────────────────────────────────────

    /**
     * 编译。
     *
     * <p><b>失败也返回 200</b>,响应体里带 {@code succeeded=false} 与完整诊断。
     * 编译失败是这个接口的正常结果之一,而 UI 需要拿到逐条诊断才能把错误标在
     * 对应的字段上 —— 用 4xx 表达"你的定义有十七个字段类型不兼容",客户端
     * 只会得到一个笼统的错误提示。
     */
    @PostMapping("/{id}/compile")
    @RequirePermission("control:job:compile")
    @Operation(summary = "编译任务定义",
            description = "编译失败也返回 200,通过 succeeded 字段与 diagnostics 列表表达")
    public ApiResponse<CompileResponse> compile(@PathVariable String id) {
        return ApiResponse.ok(jobService.compile(id));
    }

    @GetMapping("/{id}/diagnostics")
    @RequirePermission("control:job:read")
    @Operation(summary = "最近一次编译的完整诊断")
    public ApiResponse<List<CompileResult.Diagnostic>> diagnostics(@PathVariable String id) {
        return ApiResponse.ok(jobService.diagnostics(id));
    }

    @PostMapping("/{id}/publish")
    @RequirePermission("control:job:publish")
    @Operation(summary = "发布任务定义",
            description = "发布前强制重新编译 —— 两次之间源表可能已经改了字段")
    public ApiResponse<JobDefinitionView> publish(@PathVariable String id) {
        return ApiResponse.ok(jobService.publish(id));
    }

    @PostMapping("/{id}/offline")
    @RequirePermission("control:job:publish")
    @Operation(summary = "下线任务")
    public ApiResponse<JobDefinitionView> offline(@PathVariable String id) {
        return ApiResponse.ok(jobService.offline(id));
    }

    @PostMapping("/{id}/archive")
    @RequirePermission("control:job:delete")
    @Operation(summary = "归档任务")
    public ApiResponse<JobDefinitionView> archive(@PathVariable String id) {
        return ApiResponse.ok(jobService.archive(id));
    }

    // ── 调度(功能 16)──────────────────────────────────────────────────

    @PostMapping("/schedule-preview")
    @RequirePermission("control:job:read")
    @Operation(summary = "预览 Cron 的接下来几次触发时间",
            description = "绑定前让用户确认自己写的表达式是什么意思。5 段的 crontab 风格会自动补秒位")
    public ApiResponse<SchedulePreview> previewSchedule(@Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(jobService.previewSchedule(
                request.cronExpression(), request.timezone(), request.misfirePolicy()));
    }

    @PostMapping("/{id}/schedule")
    @RequirePermission("control:job:schedule")
    @Operation(summary = "绑定周期调度")
    public ApiResponse<JobDefinitionView> bindSchedule(@PathVariable String id,
                                                       @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(jobService.bindSchedule(id,
                request.cronExpression(), request.timezone(), request.misfirePolicy()));
    }

    @DeleteMapping("/{id}/schedule")
    @RequirePermission("control:job:schedule")
    @Operation(summary = "解绑调度", description = "任务回到已发布,仍可手工触发")
    public ApiResponse<JobDefinitionView> unbindSchedule(@PathVariable String id) {
        return ApiResponse.ok(jobService.unbindSchedule(id));
    }

    @PostMapping("/{id}/schedule/pause")
    @RequirePermission("control:job:schedule")
    @Operation(summary = "暂停调度", description = "暂停的只是自动触发,手工触发仍可用")
    public ApiResponse<JobDefinitionView> pauseSchedule(@PathVariable String id) {
        return ApiResponse.ok(jobService.pauseSchedule(id));
    }

    @PostMapping("/{id}/schedule/resume")
    @RequirePermission("control:job:schedule")
    @Operation(summary = "恢复调度",
            description = "从现在往后算下次触发,不补跑暂停期间错过的")
    public ApiResponse<JobDefinitionView> resumeSchedule(@PathVariable String id) {
        return ApiResponse.ok(jobService.resumeSchedule(id));
    }

    // ── 执行 ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/run")
    @RequirePermission("control:job:trigger")
    @Operation(summary = "立即执行一次",
            description = "定义改过但没重新编译时会被拒绝 —— 否则跑的会是一份过期的计划")
    public ApiResponse<ExecutionView> run(@PathVariable String id) {
        return ApiResponse.ok(executionService.triggerNow(id));
    }
}
