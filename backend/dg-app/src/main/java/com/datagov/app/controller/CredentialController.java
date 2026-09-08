package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.dto.CredentialView;
import com.datagov.platform.service.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 凭据管理 —— 功能 4 的凭据托管部分。
 *
 * <p><b>所有响应都不含凭据内容</b>,明文与密文都不返回。这是
 * {@code SPACE-MODEL.md} 的验收项之一,由 {@link CredentialView}
 * 的类型定义保证 —— 那个 record 上根本没有 payload 分量。
 */
@RestController
@RequestMapping("/api/v1/credentials")
@Tag(name = "凭据管理", description = "口令与 Token 的集中托管")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    public record CredentialRequest(
            @NotBlank(message = "凭据名称不能为空") String name,
            @NotNull(message = "认证方式不能为空") CredentialSecret.AuthType authType,
            String username,
            /** 编辑时留空表示保持原口令不变,而不是改成空口令 */
            String secret,
            String description) {
    }

    @GetMapping
    @RequirePermission("platform:credential:read")
    @Operation(summary = "凭据列表", description = "只返回名称、类型与描述,不含任何凭据内容")
    public ApiResponse<List<CredentialView>> list() {
        return ApiResponse.ok(credentialService.list());
    }

    @PostMapping
    @RequirePermission("platform:credential:create")
    @Operation(summary = "新建凭据")
    public ApiResponse<CredentialView> create(@Valid @RequestBody CredentialRequest request) {
        return ApiResponse.ok(credentialService.create(request.name(), request.authType(),
                request.username(), request.secret(), request.description()));
    }

    @PutMapping("/{id}")
    @RequirePermission("platform:credential:update")
    @Operation(summary = "编辑凭据", description = "secret 留空表示保持原口令")
    public ApiResponse<CredentialView> update(@PathVariable String id,
                                              @Valid @RequestBody CredentialRequest request) {
        return ApiResponse.ok(credentialService.update(id, request.name(), request.authType(),
                request.username(), request.secret(), request.description()));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("platform:credential:delete")
    @Operation(summary = "删除凭据")
    public ApiResponse<Void> delete(@PathVariable String id) {
        credentialService.delete(id);
        return ApiResponse.ok();
    }
}
