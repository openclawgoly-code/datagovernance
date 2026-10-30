package com.datagov.app.insight;

import com.datagov.app.insight.InsightSupersonicMappingService.MappingRequest;
import com.datagov.app.insight.InsightSupersonicMappingService.MappingView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/insight/mappings")
@Tag(name = "问数账号映射", description = "平台用户到 SuperSonic 账号的映射")
public class InsightSupersonicMappingController {

    private final InsightSupersonicMappingService service;

    public InsightSupersonicMappingController(InsightSupersonicMappingService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("insight:mapping:read")
    @Operation(summary = "映射列表")
    public ApiResponse<List<MappingView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @RequirePermission("insight:mapping:manage")
    @Operation(summary = "新增/更新映射")
    public ApiResponse<MappingView> save(@RequestBody MappingRequest request) {
        return ApiResponse.ok(service.save(request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("insight:mapping:manage")
    @Operation(summary = "删除映射")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
