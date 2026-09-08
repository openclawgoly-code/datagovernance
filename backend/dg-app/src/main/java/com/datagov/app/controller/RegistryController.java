package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.RegistryService;
import com.datagov.metadata.service.RegistryService.ArtifactView;
import com.datagov.metadata.service.RegistryService.EdgeRequest;
import com.datagov.metadata.service.RegistryService.EdgeView;
import com.datagov.metadata.service.RegistryService.PublishVersionRequest;
import com.datagov.metadata.service.RegistryService.RegisterRequest;
import com.datagov.metadata.service.RegistryService.VersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据集 / 模型注册中心与语义映射(序号 34 的契约第 3、4 条)。
 *
 * <p><b>这套接口是给 Intelligence 平台调的。</b> 序号 34 已确认独立立项
 * (架构风险 R1);当那个平台建起来时,它在这里注册产物、在这里写语义映射,
 * 而不是自己再建一个注册中心 —— 「不建第二注册中心」是契约的原话,
 * 它的实际后果是平台的血缘与影响分析能看见数据集与模型。
 */
@RestController
@RequestMapping("/api/v1/registry")
@Tag(name = "注册中心", description = "数据集 / 模型的标识与版本、字段到医学概念的语义映射")
public class RegistryController {

    private final RegistryService registryService;

    public RegistryController(RegistryService registryService) {
        this.registryService = registryService;
    }

    // ── 注册项 ──────────────────────────────────────────────────────────

    @GetMapping("/artifacts")
    @RequirePermission("metadata:registry:read")
    @Operation(summary = "分页查询注册项", description = "数据集 / 模型 / 本体")
    public ApiResponse<PageResult<ArtifactView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(registryService.list(page, size, kind, keyword));
    }

    @GetMapping("/artifacts/{id}")
    @RequirePermission("metadata:registry:read")
    @Operation(summary = "查询单个注册项")
    public ApiResponse<ArtifactView> get(@PathVariable String id) {
        return ApiResponse.ok(registryService.get(id));
    }

    @PostMapping("/artifacts")
    @RequirePermission("metadata:registry:manage")
    @Operation(summary = "注册一个数据集 / 模型 / 本体",
            description = "只登记标识,内容在发布版本时以 URI 给出")
    public ApiResponse<ArtifactView> register(@RequestBody RegisterRequest request) {
        return ApiResponse.ok(registryService.register(request));
    }

    @DeleteMapping("/artifacts/{id}")
    @RequirePermission("metadata:registry:manage")
    @Operation(summary = "删除注册项",
            description = "版本记录保留 —— 「这个模型用了哪版数据」在数据集删除后仍要能回答")
    public ApiResponse<Void> delete(@PathVariable String id) {
        registryService.delete(id);
        return ApiResponse.ok();
    }

    // ── 版本 ────────────────────────────────────────────────────────────

    @GetMapping("/artifacts/{id}/versions")
    @RequirePermission("metadata:registry:read")
    @Operation(summary = "版本列表")
    public ApiResponse<List<VersionView>> versions(@PathVariable String id) {
        return ApiResponse.ok(registryService.versions(id));
    }

    @PostMapping("/artifacts/{id}/versions")
    @RequirePermission("metadata:registry:manage")
    @Operation(summary = "发布新版本",
            description = "版本号由平台分配;已发布的版本不可修改 —— 要改内容就再发一版")
    public ApiResponse<VersionView> publishVersion(@PathVariable String id,
                                                   @RequestBody PublishVersionRequest request) {
        return ApiResponse.ok(registryService.publishVersion(id, request));
    }

    // ── 语义映射(契约第 4 条)──────────────────────────────────────────

    @GetMapping("/edges")
    @RequirePermission("metadata:registry:read")
    @Operation(summary = "查询关系边",
            description = "本期的用途是 Column ──MapsTo──> Concept:让医学语义被血缘分析看见")
    public ApiResponse<List<EdgeView>> edges(
            @RequestParam(required = false) String fromType,
            @RequestParam(required = false) String fromId,
            @RequestParam(required = false) String relation) {
        return ApiResponse.ok(registryService.edges(fromType, fromId, relation));
    }

    @PostMapping("/edges")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "新增关系边",
            description = "Concept 的定义归 Intelligence,平台只记这条边的两端标识")
    public ApiResponse<EdgeView> addEdge(@RequestBody EdgeRequest request) {
        return ApiResponse.ok(registryService.addEdge(request));
    }

    @DeleteMapping("/edges/{id}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除关系边")
    public ApiResponse<Void> removeEdge(@PathVariable String id) {
        registryService.removeEdge(id);
        return ApiResponse.ok();
    }
}
