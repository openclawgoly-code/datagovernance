package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.QualityService;
import com.datagov.metadata.service.QualityService.QualitySummary;
import com.datagov.metadata.service.QualityService.RuleRequest;
import com.datagov.metadata.service.QualityService.RuleView;
import com.datagov.metadata.service.QualityService.RunView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 资产质量（P2）：内置规则、手动运行、摘要与历史。 */
@RestController
@RequestMapping("/api/v1/metadata/quality")
@Tag(name = "资产质量", description = "内置质量规则、运行历史与加权分数")
public class QualityController {

    private final QualityService qualityService;

    public QualityController(QualityService qualityService) {
        this.qualityService = qualityService;
    }

    @GetMapping("/rules")
    @RequirePermission("metadata:quality:read")
    @Operation(summary = "质量规则分页")
    public ApiResponse<PageResult<RuleView>> listRules(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) String ruleType) {
        return ApiResponse.ok(qualityService.listRules(page, size, datasetId, ruleType));
    }

    @PostMapping("/rules")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "新建质量规则")
    public ApiResponse<RuleView> createRule(@RequestBody RuleRequest request) {
        return ApiResponse.ok(qualityService.createRule(request));
    }

    @PutMapping("/rules/{id}")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "编辑/启停质量规则")
    public ApiResponse<RuleView> updateRule(@PathVariable String id,
                                            @RequestBody RuleRequest request) {
        return ApiResponse.ok(qualityService.updateRule(id, request));
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "删除质量规则", description = "运行历史保留")
    public ApiResponse<Void> deleteRule(@PathVariable String id) {
        qualityService.deleteRule(id);
        return ApiResponse.ok();
    }

    @PostMapping("/rules/{id}/run")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "运行单条规则", description = "失败写入运行记录，不抛 5xx")
    public ApiResponse<RunView> runRule(@PathVariable String id) {
        return ApiResponse.ok(qualityService.runRule(id));
    }

    @PostMapping("/datasets/{id}/run")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "运行数据集全部启用规则", description = "单条失败不阻断其他规则")
    public ApiResponse<List<RunView>> runDataset(@PathVariable String id) {
        return ApiResponse.ok(qualityService.runDataset(id));
    }

    @GetMapping("/datasets/{id}/summary")
    @RequirePermission("metadata:quality:read")
    @Operation(summary = "资产质量摘要", description = "分数只覆盖已配置且已运行的规则")
    public ApiResponse<QualitySummary> summary(@PathVariable String id) {
        return ApiResponse.ok(qualityService.summary(id));
    }

    @GetMapping("/runs")
    @RequirePermission("metadata:quality:read")
    @Operation(summary = "质量运行历史")
    public ApiResponse<PageResult<RunView>> listRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) Boolean passed,
            @RequestParam(required = false) Boolean latest) {
        return ApiResponse.ok(qualityService.listRuns(page, size, status, ruleType, datasetId,
                passed, latest));
    }
}
