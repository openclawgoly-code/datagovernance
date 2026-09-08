package com.datagov.data.spi.catalog;

/**
 * 规范化类型系统。
 *
 * <p>这是风险 R7(连接器矩阵爆炸)最重要的一处收敛。若不做规范化,
 * 异构建表的类型映射就是 N×N 张表(MySQL→Doris、Oracle→达梦、达梦→PostgreSQL…);
 * 引入中间类型后降为 2N —— 每个连接器只需实现"我的类型 ↔ 规范类型"两个方向。
 *
 * <p>P1 只用它描述读到的结构;P2 的异构建表(功能 11/12/13)会以它为中枢。
 * 因此这里的取值必须足够克制: 宁可用 {@link #UNKNOWN} 保留原始类型名,
 * 也不要为了"看起来完备"硬塞一个语义不准的映射 —— 错误的类型映射
 * 会在数据同步时静默截断精度,比映射失败更难发现。
 */
public enum CanonicalType {

    // 布尔
    BOOLEAN,

    // 整数(按宽度区分,避免 Oracle NUMBER(38) 落到 INT 上被截断)
    TINYINT,
    SMALLINT,
    INT,
    BIGINT,

    // 浮点与定点
    FLOAT,
    DOUBLE,
    /** 定点数,精度/标度由 {@link ColumnInfo#precision()} / {@link ColumnInfo#scale()} 携带 */
    DECIMAL,

    // 字符
    CHAR,
    VARCHAR,
    TEXT,

    // 时间
    DATE,
    TIME,
    TIMESTAMP,
    /** 带时区的时间戳 —— 与 TIMESTAMP 分开,跨库同步时时区语义丢失是经典事故 */
    TIMESTAMP_TZ,

    // 二进制
    BINARY,
    VARBINARY,
    BLOB,

    // 半结构化
    JSON,
    ARRAY,

    /** 无法安全映射。原始类型名保留在 {@link ColumnInfo#rawType()} 中,由人工决策。 */
    UNKNOWN
}
