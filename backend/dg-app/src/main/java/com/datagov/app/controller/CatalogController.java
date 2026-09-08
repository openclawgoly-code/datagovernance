package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.metadata.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库表结构浏览与数据源类型元信息(功能 5)。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "结构浏览", description = "库表列下钻、数据源类型与能力声明")
public class CatalogController {

    private final CatalogService catalogService;
    private final DataAccessGateway gateway;

    public CatalogController(CatalogService catalogService, DataAccessGateway gateway) {
        this.catalogService = catalogService;
        this.gateway = gateway;
    }

    /**
     * 数据源类型描述。
     *
     * <p>前端<b>必须</b>据此渲染表单与目录树,而不是自己写
     * {@code if (type === 'MYSQL')}。每加一种数据源就要改前端,
     * 正是架构风险 R7(连接器矩阵爆炸)的传导路径。
     */
    public record DataSourceTypeInfo(
            DataSourceType type,
            String displayName,
            DataSourceType.Family family,
            int defaultPort,
            boolean jdbc,
            ConnectorCapabilities capabilities) {
    }

    @GetMapping("/datasource-types")
    @Operation(summary = "可用的数据源类型及其能力",
            description = "只返回驱动确实就绪的类型。信创环境里达梦等驱动常需现场安装,"
                    + "缺失时该类型不会出现在这里,而不是等用户建完数据源才报错。")
    public ApiResponse<List<DataSourceTypeInfo>> types() {
        List<DataSourceTypeInfo> infos = gateway.availableTypes().stream()
                .map(type -> new DataSourceTypeInfo(
                        type, type.displayName(), type.family(), type.defaultPort(),
                        type.isJdbc(), gateway.capabilities(type)))
                .toList();
        return ApiResponse.ok(infos);
    }

    /**
     * 按路径下钻一层。
     *
     * <p>返回哪一类内容由路径层级与该数据源的能力共同决定:
     * <pre>
     *   三级树(PG/Oracle/SQLServer/达梦): 无参→库, database→模式, +schema→表, +table→列
     *   两级树(MySQL/Doris/StarRocks):   无参→库, database→表,   +table→列
     *   文件型(FTP/SFTP):                path→文件条目
     * </pre>
     */
    @GetMapping("/datasources/{id}/catalog")
    @RequirePermission("metadata:datasource:browse")
    @Operation(summary = "浏览库表结构",
            description = "默认走快照缓存;refresh=true 强制重新探测目标库")
    public ApiResponse<CatalogPage> browse(
            @PathVariable String id,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "false") boolean refresh) {

        CatalogPath catalogPath = path != null && !path.isBlank()
                ? CatalogPath.ofPath(path)
                : new CatalogPath(blankToNull(database), blankToNull(schema), blankToNull(table), null);

        return ApiResponse.ok(catalogService.browse(id, catalogPath, refresh));
    }

    @GetMapping("/datasources/{id}/capabilities")
    @RequirePermission("metadata:datasource:read")
    @Operation(summary = "该数据源的能力声明", description = "前端据此决定画几级树")
    public ApiResponse<ConnectorCapabilities> capabilities(@PathVariable String id) {
        return ApiResponse.ok(catalogService.capabilities(id));
    }

    /** 查询参数里的空串与缺失是同一个意思,但 CatalogPath 只认 null。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
