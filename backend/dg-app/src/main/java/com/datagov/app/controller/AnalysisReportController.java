package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.governance.service.AnalysisReportService;
import com.datagov.governance.service.AnalysisReportService.PublishRequest;
import com.datagov.governance.service.AnalysisReportService.PublishVersionRequest;
import com.datagov.governance.service.AnalysisReportService.ReportView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 分析报告(P6)。
 *
 * <p>五个端点对应契约里的三个命令与两个查询。没有 PUT / PATCH / DELETE:
 * 报告不可变,修订走 {@code /versions},撤回走 {@code /retract}。
 *
 * <p>控制器只做路由与权限声明;证据结构、截断规则、版本并发都在 Service 与
 * {@code AnalysisReportPolicy} 里 —— 那是可以单测的部分。
 */
@RestController
@RequestMapping("/api/v1/governance/analysis-reports")
@Tag(name = "分析报告", description = "外部分析 Agent 的结论归口:不可变、带证据、可版本化、可撤回")
public class AnalysisReportController {

    private final AnalysisReportService service;

    public AnalysisReportController(AnalysisReportService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission("governance:analysis:write")
    @Operation(summary = "发布分析报告(首版)",
            description = "correlationId 是 workspace 内幂等键:重放返回同一份报告,不新建")
    public ApiResponse<ReportView> publish(@RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publish(request));
    }

    @PostMapping("/{id}/versions")
    @RequirePermission("governance:analysis:write")
    @Operation(summary = "发布新版本",
            description = "旧版本自动成为 SUPERSEDED;已撤回的报告不允许再发版本")
    public ApiResponse<ReportView> publishVersion(@PathVariable String id,
                                                  @RequestBody PublishVersionRequest request) {
        return ApiResponse.ok(service.publishVersion(id, request));
    }

    @GetMapping
    @RequirePermission("governance:analysis:read")
    @Operation(summary = "分析报告列表",
            description = "默认排除已撤回;includeRetracted=true 才显示。列表只带当前版本的摘要,不带正文")
    public ApiResponse<PageResult<ReportView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Boolean includeRetracted) {
        return ApiResponse.ok(service.list(page, size, subjectType, subjectId,
                from, to, includeRetracted));
    }

    @GetMapping("/{id}")
    @RequirePermission("governance:analysis:read")
    @Operation(summary = "分析报告详情", description = "含全部版本;版本状态由 latestVersion 派生")
    public ApiResponse<ReportView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/retract")
    @RequirePermission("governance:analysis:write")
    @Operation(summary = "撤回分析报告",
            description = "幂等:已撤回再撤返回当前视图。撤回后仍可查,只是默认列表不再显示")
    public ApiResponse<ReportView> retract(@PathVariable String id) {
        return ApiResponse.ok(service.retract(id));
    }
}
