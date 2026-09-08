package com.datagov.data.spi.ddl;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;

/**
 * 能生成并执行建表语句的连接器(功能 9)。
 *
 * <p>与 {@code RelationalCatalogReader} / {@code SqlQueryExecutor} 一样是一个
 * <b>能力接口</b>:不是所有连接器都该有它。FTP 上没有"表"可建,RestAPI 更没有 ——
 * 让它们实现一个只能抛 UnsupportedOperationException 的方法,是把编译期能拦住的
 * 错误推迟到运行期。
 *
 * <p>生成与执行分成两个方法,对应功能 9 的「<b>预览并修改</b>建表语句」:
 * 用户看到 {@link #generateCreateTable} 的初稿,改完之后由 {@link #executeDdl}
 * 执行<b>用户最终确认的那一份</b>,而不是重新生成一遍。这个区别很重要 ——
 * 若执行时重新生成,用户的修改会被静默丢弃。
 */
public interface DdlGenerator extends DataSourceConnector {

    /**
     * 按目标方言生成建表语句。
     *
     * <p>不连目标库 —— 这是纯粹的字符串生成,可以在预览阶段随便调用。
     */
    TableDdl.GeneratedDdl generateCreateTable(DataSourceType type, TableDdl.CreateTableSpec spec);

    /**
     * 执行 DDL。
     *
     * <p><b>执行的是传进来的语句,不重新生成。</b> 用户在预览里改过的内容
     * 必须原样生效,否则「预览并修改」这个功能就是假的。
     *
     * @param statements 用户确认后的语句,按顺序执行
     * @throws Exception 建表失败按异常抛出 —— 与连通性测试不同,这里的调用方
     *                   是执行引擎,它本来就要把异常翻译成执行失败
     */
    void executeDdl(DataSourceType type, ConnectionConfig config,
                    java.util.List<String> statements) throws Exception;
}
