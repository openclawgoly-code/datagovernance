package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.GlossaryService;
import com.datagov.metadata.service.GlossaryService.AssetTermView;
import com.datagov.metadata.service.GlossaryService.BindingRequest;
import com.datagov.metadata.service.GlossaryService.BindingView;
import com.datagov.metadata.service.GlossaryService.TermRequest;
import com.datagov.metadata.service.GlossaryService.TermView;
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

/** 业务术语（P2）：术语维护、发布、资产/字段绑定。 */
@RestController
@RequestMapping("/api/v1/metadata")
@Tag(name = "业务术语", description = "workspace 级术语表与资产绑定")
public class GlossaryController {

    private final GlossaryService glossaryService;

    public GlossaryController(GlossaryService glossaryService) {
        this.glossaryService = glossaryService;
    }

    @GetMapping("/glossary/terms")
    @RequirePermission("metadata:glossary:read")
    @Operation(summary = "术语分页", description = "支持关键词、业务域、状态筛选")
    public ApiResponse<PageResult<TermView>> listTerms(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domainId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(glossaryService.listTerms(page, size, keyword, domainId, status));
    }

    @PostMapping("/glossary/terms")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "新建术语", description = "创建后为 DRAFT；term 大小写不敏感唯一")
    public ApiResponse<TermView> createTerm(@RequestBody TermRequest request) {
        return ApiResponse.ok(glossaryService.createTerm(request));
    }

    @PutMapping("/glossary/terms/{id}")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "编辑术语", description = "编辑不改变发布状态")
    public ApiResponse<TermView> updateTerm(@PathVariable String id,
                                            @RequestBody TermRequest request) {
        return ApiResponse.ok(glossaryService.updateTerm(id, request));
    }

    @PostMapping("/glossary/terms/{id}/publish")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "发布术语")
    public ApiResponse<TermView> publish(@PathVariable String id) {
        return ApiResponse.ok(glossaryService.publish(id));
    }

    @PostMapping("/glossary/terms/{id}/deprecate")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "废弃已发布术语", description = "资产详情不再展示")
    public ApiResponse<TermView> deprecate(@PathVariable String id) {
        return ApiResponse.ok(glossaryService.deprecate(id));
    }

    @GetMapping("/glossary/terms/{id}/bindings")
    @RequirePermission("metadata:glossary:read")
    @Operation(summary = "术语的绑定列表")
    public ApiResponse<List<BindingView>> listBindings(@PathVariable String id) {
        return ApiResponse.ok(glossaryService.listBindings(id));
    }

    @PostMapping("/glossary/terms/{id}/bindings")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "绑定术语到数据集/字段")
    public ApiResponse<BindingView> createBinding(@PathVariable String id,
                                                  @RequestBody BindingRequest request) {
        return ApiResponse.ok(glossaryService.createBinding(id, request));
    }

    @DeleteMapping("/glossary/terms/{id}/bindings/{bindingId}")
    @RequirePermission("metadata:glossary:manage")
    @Operation(summary = "解绑")
    public ApiResponse<Void> deleteBinding(@PathVariable String id,
                                           @PathVariable String bindingId) {
        glossaryService.deleteBinding(id, bindingId);
        return ApiResponse.ok();
    }

    @GetMapping("/catalog/datasets/{id}/glossary")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "资产已发布的业务术语", description = "只返回 PUBLISHED")
    public ApiResponse<List<AssetTermView>> assetGlossary(@PathVariable String id) {
        return ApiResponse.ok(glossaryService.assetGlossary(id));
    }
}
