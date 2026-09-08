package com.datagov.app.controller;

import com.datagov.common.api.ApiResponse;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.LoginResult;
import com.datagov.platform.dto.SessionView;
import com.datagov.platform.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "登录、空间切换、会话")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password) {
    }

    public record SwitchWorkspaceRequest(
            @NotBlank(message = "空间不能为空") String workspaceId) {
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "返回令牌、可访问空间、权限码与菜单树")
    public ApiResponse<LoginResult> login(@jakarta.validation.Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    @PostMapping("/switch-workspace")
    @Operation(summary = "切换空间",
            description = "校验成员资格后重新解析权限并签发新令牌。前端须用新令牌替换旧的。")
    public ApiResponse<LoginResult> switchWorkspace(
            @jakarta.validation.Valid @RequestBody SwitchWorkspaceRequest request) {
        return ApiResponse.ok(
                authService.switchWorkspace(WorkspaceContext.require(), request.workspaceId()));
    }

    @GetMapping("/me")
    @Operation(summary = "当前会话", description = "供前端刷新页面后恢复状态")
    public ApiResponse<SessionView> me() {
        return ApiResponse.ok(authService.describeSession(WorkspaceContext.require()));
    }

    /**
     * 登出。
     *
     * <p>服务端不维护会话状态,因此这里没有实际动作 —— 令牌作废由前端丢弃完成。
     * 保留这个接口是为了给前端一个明确的语义位置,也为将来引入令牌吊销名单
     * (P4 Governance 要求"强制下线"时)留出落点,而不必那时再改前端。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok();
    }
}
