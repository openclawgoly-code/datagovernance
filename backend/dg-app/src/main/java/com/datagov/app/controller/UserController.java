package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户管理 —— 功能 30。 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "用户管理", description = "账号、口令、空间内角色分配")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record CreateUserRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 64, message = "用户名长度 2-64")
            String username,
            @NotBlank(message = "初始口令不能为空")
            @Size(min = 8, message = "口令至少 8 位")
            String password,
            String displayName,
            String email,
            String phone,
            boolean platformAdmin) {
    }

    public record UpdateUserRequest(String displayName, String email, String phone) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, message = "口令至少 8 位") String newPassword) {
    }

    public record AssignRolesRequest(List<String> roleIds) {
    }

    @GetMapping
    @RequirePermission("platform:user:read")
    @Operation(summary = "用户列表")
    public ApiResponse<List<UserView>> list(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(userService.list(keyword));
    }

    @PostMapping
    @RequirePermission("platform:user:create")
    @Operation(summary = "新建用户")
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.create(request.username(), request.password(),
                request.displayName(), request.email(), request.phone(),
                request.platformAdmin(), WorkspaceContext.require().userId()));
    }

    @PutMapping("/{id}")
    @RequirePermission("platform:user:update")
    @Operation(summary = "编辑用户")
    public ApiResponse<UserView> update(@PathVariable String id,
                                        @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(userService.update(id, request.displayName(),
                request.email(), request.phone(), WorkspaceContext.require().userId()));
    }

    @PostMapping("/{id}/reset-password")
    @RequirePermission("platform:user:update")
    @Operation(summary = "重置口令")
    public ApiResponse<Void> resetPassword(@PathVariable String id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword(), WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/status")
    @RequirePermission("platform:user:update")
    @Operation(summary = "启用/停用",
            description = "不能停用最后一个平台管理员 —— 那会把所有人锁在外面")
    public ApiResponse<Void> setStatus(@PathVariable String id, @RequestParam boolean enabled) {
        userService.setStatus(id, enabled, WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("platform:user:delete")
    @Operation(summary = "删除用户")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/roles")
    @RequirePermission("platform:user:read")
    @Operation(summary = "该用户在当前空间下的角色")
    public ApiResponse<List<String>> roles(@PathVariable String id) {
        return ApiResponse.ok(userService.listRoleIds(
                WorkspaceContext.requireWorkspaceId(), id));
    }

    @PostMapping("/{id}/roles")
    @RequirePermission("platform:user:assign-role")
    @Operation(summary = "分配角色",
            description = "全量覆盖当前空间下的角色。同一用户在不同空间可以有不同角色。")
    public ApiResponse<Void> assignRoles(@PathVariable String id,
                                         @RequestBody AssignRolesRequest request) {
        userService.assignRoles(WorkspaceContext.requireWorkspaceId(), id,
                request.roleIds(), WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }
}
