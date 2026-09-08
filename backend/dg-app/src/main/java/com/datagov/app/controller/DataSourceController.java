package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import com.datagov.metadata.dto.DataSourceView;
import com.datagov.metadata.entity.DataSourceVersionEntity;
import com.datagov.metadata.service.DataSourceService;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.service.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源接口(功能 1-4、8)。
 *
 * <p><b>这里承担一项 Metadata 不能做的编排</b>:用户在表单里填的口令要先交给
 * Platform 创建一条 Credential,再把 credentialId 传给 Metadata。
 * 放在装配层而不是 Metadata 内部,是为了让「Metadata 不得存储凭据明文」
 * 这条约束在代码结构上成立 —— Metadata 的服务签名里根本没有口令参数。
 */
@RestController
@RequestMapping("/api/v1/datasources")
@Tag(name = "数据源", description = "数据源定义、连通性测试、生命周期")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final CredentialService credentialService;

    public DataSourceController(DataSourceService dataSourceService,
                                CredentialService credentialService) {
        this.dataSourceService = dataSourceService;
        this.credentialService = credentialService;
    }

    /** 表单里填的口令。仅在提交时传输,任何响应都不会返回它。 */
    public record InlineSecret(
            @NotNull CredentialSecret.AuthType authType,
            String username,
            String secret) {
    }

    public record DataSourceRequest(
            @NotBlank(message = "名称不能为空") String name,
            @NotNull(message = "类型不能为空") DataSourceType type,
            String description,
            String host,
            Integer port,
            String databaseName,
            String username,
            Map<String, String> properties,
            String jdbcUrlOverride,
            String baseUrl,
            /** 复用已有凭据 */
            String credentialId,
            /** 新建凭据。与 credentialId 二选一;都为空表示无需认证 */
            InlineSecret inlineSecret,
            Integer connectTimeoutMs,
            Integer readTimeoutMs) {
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    @GetMapping
    @RequirePermission("metadata:datasource:read")
    @Operation(summary = "分页查询数据源")
    public ApiResponse<PageResult<DataSourceView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) DataSourceType type,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(dataSourceService.list(page, size, type, keyword));
    }

    @GetMapping("/{id}")
    @RequirePermission("metadata:datasource:read")
    @Operation(summary = "查询单个数据源")
    public ApiResponse<DataSourceView> get(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.get(id));
    }

    @GetMapping("/{id}/versions")
    @RequirePermission("metadata:datasource:read")
    @Operation(summary = "版本历史")
    public ApiResponse<List<DataSourceVersionEntity>> versions(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.listVersions(id));
    }

    // ── 命令 ────────────────────────────────────────────────────────────

    @PostMapping
    @RequirePermission("metadata:datasource:create")
    @Operation(summary = "新建数据源",
            description = "若提供 inlineSecret,会先在 Platform 创建一条凭据,数据源只保存其引用")
    @Transactional
    public ApiResponse<DataSourceView> create(@Valid @RequestBody DataSourceRequest request) {
        String credentialId = resolveCredentialId(request, null);
        return ApiResponse.ok(dataSourceService.create(toCommand(request, credentialId)));
    }

    @PutMapping("/{id}")
    @RequirePermission("metadata:datasource:update")
    @Operation(summary = "编辑数据源")
    @Transactional
    public ApiResponse<DataSourceView> update(@PathVariable String id,
                                              @Valid @RequestBody DataSourceRequest request) {
        DataSourceView existing = dataSourceService.get(id);
        String credentialId = resolveCredentialId(request, existing.credentialId());
        return ApiResponse.ok(dataSourceService.update(id, toCommand(request, credentialId)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("metadata:datasource:delete")
    @Operation(summary = "删除数据源")
    public ApiResponse<Void> delete(@PathVariable String id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @RequirePermission("metadata:datasource:test")
    @Operation(summary = "测试连通性",
            description = "成功进入 AVAILABLE;失败回到 DRAFT(手工测试失败意味着配置可能没配对)")
    public ApiResponse<ConnectivityResult> test(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.testConnection(id, false));
    }

    @PostMapping("/test")
    @RequirePermission("metadata:datasource:test")
    @Operation(summary = "未保存试连", description = "不落库、不改状态,用于新建表单里的即时验证")
    public ApiResponse<ConnectivityResult> testTransient(@Valid @RequestBody DataSourceRequest request) {
        // 试连用的临时凭据不落库:直接把表单里的口令包成一次性 secret。
        // 若引用了已有凭据则走正常解析路径。
        return ApiResponse.ok(dataSourceService.testTransient(
                toCommand(request, request.credentialId())));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("metadata:datasource:update")
    @Operation(summary = "停用")
    public ApiResponse<DataSourceView> disable(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.disable(id));
    }

    @PostMapping("/{id}/enable")
    @RequirePermission("metadata:datasource:update")
    @Operation(summary = "启用", description = "回到 DRAFT 而非直接可用:停用期间目标端可能已变化")
    public ApiResponse<DataSourceView> enable(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.enable(id));
    }

    @PostMapping("/{id}/archive")
    @RequirePermission("metadata:datasource:delete")
    @Operation(summary = "归档", description = "终态,不可逆")
    public ApiResponse<DataSourceView> archive(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.archive(id));
    }

    // ── 内部编排 ────────────────────────────────────────────────────────

    /**
     * 把表单里的口令落成一条 Credential,返回其 ID。
     *
     * @param existingCredentialId 编辑场景下已绑定的凭据;为空则新建
     */
    private String resolveCredentialId(DataSourceRequest request, String existingCredentialId) {
        if (request.inlineSecret() == null) {
            // 没填口令:沿用请求里指定的凭据,或保持原有绑定
            return request.credentialId() != null ? request.credentialId() : existingCredentialId;
        }
        InlineSecret secret = request.inlineSecret();
        String credentialName = request.name() + " 的凭据";

        if (existingCredentialId != null) {
            credentialService.update(existingCredentialId, credentialName,
                    secret.authType(), secret.username(), secret.secret(),
                    "由数据源「%s」自动维护".formatted(request.name()));
            return existingCredentialId;
        }
        return credentialService.create(credentialName, secret.authType(),
                secret.username(), secret.secret(),
                "由数据源「%s」自动维护".formatted(request.name())).id();
    }

    private static DataSourceUpsertCommand toCommand(DataSourceRequest request, String credentialId) {
        return new DataSourceUpsertCommand(
                request.name(), request.type(), request.description(),
                request.host(), request.port(), request.databaseName(), request.username(),
                request.properties(), request.jdbcUrlOverride(), request.baseUrl(),
                credentialId, request.connectTimeoutMs(), request.readTimeoutMs());
    }
}
