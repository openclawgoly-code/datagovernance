package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.metadata.dto.CatalogNodeView;
import com.datagov.metadata.service.DataSourceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据源目录(功能 5)。
 *
 * <p>路径用 {@code /datasource-catalogs} 而不是 {@code /catalogs},是为了和
 * {@code /datasources/{id}/catalog}(功能 7 的库表结构浏览)在 URL 上就区分开 ——
 * 两者都叫 catalog,合用一个词根会让接口文档变得难读。
 */
@RestController
@RequestMapping("/api/v1/datasource-catalogs")
@Tag(name = "数据源目录", description = "数据源的人工组织结构(功能5),非库表结构")
public class DataSourceCatalogController {

    private final DataSourceCatalogService catalogService;

    public DataSourceCatalogController(DataSourceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record CatalogRequest(
            String parentId,
            @NotBlank(message = "目录名称不能为空")
            @Size(max = 128, message = "目录名称不得超过 128 字符")
            String name,
            String description,
            Integer sortOrder) {
    }

    public record MoveRequest(String newParentId) {
    }

    public record CatalogTree(List<CatalogNodeView> nodes, int uncategorizedCount) {
    }

    @GetMapping
    @RequirePermission("metadata:catalog:read")
    @Operation(summary = "目录树",
            description = "附带每个目录直接挂载的数据源数量,以及未分类数据源的数量")
    public ApiResponse<CatalogTree> tree() {
        return ApiResponse.ok(new CatalogTree(
                catalogService.tree(), catalogService.uncategorizedCount()));
    }

    @PostMapping
    @RequirePermission("metadata:catalog:manage")
    @Operation(summary = "新建目录")
    public ApiResponse<CatalogNodeView> create(@Valid @RequestBody CatalogRequest request) {
        return ApiResponse.ok(catalogService.create(request.parentId(), request.name(),
                request.description(), request.sortOrder()));
    }

    @PutMapping("/{id}")
    @RequirePermission("metadata:catalog:manage")
    @Operation(summary = "编辑目录")
    public ApiResponse<CatalogNodeView> update(@PathVariable String id,
                                               @Valid @RequestBody CatalogRequest request) {
        return ApiResponse.ok(catalogService.update(id, request.name(),
                request.description(), request.sortOrder()));
    }

    @PostMapping("/{id}/move")
    @RequirePermission("metadata:catalog:manage")
    @Operation(summary = "移动目录",
            description = "禁止移动到自己的子目录下 —— 那会在目录树里造出一个环")
    public ApiResponse<Void> move(@PathVariable String id, @RequestBody MoveRequest request) {
        catalogService.move(id, request.newParentId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("metadata:catalog:manage")
    @Operation(summary = "删除目录",
            description = "非空目录不允许删除。级联删除会让一次误点丢掉整棵子树,"
                    + "静默挪走数据源则让用户找不到自己的东西。")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalogService.delete(id);
        return ApiResponse.ok();
    }
}
