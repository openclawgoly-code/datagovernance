package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.runtime.domain.ExecutorStatus;
import com.datagov.runtime.service.ExecutorRegistryService;
import com.datagov.runtime.service.ExecutorRegistryService.ExecutorView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 执行器管理(序号 31)。
 *
 * <p>菜单在「基础配置」下,归属却是 Runtime(架构风险 R2)—— 这里管的是执行
 * 资源池,不是配置项。所以没有"编辑"接口:执行器的并发额度、端点这些东西
 * 由部署决定,平台只负责注册、观察与排空。
 */
@RestController
@RequestMapping("/api/v1/executors")
@Tag(name = "执行器", description = "执行资源池的注册、心跳与排空")
public class ExecutorController {

    private final ExecutorRegistryService executorService;

    public ExecutorController(ExecutorRegistryService executorService) {
        this.executorService = executorService;
    }

    /** 状态元数据。前端的标签与按钮可用性都读它。 */
    @GetMapping("/statuses")
    @Operation(summary = "执行器状态的取值")
    public ApiResponse<List<StatusInfo>> statuses() {
        return ApiResponse.ok(Arrays.stream(ExecutorStatus.values())
                .map(s -> new StatusInfo(s.name(), s.displayName(), s.acceptsWork()))
                .toList());
    }

    public record StatusInfo(String status, String displayName, boolean acceptsWork) {
    }

    public record RegisterRequest(String name, String kind, String endpoint,
                                  Integer maxConcurrency, Boolean shared) {
    }

    public record HeartbeatRequest(Integer runningCount) {
    }

    @GetMapping
    @RequirePermission("runtime:executor:read")
    @Operation(summary = "可见的执行器",
            description = "平台共享的 + 本空间专属的。看不见其他空间的专属执行器")
    public ApiResponse<List<ExecutorView>> list() {
        return ApiResponse.ok(executorService.list());
    }

    @GetMapping("/{id}")
    @RequirePermission("runtime:executor:read")
    @Operation(summary = "查询单个执行器")
    public ApiResponse<ExecutorView> get(@PathVariable String id) {
        return ApiResponse.ok(executorService.get(id));
    }

    @PostMapping
    @RequirePermission("runtime:executor:manage")
    @Operation(summary = "注册执行器",
            description = "注册完是「已注册」而不是「健康」—— 还没收到过一次心跳")
    public ApiResponse<ExecutorView> register(@RequestBody RegisterRequest request) {
        return ApiResponse.ok(executorService.register(request.name(), request.kind(),
                request.endpoint(), request.maxConcurrency(),
                Boolean.TRUE.equals(request.shared())));
    }

    @PostMapping("/{id}/heartbeat")
    @RequirePermission("runtime:executor:manage")
    @Operation(summary = "上报心跳", description = "第一次心跳把「已注册」转成「健康」")
    public ApiResponse<ExecutorView> heartbeat(@PathVariable String id,
                                               @RequestBody(required = false) HeartbeatRequest request) {
        return ApiResponse.ok(executorService.heartbeat(id,
                request == null ? null : request.runningCount()));
    }

    @PostMapping("/{id}/drain")
    @RequirePermission("runtime:executor:manage")
    @Operation(summary = "排空",
            description = "不再派新活,等手上的跑完。这是下线执行器的唯一正确入口")
    public ApiResponse<ExecutorView> drain(@PathVariable String id) {
        return ApiResponse.ok(executorService.drain(id));
    }

    @PostMapping("/{id}/resume")
    @RequirePermission("runtime:executor:manage")
    @Operation(summary = "取消排空")
    public ApiResponse<ExecutorView> resume(@PathVariable String id) {
        return ApiResponse.ok(executorService.resume(id));
    }

    @PostMapping("/{id}/remove")
    @RequirePermission("runtime:executor:manage")
    @Operation(summary = "移除执行器",
            description = "需先排空;手上还有任务时拒绝 —— 现在移除会把这些执行记录一起丢掉")
    public ApiResponse<ExecutorView> remove(@PathVariable String id) {
        return ApiResponse.ok(executorService.remove(id));
    }
}
