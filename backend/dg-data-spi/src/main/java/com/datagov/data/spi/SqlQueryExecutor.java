package com.datagov.data.spi;

import com.datagov.data.spi.query.SqlQuery;

/**
 * 自定义 SQL 查询能力(功能 7)。
 *
 * <p>只有 JDBC 类数据源实现它。FTP/SFTP/RestAPI 没有 SQL 的概念,
 * 它们不实现这个接口 —— 而不是实现了再抛 UnsupportedOperationException。
 *
 * <p><b>实现方的义务</b>:
 * <ul>
 *   <li>执行前必须过 {@link com.datagov.data.spi.query.ReadOnlySqlGuard}</li>
 *   <li>必须设置 {@code Statement.setQueryTimeout} 与 {@code setMaxRows} ——
 *       两者都不是可选项,没有它们平台就有能力拖垮别人的生产库</li>
 *   <li>必须以只读事务或只读连接执行(能设的话)</li>
 * </ul>
 */
public interface SqlQueryExecutor extends DataSourceConnector {

    SqlQuery.Result executeQuery(DataSourceType type, ConnectionConfig config, SqlQuery.Request request);
}
