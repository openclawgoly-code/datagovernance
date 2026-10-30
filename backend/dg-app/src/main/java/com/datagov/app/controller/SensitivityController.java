package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.BatchReviewRequest;
import com.datagov.metadata.service.SensitivityService.BatchReviewResult;
import com.datagov.metadata.service.SensitivityService.ReviewRequest;
import com.datagov.metadata.service.SensitivityService.RuleView;
import com.datagov.metadata.service.SensitivityService.ScanRequest;
import com.datagov.metadata.service.SensitivityService.ScanResult;
import com.datagov.metadata.service.SensitivityService.SensitivityView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 敏感标签(P10.4)。
 *
 * <p>首期扫描是“元数据规则”:按字段名/注释匹配。内容采样识别在下一阶段接入,
 * 这里不把“规则命中”说成“内容确认”。
 */
@RestController
@RequestMapping("/api/v1/metadata/sensitivity")
@Tag(name = "敏感数据", description = "敏感规则、字段标签、扫描与复核")
public class SensitivityController {

    private final SensitivityService sensitivityService;

    public SensitivityController(SensitivityService sensitivityService) {
        this.sensitivityService = sensitivityService;
    }

    @GetMapping("/rules")
    @RequirePermission("metadata:sensitivity:read")
    @Operation(summary = "敏感规则列表", description = "内置规则 + 当前空间自定义规则")
    public ApiResponse<List<RuleView>> rules() {
        return ApiResponse.ok(sensitivityService.rules());
    }

    @GetMapping("/columns")
    @RequirePermission("metadata:sensitivity:read")
    @Operation(summary = "字段敏感标签")
    public ApiResponse<PageResult<SensitivityView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String datasetId) {
        return ApiResponse.ok(sensitivityService.list(page, size, level, status, datasetId));
    }

    @PostMapping("/scan")
    @RequirePermission("metadata:sensitivity:scan")
    @Operation(summary = "发起敏感扫描",
            description = "按字段名/注释匹配规则;可指定 datasetId,也可全工作区扫描")
    public ApiResponse<ScanResult> scan(@RequestBody(required = false) ScanRequest request) {
        return ApiResponse.ok(sensitivityService.scan(request));
    }

    @PostMapping("/columns/batch-review")
    @RequirePermission("metadata:sensitivity:review")
    @Operation(summary = "批量复核敏感标签",
            description = "一次最多 200 条;部分标签不存在时返回失败列表,不中断其余更新")
    public ApiResponse<BatchReviewResult> batchReview(
            @RequestBody BatchReviewRequest request) {
        return ApiResponse.ok(sensitivityService.batchReview(request));
    }

    @PostMapping("/columns/{id}/review")
    @RequirePermission("metadata:sensitivity:review")
    @Operation(summary = "复核敏感标签", description = "CONFIRMED / REJECTED;人工结论不会被后续自动扫描覆盖")
    public ApiResponse<SensitivityView> review(@PathVariable String id,
                                               @RequestBody ReviewRequest request) {
        return ApiResponse.ok(sensitivityService.review(id, request));
    }
}
