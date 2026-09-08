package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.control.service.StreamingJobService;
import com.datagov.control.service.StreamingJobService.StreamingStateView;
import com.datagov.runtime.domain.StreamingLifecycle;
import com.datagov.runtime.domain.StreamingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 实时任务的启停(序号 18)。
 *
 * <p>没有并进 {@code JobDefinitionController}:那里的动词是"执行一次",
 * 这里的动词是"让它一直活着"。同一个 URL 前缀下放两套语义相反的动词,
 * 会让调用方以为 {@code /run} 和 {@code /start} 只是叫法不同。
 */
@RestController
@RequestMapping("/api/v1/streaming-jobs")
@Tag(name = "实时任务", description = "常驻作业的启动、停止与保活状态")
public class StreamingJobController {

    private final StreamingJobService streamingService;

    public StreamingJobController(StreamingJobService streamingService) {
        this.streamingService = streamingService;
    }

    /** 运行态元数据。前端的状态标签与"能不能点启动"都读它,不硬编码。 */
    @GetMapping("/states")
    @Operation(summary = "流任务运行态的取值")
    public ApiResponse<StreamingMeta> meta() {
        return ApiResponse.ok(new StreamingMeta(
                Arrays.stream(StreamingStatus.values())
                        .map(s -> new StatusInfo(s.name(), s.displayName(), s.isActive(),
                                s.canStart(), s.canStop()))
                        .toList(),
                StreamingLifecycle.MAX_RESTART_ATTEMPTS));
    }

    public record StreamingMeta(List<StatusInfo> statuses, int maxRestartAttempts) {
    }

    public record StatusInfo(String status, String displayName, boolean active,
                             boolean canStart, boolean canStop) {
    }

    @GetMapping
    @RequirePermission("control:job:read")
    @Operation(summary = "本空间所有实时任务的运行态")
    public ApiResponse<List<StreamingStateView>> list() {
        return ApiResponse.ok(streamingService.listStates());
    }

    @GetMapping("/{id}")
    @RequirePermission("control:job:read")
    @Operation(summary = "单个实时任务的运行态")
    public ApiResponse<StreamingStateView> get(@PathVariable String id) {
        return ApiResponse.ok(streamingService.state(id));
    }

    @PostMapping("/{id}/start")
    @RequirePermission("control:job:stream")
    @Operation(summary = "启动实时任务",
            description = "已在运行时返回 409 而不是静默成功 —— 重复启动多半意味着有人以为它没起来")
    public ApiResponse<StreamingStateView> start(@PathVariable String id) {
        return ApiResponse.ok(streamingService.start(id));
    }

    @PostMapping("/{id}/stop")
    @RequirePermission("control:job:stream")
    @Operation(summary = "停止实时任务",
            description = "两阶段:先进「停止中」触发 savepoint,收尾完成后才是「已停止」")
    public ApiResponse<StreamingStateView> stop(@PathVariable String id) {
        return ApiResponse.ok(streamingService.stop(id));
    }
}
