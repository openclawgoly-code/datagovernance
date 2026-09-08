package com.datagov.data.spi;

/**
 * 连接器能力声明。
 *
 * <p>UI 必须<b>问</b>而不是<b>猜</b>一个数据源能做什么。没有这层声明,
 * 前端就会写出 {@code if (type === 'FTP') ...} 这样的类型判断,
 * 于是每加一种数据源就要改前端 —— 正是风险 R7 的传导路径。
 *
 * @param canTestConnection 支持连通性测试(所有类型都应为 true)
 * @param canBrowseCatalog  支持库/表/列结构浏览
 * @param canBrowseFiles    支持目录/文件浏览
 * @param hasDatabaseLevel  目录树是否有"库"这一层
 * @param hasSchemaLevel    目录树是否有"模式"这一层 —— 决定前端画两级还是三级树
 * @param canReadData       支持读取数据(P2 同步任务的源端能力,P1 不使用)
 * @param canWriteData      支持写入数据(P2 同步任务的目标端能力,P1 不使用)
 * @param canCreateTable    支持自动建表(P2 异构建表,P1 不使用)
 */
public record ConnectorCapabilities(
        boolean canTestConnection,
        boolean canBrowseCatalog,
        boolean canBrowseFiles,
        boolean hasDatabaseLevel,
        boolean hasSchemaLevel,
        boolean canReadData,
        boolean canWriteData,
        boolean canCreateTable
) {

    /** 三级树(库 → 模式 → 表): PostgreSQL / Oracle / SQLServer / 达梦 */
    public static ConnectorCapabilities relationalWithSchema() {
        return new ConnectorCapabilities(true, true, false, true, true, true, true, true);
    }

    /** 两级树(库 → 表): MySQL / Doris / StarRocks */
    public static ConnectorCapabilities relationalWithoutSchema() {
        return new ConnectorCapabilities(true, true, false, true, false, true, true, true);
    }

    /** 文件类: FTP / SFTP */
    public static ConnectorCapabilities fileBased() {
        return new ConnectorCapabilities(true, false, true, false, false, true, true, false);
    }

    /** 接口类: RestAPI —— 只能验通、不能浏览结构 */
    public static ConnectorCapabilities httpBased() {
        return new ConnectorCapabilities(true, false, false, false, false, true, false, false);
    }
}
