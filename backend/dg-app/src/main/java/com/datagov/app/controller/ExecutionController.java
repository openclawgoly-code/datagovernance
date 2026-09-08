package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 执行记录 —— 序号 10、15、19、21、23 共用的<b>一套</b>接口。
 *
 * <p>五个菜单项在这里是同一个列表加不同的 {@code jobRefType} 过滤。这不是省事,
 * 而是架构约束 R4 在 API 层的体现:五张表意味着序号 24 的监控没有单一事实源。
 * 既然只有一张表,自然也只该有一套接口 —— 否则很快就会有人在其中一套上加了
 * 字段而忘了另外四套。
 */
@RestController
@RequestMapping("/api/v1/executions")
@Tag(name = "执行记录", description = "整库迁移/离线同步/实时/批处理/工作流共用")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /** 作业种类元数据。前端的过滤下拉读它,不硬编码五个菜单项。 */
    @GetMapping("/job-types")
    @Operation(summary = "作业种类")
    public ApiResponse<List<JobRefTypeInfo>> jobTypes() {
        return ApiResponse.ok(Arrays.stream(JobRefType.values())
                .map(t -> new JobRefTypeInfo(t.name(), t.displayName(), t.isLongRunning()))
                .toList());
    }

    public record JobRefTypeInfo(String type, String displayName, boolean longRunning) {
    }

    @GetMapping
    @RequirePermission("runtime:execution:read")
    @Operation(summary = "分页查询执行记录",
            description = "jobRefType 为空时返回全部,即序号 24 监控看到的全量")
    public ApiResponse<PageResult<ExecutionView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) JobRefType jobRefType,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) String jobRefId) {
        return ApiResponse.ok(executionService.list(page, size, jobRefType, status, jobRefId));
    }

    @GetMapping("/{id}")
    @RequirePermission("runtime:execution:read")
    @Operation(summary = "执行详情",
            description = "含全部尝试。重试不新建执行记录,只新增一次尝试")
    public ApiResponse<ExecutionView.Detail> get(@PathVariable String id) {
        return ApiResponse.ok(executionService.get(id));
    }

    @GetMapping("/{id}/children")
    @RequirePermission("runtime:execution:read")
    @Operation(summary = "子执行", description = "工作流节点的执行记录(序号 22/23)")
    public ApiResponse<List<ExecutionView>> children(@PathVariable String id) {
        return ApiResponse.ok(executionService.listChildren(id));
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission("runtime:execution:cancel")
    @Operation(summary = "取消执行",
            description = "取消是两段式的:先进「取消中」,执行器实际停下来后才是「已取消」")
    public ApiResponse<ExecutionView> cancel(@PathVariable String id) {
        return ApiResponse.ok(executionService.cancel(id));
    }
}
