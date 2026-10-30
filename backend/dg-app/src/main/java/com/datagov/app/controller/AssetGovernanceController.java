package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.metadata.service.AssetGovernanceService;
import com.datagov.metadata.service.AssetGovernanceService.DomainRequest;
import com.datagov.metadata.service.AssetGovernanceService.DomainView;
import com.datagov.metadata.service.AssetGovernanceService.GovernanceRequest;
import com.datagov.metadata.service.AssetGovernanceService.GovernanceView;
import com.datagov.metadata.service.AssetGovernanceService.MemberOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资产治理（P2）：业务域、owner/管家、认证状态。
 *
 * <p>读取复用 {@code metadata:asset:read}，写入需要 {@code metadata:asset:govern}。
 * 治理结论与 Catalog 采集事实分离，端点也分开，避免把人工结论混进采集接口。
 */
@RestController
@RequestMapping("/api/v1/metadata")
@Tag(name = "资产治理", description = "业务域、负责人、管家与认证状态")
public class AssetGovernanceController {

    private final AssetGovernanceService governanceService;

    public AssetGovernanceController(AssetGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping("/catalog/datasets/{id}/governance")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "资产治理详情", description = "未配置时返回 certificationStatus=NONE")
    public ApiResponse<GovernanceView> getGovernance(@PathVariable String id) {
        return ApiResponse.ok(governanceService.getGovernance(id));
    }

    @PutMapping("/catalog/datasets/{id}/governance")
    @RequirePermission("metadata:asset:govern")
    @Operation(summary = "更新资产治理", description = "owner/steward 必须是当前空间 ACTIVE 成员")
    public ApiResponse<GovernanceView> updateGovernance(@PathVariable String id,
                                                        @RequestBody GovernanceRequest request) {
        return ApiResponse.ok(governanceService.updateGovernance(id, request));
    }

    @GetMapping("/domains")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "业务域列表")
    public ApiResponse<List<DomainView>> listDomains() {
        return ApiResponse.ok(governanceService.listDomains());
    }

    @PostMapping("/domains")
    @RequirePermission("metadata:asset:govern")
    @Operation(summary = "新建业务域", description = "code 在空间内唯一且规范为小写")
    public ApiResponse<DomainView> createDomain(@RequestBody DomainRequest request) {
        return ApiResponse.ok(governanceService.createDomain(request));
    }

    @PutMapping("/domains/{id}")
    @RequirePermission("metadata:asset:govern")
    @Operation(summary = "编辑业务域")
    public ApiResponse<DomainView> updateDomain(@PathVariable String id,
                                                @RequestBody DomainRequest request) {
        return ApiResponse.ok(governanceService.updateDomain(id, request));
    }

    @DeleteMapping("/domains/{id}")
    @RequirePermission("metadata:asset:govern")
    @Operation(summary = "删除业务域", description = "仍被资产引用时拒绝删除")
    public ApiResponse<Void> deleteDomain(@PathVariable String id) {
        governanceService.deleteDomain(id);
        return ApiResponse.ok();
    }

    @GetMapping("/domains/members")
    @RequirePermission("metadata:asset:govern")
    @Operation(summary = "当前空间可选负责人/管家", description = "只返回 ACTIVE 成员")
    public ApiResponse<List<MemberOption>> listMembers() {
        return ApiResponse.ok(governanceService.listMembers());
    }
}
