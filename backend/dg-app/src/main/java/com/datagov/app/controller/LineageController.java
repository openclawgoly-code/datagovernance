package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.EdgeView;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字段级血缘(P10.4)。
 *
 * <p>写入来自 ETL/CDC/OpenLineage 作业;查询用于“这个字段从哪来、改它会影响谁”。
 * 边不删除,SQL 变化时旧边由服务端标 SUPERSEDED。
 */
@RestController
@RequestMapping("/api/v1/metadata/lineage")
@Tag(name = "字段血缘", description = "列级血缘、上游/下游与影响分析")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @PostMapping("/jobs")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "上报一次作业的列级血缘",
            description = "同一作业重复上报会复用/更新边,旧边标 SUPERSEDED")
    public ApiResponse<JobResult> recordJob(@RequestBody JobRequest request) {
        return ApiResponse.ok(lineageService.recordJob(request));
    }

    @PostMapping("/edges")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "人工补录一条列级血缘")
    public ApiResponse<EdgeView> recordEdge(@RequestBody EdgeInput input) {
        return ApiResponse.ok(lineageService.recordManualEdge(input));
    }

    @GetMapping("/nodes/{id}")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "血缘节点")
    public ApiResponse<NodeView> getNode(@PathVariable String id) {
        return ApiResponse.ok(lineageService.getNode(id));
    }

    @GetMapping("/columns/{id}/upstream")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "字段上游", description = "depth 上限 5")
    public ApiResponse<GraphView> upstream(@PathVariable String id,
                                           @RequestParam(defaultValue = "3") int depth) {
        return ApiResponse.ok(lineageService.upstream(id, depth));
    }

    @GetMapping("/columns/{id}/downstream")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "字段下游")
    public ApiResponse<GraphView> downstream(@PathVariable String id,
                                             @RequestParam(defaultValue = "3") int depth) {
        return ApiResponse.ok(lineageService.downstream(id, depth));
    }

    @GetMapping("/columns/{id}/impact")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "影响分析", description = "等价于下游遍历,单独命名让调用方读得懂意图")
    public ApiResponse<GraphView> impact(@PathVariable String id,
                                         @RequestParam(defaultValue = "5") int depth) {
        return ApiResponse.ok(lineageService.impact(id, depth));
    }

    @GetMapping("/datasets/{id}/graph")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "表级血缘",
            description = "列级事实按数据集聚合; direction=upstream/downstream/both, depth 上限 5")
    public ApiResponse<GraphView> datasetGraph(
            @PathVariable String id,
            @RequestParam(defaultValue = "both") String direction,
            @RequestParam(defaultValue = "2") int depth) {
        return ApiResponse.ok(lineageService.datasetGraph(id, direction, depth));
    }
}
