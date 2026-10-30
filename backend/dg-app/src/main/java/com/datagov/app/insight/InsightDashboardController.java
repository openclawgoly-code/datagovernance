package com.datagov.app.insight;

import com.datagov.app.insight.InsightDashboardService.AclCandidateView;
import com.datagov.app.insight.InsightDashboardService.AclRequest;
import com.datagov.app.insight.InsightDashboardService.AclView;
import com.datagov.app.insight.InsightDashboardService.CreateRequest;
import com.datagov.app.insight.InsightDashboardService.DashboardDetail;
import com.datagov.app.insight.InsightDashboardService.DashboardView;
import com.datagov.app.insight.InsightDashboardService.ItemRequest;
import com.datagov.app.insight.InsightDashboardService.ItemUpdateRequest;
import com.datagov.app.insight.InsightDashboardService.ItemView;
import com.datagov.app.insight.InsightDashboardService.RunResult;
import com.datagov.app.insight.InsightDashboardService.VersionView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/{id}/run")
    @RequirePermission("insight:dashboard:read")
    @Operation(summary = "重新执行看板全部问题")
    public ApiResponse<RunResult> run(@PathVariable String id) {
        return ApiResponse.ok(service.run(id));
    }

    @PostMapping("/{id}/items")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "添加问题项")
    public ApiResponse<ItemView> addItem(@PathVariable String id,
                                         @RequestBody ItemRequest request) {
        return ApiResponse.ok(service.addItem(id, request));
    }

    @PatchMapping("/{id}/items/{itemId}")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "更新问题项排序/布局")
    public ApiResponse<ItemView> updateItem(@PathVariable String id,
                                            @PathVariable String itemId,
                                            @RequestBody ItemUpdateRequest request) {
        return ApiResponse.ok(service.updateItem(id, itemId, request));
    }

    @GetMapping("/{id}/versions")
    @RequirePermission("insight:dashboard:read")
    @Operation(summary = "看板版本历史")
    public ApiResponse<List<VersionView>> versions(@PathVariable String id) {
        return ApiResponse.ok(service.versions(id));
    }

    @GetMapping("/{id}/acl")
    @RequirePermission("insight:dashboard:read")
    @Operation(summary = "看板 ACL")
    public ApiResponse<List<AclView>> acl(@PathVariable String id) {
        return ApiResponse.ok(service.acl(id));
    }

    @GetMapping("/{id}/acl/candidates")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "看板 ACL 候选用户",
            description = "只返回当前空间 ACTIVE 成员，排除已授权用户，最多 20 条")
    public ApiResponse<List<AclCandidateView>> aclCandidates(
            @PathVariable String id,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.aclCandidates(id, keyword));
    }

    @PostMapping("/{id}/acl")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "授予看板 READ/MANAGE")
    public ApiResponse<AclView> addAcl(@PathVariable String id,
                                       @RequestBody AclRequest request) {
        return ApiResponse.ok(service.addAcl(id, request));
    }

    @DeleteMapping("/{id}/acl/{username}")
    @RequirePermission("insight:dashboard:manage")
    @Operation(summary = "移除看板 ACL")
    public ApiResponse<Void> removeAcl(@PathVariable String id,
                                       @PathVariable String username) {
        service.removeAcl(id, username);
        return ApiResponse.ok();
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
