package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 空间(租户)管理 —— 功能 28。
 *
 * <p>注意「空间」在本平台是租户概念,与架构文档里的 Space(边界)同名不同物。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "空间管理", description = "租户、授权用户、鉴权密钥")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    public record WorkspaceRequest(
            @NotBlank(message = "空间标识不能为空")
            @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{1,63}$",
                    message = "空间标识只能用小写字母、数字、下划线和连字符,长度 2-64")
            String code,
            @NotBlank(message = "空间名称不能为空") String name,
            String description) {
    }

    public record MembersRequest(List<String> userIds) {
    }

    public record StatusRequest(boolean enabled) {
    }

    @GetMapping
    @Operation(summary = "我可访问的空间",
            description = "平台管理员看全部;普通用户只看被授权的。不需要额外权限码 —— "
                    + "任何登录用户都得知道自己能进哪些空间。")
    public ApiResponse<List<WorkspaceView>> listAccessible() {
        return ApiResponse.ok(workspaceService.listAccessible(WorkspaceContext.require().userId()));
    }

    @PostMapping
    @RequirePermission("platform:workspace:create")
    @Operation(summary = "新建空间",
            description = "返回体不含密钥;鉴权密钥需通过轮换接口单独获取")
    public ApiResponse<WorkspaceView> create(@Valid @RequestBody WorkspaceRequest request) {
        return ApiResponse.ok(workspaceService.create(request.code(), request.name(),
                request.description(), WorkspaceContext.require().userId()));
    }

    @PutMapping("/{id}")
    @RequirePermission("platform:workspace:update")
    @Operation(summary = "编辑空间")
    public ApiResponse<WorkspaceView> update(@PathVariable String id,
                                             @Valid @RequestBody WorkspaceRequest request) {
        return ApiResponse.ok(workspaceService.update(id, request.name(),
                request.description(), WorkspaceContext.require().userId()));
    }

    /**
     * 启用 / 停用空间(功能 28)。
     *
     * <p>要的是 {@code platform:workspace:create} 而不是 {@code :update} ——
     * 停用一个空间会让里面所有人立刻失去访问,影响面与创建/销毁同级,
     * 不该和"改个空间名"共用一个权限码。而内置的空间管理员角色恰好被排除在
     * {@code workspace:create} 之外(见 V2 迁移),因此空间管理员停不掉自己的空间,
     * 这正是想要的:否则一个空间管理员可以把整个空间连同其他管理员一起锁死。
     */
    @PostMapping("/{id}/status")
    @RequirePermission("platform:workspace:create")
    @Operation(summary = "启用/停用空间",
            description = "停用不删除任何数据,仅拒绝非平台管理员的访问;幂等")
    public ApiResponse<WorkspaceView> setStatus(@PathVariable String id,
                                                @RequestBody StatusRequest request) {
        return ApiResponse.ok(workspaceService.setStatus(id, request.enabled(),
                WorkspaceContext.require().userId()));
    }

    @GetMapping("/{id}/admins")
    @RequirePermission("platform:workspace:member")
    @Operation(summary = "空间管理员列表",
            description = "空间管理员即在该空间下被授予内置 WORKSPACE_ADMIN 角色的用户")
    public ApiResponse<List<String>> admins(@PathVariable String id) {
        return ApiResponse.ok(workspaceService.listAdminIds(id));
    }

    @PostMapping("/{id}/admins/{userId}")
    @RequirePermission("platform:user:assign-role")
    @Operation(summary = "指定空间管理员",
            description = "同时把该用户加入空间成员;幂等")
    public ApiResponse<Void> grantAdmin(@PathVariable String id, @PathVariable String userId) {
        workspaceService.grantAdmin(id, userId, WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/admins/{userId}")
    @RequirePermission("platform:user:assign-role")
    @Operation(summary = "取消空间管理员", description = "保留其空间成员身份;幂等")
    public ApiResponse<Void> revokeAdmin(@PathVariable String id, @PathVariable String userId) {
        workspaceService.revokeAdmin(id, userId, WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/members")
    @RequirePermission("platform:workspace:member")
    @Operation(summary = "授权用户列表")
    public ApiResponse<List<String>> members(@PathVariable String id) {
        return ApiResponse.ok(workspaceService.listMemberIds(id));
    }

    @PostMapping("/{id}/members")
    @RequirePermission("platform:workspace:member")
    @Operation(summary = "添加授权用户", description = "幂等,重复添加不报错")
    public ApiResponse<Void> addMembers(@PathVariable String id,
                                        @RequestBody MembersRequest request) {
        workspaceService.addMembers(id, request.userIds(), WorkspaceContext.require().userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequirePermission("platform:workspace:member")
    @Operation(summary = "移除授权用户")
    public ApiResponse<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        workspaceService.removeMember(id, userId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/rotate-secret")
    @RequirePermission("platform:workspace:update")
    @Operation(summary = "轮换鉴权密钥",
            description = "返回的 secretKey 是它唯一一次以明文出现的机会,之后库里只有密文。"
                    + "旧密钥标记为 RETIRED 而非立即失效,给调用方留出切换时间。")
    public ApiResponse<String> rotateSecret(@PathVariable String id) {
        return ApiResponse.ok(
                workspaceService.rotateSecret(id, WorkspaceContext.require().userId()));
    }
}
