package com.datagov.app.controller;

import com.datagov.app.runner.TargetNaming;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.data.spi.ddl.DdlGateway;
import com.datagov.data.spi.ddl.TableDdl;
import com.datagov.metadata.dto.DataSourceView;
import com.datagov.metadata.service.CatalogService;
import com.datagov.metadata.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 建表语句的预览(功能 9「预览并修改建表语句」)。
 *
 * <p>只有<b>预览</b>,没有"执行建表"的端点 —— 建表发生在整库迁移的执行期,由
 * Runtime 按用户确认的语句执行。开一个"直接建表"的 HTTP 接口等于给平台加一条
 * 任意 DDL 的通道,而它绕过了执行记录、绕过了审计,也绕过了任务定义的版本。
 *
 * <p>用户改过的语句存回任务定义的 {@code config.ddlOverrides},执行时优先用它。
 */
@RestController
@RequestMapping("/api/v1/ddl")
@Tag(name = "建表语句", description = "整库迁移的目标表 DDL 预览(功能 9)")
public class TableDdlController {

    private final DataSourceService dataSourceService;
    private final CatalogService catalogService;
    private final DdlGateway ddlGateway;

    public TableDdlController(DataSourceService dataSourceService,
                              CatalogService catalogService,
                              DdlGateway ddlGateway) {
        this.dataSourceService = dataSourceService;
        this.catalogService = catalogService;
        this.ddlGateway = ddlGateway;
    }

    /**
     * @param options 方言选项。Doris 的 replication_num / buckets、MySQL 的 charset
     */
    public record PreviewRequest(
            @NotBlank(message = "源数据源不能为空") String sourceDataSourceId,
            String sourceDatabase,
            String sourceSchema,
            @NotBlank(message = "源表不能为空") String sourceTable,

            @NotBlank(message = "目标数据源不能为空") String targetDataSourceId,
            String targetDatabase,
            String targetSchema,
            /** 留空时按源表名 + 命名规则推导 */
            String targetTable,
            String tablePrefix,
            String tableSuffix,
            boolean lowercaseNames,

            Map<String, String> options
    ) {
    }

    public record PreviewResponse(
            String sourceTable,
            String targetTable,
            String targetType,
            /** 可直接放进编辑器的一段脚本 */
            String script,
            List<String> statements,
            /**
             * 生成过程中的提醒(类型降级、方言不支持某个约束……)。
             * <b>UI 必须显示它</b> —— 整库迁移最常见的事故是某个字段悄悄变窄了,
             * 而这类降级只会在这里出现一次。
             */
            List<String> warnings,
            /** 该目标方言支持哪些选项,供 UI 提示 */
            Map<String, String> supportedOptions
    ) {
    }

    @PostMapping("/preview")
    @RequirePermission("control:job:compile")
    @Operation(summary = "生成目标表的建表语句",
            description = "只生成不执行。用户可以改,改完的语句存进任务定义,执行时优先使用")
    public ApiResponse<PreviewResponse> preview(@Valid @RequestBody PreviewRequest request) {
        DataSourceView targetDs = dataSourceService.get(request.targetDataSourceId());
        if (!ddlGateway.supportsDdl(targetDs.type())) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                    "%s 不支持建表".formatted(targetDs.typeDisplayName()),
                    "整库迁移的目标端必须是关系型或 MPP 数据源");
        }

        // 读源表结构走 CatalogService 而不是直接用网关:它已经带了空间隔离、
        // 数据源状态校验与快照复用。绕过它等于把那三件事重新实现一遍,
        // 而重新实现的那一份迟早会漏掉其中之一。
        CatalogModel.CatalogPage page = catalogService.browse(request.sourceDataSourceId(),
                CatalogPath.ofTable(request.sourceDatabase(), request.sourceSchema(),
                        request.sourceTable()), false);
        if (page.columns().isEmpty()) {
            throw new BizException(ErrorCode.MTD_CATALOG_NOT_FOUND,
                    "读不到源表「%s」的结构".formatted(request.sourceTable()));
        }

        String targetTable = request.targetTable() != null && !request.targetTable().isBlank()
                ? request.targetTable()
                : applyNaming(request);

        TableDdl.CreateTableSpec spec = new TableDdl.CreateTableSpec(
                request.targetDatabase(), request.targetSchema(), targetTable,
                page.columns().stream()
                        .map(c -> new TableDdl.ColumnSpec(
                                TargetNaming.column(c.name(), request.lowercaseNames()),
                                c.canonicalType(),
                                c.precision(), c.scale(), c.nullable(), c.comment()))
                        .toList(),
                page.columns().stream().filter(CatalogModel.ColumnInfo::primaryKey)
                        .map(c -> TargetNaming.column(c.name(), request.lowercaseNames()))
                        .toList(),
                null,
                request.options() == null ? Map.of() : request.options());

        TableDdl.GeneratedDdl generated = ddlGateway.generateCreateTable(targetDs.type(), spec);
        return ApiResponse.ok(new PreviewResponse(
                request.sourceTable(), targetTable, targetDs.type().name(),
                generated.asScript(), generated.statements(), generated.warnings(),
                supportedOptions(targetDs.type())));
    }

    private static String applyNaming(PreviewRequest request) {
        return TargetNaming.table(request.sourceTable(), request.tablePrefix(),
                request.tableSuffix(), request.lowercaseNames());
    }

    /**
     * 方言选项说明。
     *
     * <p>硬编码在这里而不是从 {@code DialectDdl} 取,是因为 dg-app 不依赖
     * dg-data-connectors 的内部类 —— 那是 Data Space 的实现细节。重复的代价
     * 是两处要同步,收益是装配层不穿透进连接器实现。
     */
    private static Map<String, String> supportedOptions(DataSourceType type) {
        return switch (type) {
            case MYSQL -> Map.of("charset", "字符集,默认 utf8mb4");
            case DORIS, STARROCKS -> Map.of(
                    "buckets", "分桶数,默认 10",
                    "replication_num", "副本数,默认 1(生产集群应设为 3)");
            default -> Map.of();
        };
    }
}
