package com.datagov.data.spi;

import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogPath;

import java.util.List;

/**
 * <b>Metadata Space 与 Data Space 之间唯一合法的接触面。</b>
 *
 * <p>Metadata 不认识任何具体连接器,也不认识 {@link DataSourceConnector} 的实现类;
 * 它只知道有这么一个网关,能"给我测一下"和"给我下钻一层"。具体驱动的选择、
 * 连接池、超时与异常翻译全部发生在 Data Space 内部。
 *
 * <p>这条边的方向是 <b>Metadata → Data</b>,且只允许 Query 语义:
 * 网关上不会出现任何写入、建表或任务提交方法。P2 引入同步任务时,
 * 数据的实际读写由 Runtime Space 发起,而不是从这里开一个口子 ——
 * 一旦这里出现 {@code writeData()},Metadata 就获得了执行能力,
 * Space 边界即告失守。
 *
 * <p>实现位于 {@code dg-data-connectors},由 {@code dg-app} 在运行时注入。
 */
public interface DataAccessGateway {

    /** 平台当前实际可用的数据源类型 —— 驱动缺失的类型不会出现在这里。 */
    List<DataSourceType> availableTypes();

    /** 查询某类型的能力声明,供 UI 决定渲染方式。 */
    ConnectorCapabilities capabilities(DataSourceType type);

    /**
     * 连通性测试(功能 1-4 的「测试连接」)。
     *
     * <p>与 {@link DataSourceConnector#testConnection} 一样,连接失败以返回值表达而非异常。
     */
    ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config);

    /**
     * 按路径下钻一层,返回该层的内容(功能 5「浏览库表结构」)。
     *
     * <p>返回哪一类内容由 {@code path} 的层级与数据源族共同决定:
     * <pre>
     *   关系型三级(PG/Oracle/SQLServer/达梦): ROOT→库, DATABASE→模式, SCHEMA→表, TABLE→列
     *   关系型两级(MySQL/Doris/StarRocks):    ROOT→库, DATABASE→表,   TABLE→列
     *   文件型(FTP/SFTP):                     PATH→文件条目
     * </pre>
     */
    CatalogPage browse(DataSourceType type, ConnectionConfig config, CatalogPath path);
}
