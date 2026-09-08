package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.runtime.service.ArtifactService;
import com.datagov.runtime.service.ArtifactService.ArtifactView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 作业制品仓库(序号 32「文件管理」)。
 *
 * <p>菜单在「基础配置」下,归属却是 Runtime(架构风险 R2)。路由前缀用
 * {@code /artifacts} 而不是 {@code /settings/files}:URL 该反映它是什么,
 * 不该反映它在菜单树的哪一层 —— 菜单会改,而改菜单不该改 API。
 */
@RestController
@RequestMapping("/api/v1/artifacts")
@Tag(name = "作业制品", description = "JAR / Python 包的上传、下载与引用管理")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @GetMapping
    @RequirePermission("runtime:artifact:read")
    @Operation(summary = "分页查询制品",
            description = "同时返回本空间的与平台级的 —— 用户选包时不关心它是谁传的")
    public ApiResponse<PageResult<ArtifactView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(artifactService.list(page, size, type, keyword));
    }

    @GetMapping("/{id}")
    @RequirePermission("runtime:artifact:read")
    @Operation(summary = "查询单个制品")
    public ApiResponse<ArtifactView> get(@PathVariable String id) {
        return ApiResponse.ok(artifactService.get(id));
    }

    @PostMapping
    @RequirePermission("runtime:artifact:manage")
    @Operation(summary = "上传制品",
            description = "同名同版本只能上传一次 —— 制品不可变,要改内容就发新版本")
    public ApiResponse<ArtifactView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String description) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "没有收到文件");
        }
        try (var in = file.getInputStream()) {
            return ApiResponse.ok(artifactService.upload(name, version, type, description,
                    file.getOriginalFilename(), in, file.getSize()));
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "读取上传文件失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/content")
    @RequirePermission("runtime:artifact:read")
    @Operation(summary = "下载制品")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        ArtifactView view = artifactService.get(id);
        Path path = artifactService.resolveContent(id);
        String filename = view.originalFilename() == null
                ? view.name() + "-" + view.version()
                : view.originalFilename();
        // 文件名可能含中文,必须走 RFC 5987 的 filename* —— 直接塞进 filename=
        // 会在多数浏览器上变成一串问号
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("runtime:artifact:manage")
    @Operation(summary = "删除制品",
            description = "被任务引用的制品不可删;删除只是逻辑删除,磁盘文件保留以便事故复盘")
    public ApiResponse<Void> delete(@PathVariable String id) {
        artifactService.delete(id);
        return ApiResponse.ok();
    }
}
