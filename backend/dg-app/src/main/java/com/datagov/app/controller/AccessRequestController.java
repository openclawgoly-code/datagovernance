package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.CreateRequest;
import com.datagov.governance.service.AccessRequestService.Decision;
import com.datagov.governance.service.AccessRequestService.GrantView;
import com.datagov.governance.service.AccessRequestService.RequestView;
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
 * 敏感数据临时访问申请（P2）。
 *
 * <p>P2 的 grant 是 workspace 内“类别 + 最高级别”的临时明文权限，
 * 不绑定单个资产；它只在 Query Gateway 生效，导出复用 ask 链路。
 */
@RestController
@RequestMapping("/api/v1/metadata")
@Tag(name = "敏感访问申请", description = "临时明文访问申请、审批与 grant")
public class AccessRequestController {

    private final AccessRequestService accessService;

    public AccessRequestController(AccessRequestService accessService) {
        this.accessService = accessService;
    }

    @PostMapping("/access-requests")
    @RequirePermission("metadata:access:request")
    @Operation(summary = "提交访问申请", description = "原因 10–500 字，时长 1–168 小时")
    public ApiResponse<RequestView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(accessService.create(request));
    }

    @GetMapping("/access-requests")
    @RequirePermission("metadata:access:request")
    @Operation(summary = "访问申请列表", description = "scope=mine 本人；scope=pending 需审批权限")
    public ApiResponse<List<RequestView>> list(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResponse.ok(accessService.list(scope, page, size));
    }

    @PostMapping("/access-requests/{id}/approve")
    @RequirePermission("metadata:access:approve")
    @Operation(summary = "批准申请", description = "禁止自审批；批准后创建带到期时间的 grant")
    public ApiResponse<RequestView> approve(@PathVariable String id,
                                            @RequestBody(required = false) Decision decision) {
        return ApiResponse.ok(accessService.approve(id, decision));
    }

    @PostMapping("/access-requests/{id}/reject")
    @RequirePermission("metadata:access:approve")
    @Operation(summary = "拒绝申请")
    public ApiResponse<RequestView> reject(@PathVariable String id,
                                           @RequestBody(required = false) Decision decision) {
        return ApiResponse.ok(accessService.reject(id, decision));
    }

    @PostMapping("/access-requests/{id}/cancel")
    @RequirePermission("metadata:access:request")
    @Operation(summary = "取消自己的待审批申请")
    public ApiResponse<RequestView> cancel(@PathVariable String id) {
        return ApiResponse.ok(accessService.cancel(id));
    }

    @GetMapping("/access-grants")
    @RequirePermission("metadata:access:request")
    @Operation(summary = "访问授权列表", description = "审批人可见全部；普通用户只看本人")
    public ApiResponse<List<GrantView>> grants() {
        return ApiResponse.ok(accessService.listGrants());
    }

    @PostMapping("/access-grants/{id}/revoke")
    @RequirePermission("metadata:access:approve")
    @Operation(summary = "撤销授权", description = "撤销后后续查询立即失效")
    public ApiResponse<GrantView> revoke(@PathVariable String id) {
        return ApiResponse.ok(accessService.revoke(id));
    }
}
