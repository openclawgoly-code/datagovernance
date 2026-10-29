package com.datagov.app.insight;

import com.datagov.app.insight.InsightDashboardService.CreateRequest;
import com.datagov.app.insight.InsightDashboardService.DashboardDetail;
import com.datagov.app.insight.InsightDashboardService.DashboardView;
import com.datagov.app.insight.InsightDashboardService.ItemRequest;
import com.datagov.app.insight.InsightDashboardService.ItemView;
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
@RequestMapping("/api/v1/insight/dashboards")
@Tag(name = "问数看板", description = "保存问题定义,打开时重新问数")
public class InsightDashboardController {

    private final InsightDashboardService service;

    public InsightDashboardController(InsightDashboardService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("insight:dashboard:read")
    @Operation(summary = "看板列表")
    public ApiResponse<List<DashboardView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "新建看板")
    public ApiResponse<DashboardView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/{id}")
    @RequirePermission("insight:dashboard:read")
    @Operation(summary = "看板详情")
    public ApiResponse<DashboardDetail> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/items")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "添加问题项")
    public ApiResponse<ItemView> addItem(@PathVariable String id,
                                         @RequestBody ItemRequest request) {
        return ApiResponse.ok(service.addItem(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "删除看板")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "删除问题项")
    public ApiResponse<Void> deleteItem(@PathVariable String id,
                                        @PathVariable String itemId) {
        service.deleteItem(id, itemId);
        return ApiResponse.ok();
    }
}
