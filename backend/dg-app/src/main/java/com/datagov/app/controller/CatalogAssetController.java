package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.CatalogAssetService;
import com.datagov.metadata.service.CatalogAssetService.ColumnView;
import com.datagov.metadata.service.CatalogAssetService.DatasetDetail;
import com.datagov.metadata.service.CatalogAssetService.DatasetView;
import com.datagov.metadata.service.CatalogAssetService.PartitionView;
import com.datagov.metadata.service.CatalogAssetService.RefreshResult;
import com.datagov.metadata.service.CatalogAssetService.SourceRequest;
import com.datagov.metadata.service.CatalogAssetService.SourceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据目录(P10.1)。
 *
 * <p>首期只接 JDBC 来源:一个 CatalogSource 指向一个已通过连通性测试的数据源,
 * refresh 时把库/模式/表/字段写进规范化 catalog 表。Parquet/Iceberg/HMS 在
 * P10.2 通过 CatalogProvider 接入,不在这里硬塞。
 */
@RestController
@RequestMapping("/api/v1/metadata/catalog")
@Tag(name = "数据目录", description = "规范化元数据采集:来源、库/模式、表、字段")
public class CatalogAssetController {

    private final CatalogAssetService catalogService;

    public CatalogAssetController(CatalogAssetService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/sources")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "数据目录来源列表")
    public ApiResponse<List<SourceView>> listSources() {
        return ApiResponse.ok(catalogService.listSources());
    }

    @PostMapping("/sources")
    @RequirePermission("metadata:asset:manage")
    @Operation(summary = "新建数据目录来源",
            description = "P10.1 仅支持 JDBC;dataSourceId 必须指向当前空间已通过测试的数据源")
    public ApiResponse<SourceView> createSource(@RequestBody SourceRequest request) {
        return ApiResponse.ok(catalogService.createSource(request));
    }

    @GetMapping("/sources/{id}")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "查询单个目录来源")
    public ApiResponse<SourceView> getSource(@PathVariable String id) {
        return ApiResponse.ok(catalogService.getSource(id));
    }

    @DeleteMapping("/sources/{id}")
    @RequirePermission("metadata:asset:manage")
    @Operation(summary = "删除目录来源", description = "逻辑删除;已采集的资产保留,可人工清理")
    public ApiResponse<Void> deleteSource(@PathVariable String id) {
        catalogService.deleteSource(id);
        return ApiResponse.ok();
    }

    @PostMapping("/sources/{id}/refresh")
    @RequirePermission("metadata:asset:refresh")
    @Operation(summary = "采集/刷新目录",
            description = "可重入;长任务不包在单个事务里,失败后重跑即可")
    public ApiResponse<RefreshResult> refresh(@PathVariable String id) {
        return ApiResponse.ok(catalogService.refresh(id));
    }

    @GetMapping("/sources/{id}/datasets")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "目录来源下的表/数据集")
    public ApiResponse<PageResult<DatasetView>> listDatasets(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(catalogService.listDatasets(id, page, size, keyword));
    }

    @GetMapping("/datasets/{id}")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "表/数据集详情", description = "含全部字段")
    public ApiResponse<DatasetDetail> getDataset(@PathVariable String id) {
        return ApiResponse.ok(catalogService.getDataset(id));
    }

    @GetMapping("/datasets/{id}/columns")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "表/数据集的字段")
    public ApiResponse<List<ColumnView>> listColumns(@PathVariable String id) {
        return ApiResponse.ok(catalogService.listColumns(id));
    }

    @GetMapping("/datasets/{id}/partitions")
    @RequirePermission("metadata:asset:read")
    @Operation(summary = "Parquet 数据集的分区", description = "JDBC 表首期恒为空")
    public ApiResponse<List<PartitionView>> listPartitions(@PathVariable String id) {
        return ApiResponse.ok(catalogService.listPartitions(id));
    }
}
