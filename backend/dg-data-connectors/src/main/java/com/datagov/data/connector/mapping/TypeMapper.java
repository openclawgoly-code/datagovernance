package com.datagov.data.connector.mapping;

import com.datagov.data.spi.catalog.CanonicalType;

/**
 * 把目标端原始类型映射到平台规范类型。
 *
 * <p>每个引擎一个实现。这是风险 R7(连接器矩阵爆炸)最重要的收敛点:
 * 不做规范化时异构类型映射是 N×N 张表,引入中间类型后降为 2N。
 *
 * <p><b>实现纪律</b>: 拿不准时返回 {@link CanonicalType#UNKNOWN},不要硬猜。
 * 错误的类型映射会在数据同步时静默截断精度(把 NUMBER(38) 映射成 INT 是
 * 典型事故),比映射失败更难发现 —— 失败会报错,截断不会。
 */
@FunctionalInterface
public interface TypeMapper {

    /**
     * @param rawTypeName 目标端类型名,如 {@code NUMBER}、{@code varchar2}、{@code timestamptz}
     * @param jdbcType    {@link java.sql.Types} 常量,驱动给出的标准类型
     * @param precision   精度(总位数 / 字符长度),取不到为 null
     * @param scale       标度(小数位),取不到为 null
     */
    CanonicalType map(String rawTypeName, int jdbcType, Integer precision, Integer scale);
}
