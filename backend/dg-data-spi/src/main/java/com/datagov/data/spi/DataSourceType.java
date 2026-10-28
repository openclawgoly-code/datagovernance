package com.datagov.data.spi;

/**
 * 平台支持的数据源类型(功能 1-4)。
 *
 * <p>类型被刻意拆得比"菜单"更细: 菜单上 Doris 与 StarRocks 常并列为一项,
 * 但二者的建表语法、分区模型与系统表并不相同,合并会在 P2 的异构建表
 * (风险 R7)上付出代价,所以在类型系统里从一开始就分开。
 *
 * <p>反过来,MySQL / Doris / StarRocks 共用 MySQL 线协议,因此它们
 * 共享驱动而不共享方言 —— {@link #driverClassName()} 相同、方言实现不同。
 */
public enum DataSourceType {

    // ── 关系型(功能 1)────────────────────────────────────────────
    MYSQL("MySQL", Family.RELATIONAL, "com.mysql.cj.jdbc.Driver", 3306),
    POSTGRESQL("PostgreSQL", Family.RELATIONAL, "org.postgresql.Driver", 5432),
    ORACLE("Oracle", Family.RELATIONAL, "oracle.jdbc.OracleDriver", 1521),
    SQLSERVER("SQLServer", Family.RELATIONAL, "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),
    /** 达梦 DM8 —— 信创关系库主力 */
    DAMENG("达梦DM8", Family.RELATIONAL, "dm.jdbc.driver.DmDriver", 5236),
    /** IBM Db2 —— 金蝶 EAS 老版本、部分用友 NC 部署 */
    DB2("IBM Db2", Family.RELATIONAL, "com.ibm.db2.jcc.DB2Driver", 50000),
    /** 人大金仓 KingbaseES —— 泛微/用友信创场景 */
    KINGBASE("人大金仓 KingbaseES", Family.RELATIONAL, "com.kingbase8.Driver", 54321),

    // ── MPP 分析型(功能 3)───────────────────────────────────────
    DORIS("Apache Doris", Family.MPP, "com.mysql.cj.jdbc.Driver", 9030),
    STARROCKS("StarRocks", Family.MPP, "com.mysql.cj.jdbc.Driver", 9030),

    // ── 文件(功能 2)─────────────────────────────────────────────
    FTP("FTP", Family.FILE, null, 21),
    SFTP("SFTP", Family.FILE, null, 22),

    // ── 接口(功能 4)─────────────────────────────────────────────
    REST_API("RestAPI", Family.HTTP, null, 443),

    // ── 对象存储:Parquet 数据湖的存放位置 ───────────────────────────
    /** 本地/挂载目录(NFS、CephFS 等),开发与私有化部署用;也用于测试 */
    LOCAL("本地/NFS 目录", Family.OBJECT_STORAGE, null, 0),
    /** S3 及 S3 兼容对象存储;MinIO/OSS 也可用各自类型以获得默认端口语义 */
    S3("S3 / S3 兼容", Family.OBJECT_STORAGE, null, 443),
    MINIO("MinIO", Family.OBJECT_STORAGE, null, 9000),
    OSS("阿里云 OSS", Family.OBJECT_STORAGE, null, 443);

    /**
     * 数据源族 —— 决定该类型能提供哪些 Capability,以及 UI 该渲染哪种浏览器
     * (表树 / 目录树 / 接口详情)。
     */
    public enum Family {
        RELATIONAL,
        MPP,
        FILE,
        HTTP,
        OBJECT_STORAGE
    }

    private final String displayName;
    private final Family family;
    private final String driverClassName;
    private final int defaultPort;

    DataSourceType(String displayName, Family family, String driverClassName, int defaultPort) {
        this.displayName = displayName;
        this.family = family;
        this.driverClassName = driverClassName;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public Family family() {
        return family;
    }

    /** JDBC 驱动类名;非 JDBC 类型(FTP/SFTP/RestAPI)返回 null。 */
    public String driverClassName() {
        return driverClassName;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public boolean isJdbc() {
        return driverClassName != null;
    }
}
