package com.datagov.data.spi;

import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogPath;

import java.util.List;

/**
 * 关系型/MPP 数据源的结构浏览能力(功能 5「浏览库表结构」)。
 *
 * <p>与 {@link DataSourceConnector#testConnection} 不同,这些方法<b>允许</b>抛出
 * {@link com.datagov.common.error.BizException} —— 浏览结构失败是异常路径,
 * 不是需要呈现给用户的业务结果。
 */
public interface RelationalCatalogReader extends DataSourceConnector {

    List<DatabaseInfo> listDatabases(DataSourceType type, ConnectionConfig config);

    /**
     * 列出模式。对无独立 schema 层的引擎(MySQL/Doris/StarRocks),
     * 实现应返回空列表,并在 {@link ConnectorCapabilities#hasSchemaLevel()} 中声明 false。
     */
    List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config, CatalogPath path);

    List<TableInfo> listTables(DataSourceType type, ConnectionConfig config, CatalogPath path);

    List<ColumnInfo> listColumns(DataSourceType type, ConnectionConfig config, CatalogPath path);
}
