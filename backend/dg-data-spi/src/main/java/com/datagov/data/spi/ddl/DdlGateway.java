package com.datagov.data.spi.ddl;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;

import java.util.List;

/**
 * 建表能力的网关(功能 9)。
 *
 * <p><b>刻意不合并进 {@code DataAccessGateway}。</b> 那个网关的类注释写着
 * 「网关上不会出现任何写入、建表或任务提交方法 —— 一旦这里出现 writeData(),
 * Metadata 就获得了执行能力,Space 边界即告失守」。把 DDL 加到它上面,
 * 会让每一个注入了 DataAccessGateway 的 Metadata 服务顺带获得建表能力。
 *
 * <p>所以是两个网关:{@code DataAccessGateway} 是 Metadata → Data 的<b>只读</b>面,
 * 本接口是 Runtime → Data 的<b>写</b>面。
 *
 * <p><b>Metadata 不得注入本接口。</b> 这一条目前只能靠评审保证 —— 两个接口都在
 * dg-data-spi 里,编译器拦不住。真正的编译期证明要等 Data Space 拆成独立进程,
 * 那时读面与写面是两个不同的 HTTP 端点。在此之前,把理由写在这里,
 * 让违反它的人至少读到过一次。
 */
public interface DdlGateway {

    /**
     * 生成建表语句(不连库)。
     *
     * <p>纯字符串生成,可以在预览阶段随便调 —— 功能 9 的「预览并修改建表语句」
     * 就靠它。
     */
    TableDdl.GeneratedDdl generateCreateTable(DataSourceType type, TableDdl.CreateTableSpec spec);

    /**
     * 执行 DDL。
     *
     * <p>执行传进来的语句,<b>不重新生成</b> —— 用户在预览里改过的内容必须
     * 原样生效,否则「预览并修改」就是假的。
     */
    void executeDdl(DataSourceType type, ConnectionConfig config, List<String> statements)
            throws Exception;

    /** 该类型是否支持建表。FTP / RestAPI 上没有"表"可建。 */
    boolean supportsDdl(DataSourceType type);
}
