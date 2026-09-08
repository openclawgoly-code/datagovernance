package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.MenuNode;
import com.datagov.platform.dto.RoleView;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 角色与权限 —— 功能 29。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "角色权限", description = "角色定义与权限全集")
public class RoleController {

    private final RoleService roleService;
    private final PermissionService permissionService;

    public RoleController(RoleService roleService, PermissionService permissionService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    public record RoleRequest(
            @NotBlank(message = "角色标识不能为空") String code,
            @NotBlank(message = "角色名称不能为空") String name,
            String description,
            List<String> permissionCodes) {
    }

    @GetMapping("/roles")
    @RequirePermission("platform:role:read")
    @Operation(summary = "当前空间可用角色",
            description = "平台内置角色(对所有空间可见)+ 本空间自定义角色")
    public ApiResponse<List<RoleView>> list() {
        return ApiResponse.ok(roleService.listAvailable());
    }

    @PostMapping("/roles")
    @RequirePermission("platform:role:create")
    @Operation(summary = "新建角色")
    public ApiResponse<RoleView> create(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(roleService.create(request.code(), request.name(),
                request.description(), request.permissionCodes(),
                WorkspaceContext.require().userId()));
    }

    @PutMapping("/roles/{id}")
    @RequirePermission("platform:role:update")
    @Operation(summary = "编辑角色", description = "内置角色不可修改")
    public ApiResponse<RoleView> update(@PathVariable String id,
                                        @Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(roleService.update(id, request.name(), request.description(),
                request.permissionCodes(), WorkspaceContext.require().userId()));
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission("platform:role:delete")
    @Operation(summary = "删除角色", description = "已被授予给用户的角色不可删除")
    public ApiResponse<Void> delete(@PathVariable String id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/permissions")
    @RequirePermission("platform:role:read")
    @Operation(summary = "权限全集(树形)",
            description = "供角色配置页勾选。节点上的 ownerSpace 标明该权限归属哪个架构 Space。")
    public ApiResponse<List<MenuNode>> permissions() {
        return ApiResponse.ok(permissionService.listAllPermissions());
    }
}
