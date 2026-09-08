package com.datagov.data.connector.mapping;

import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型映射的陷阱用例。
 *
 * <p>这些用例针对的都是<b>会静默出错</b>的情况 —— 映射错了不报错,只是在
 * 数据同步时悄悄截断精度或丢掉时区。它们比"varchar 映射成 VARCHAR"这类
 * 显而易见的用例值钱得多。
 */
@DisplayName("类型映射")
class TypeMappersTest {

    @Nested
    @DisplayName("Oracle NUMBER — 最大的映射陷阱")
    class OracleNumber {

        private final TypeMapper mapper = TypeMappers.forType(DataSourceType.ORACLE);

        @Test
        @DisplayName("有小数位一律是定点数")
        void withScaleIsDecimal() {
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 12, 2)).isEqualTo(CanonicalType.DECIMAL);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 38, 10)).isEqualTo(CanonicalType.DECIMAL);
        }

        @Test
        @DisplayName("无小数位时按精度选最小够用的整数类型")
        void integralNumbersPickSmallestSufficientType() {
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 2, 0)).isEqualTo(CanonicalType.TINYINT);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 4, 0)).isEqualTo(CanonicalType.SMALLINT);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 9, 0)).isEqualTo(CanonicalType.INT);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 18, 0)).isEqualTo(CanonicalType.BIGINT);
        }

        @Test
        @DisplayName("精度超过 BIGINT 范围时退回 DECIMAL 保精度,绝不截断")
        void oversizedNumbersFallBackToDecimal() {
            // NUMBER(38) 装不进任何整数类型。映射成 BIGINT 会在同步时静默溢出,
            // 这正是"宁可 DECIMAL 也不硬猜"的意义。
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 38, 0)).isEqualTo(CanonicalType.DECIMAL);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 19, 0)).isEqualTo(CanonicalType.DECIMAL);
        }

        @Test
        @DisplayName("无精度信息时退回 DECIMAL")
        void unknownPrecisionFallsBackToDecimal() {
            assertThat(mapper.map("NUMBER", Types.NUMERIC, null, null)).isEqualTo(CanonicalType.DECIMAL);
        }

        @Test
        @DisplayName("Oracle 的 DATE 含时分秒,必须映射成 TIMESTAMP 而不是 DATE")
        void oracleDateCarriesTime() {
            // 映射成 DATE 会把时间部分丢掉,而且不会有任何报错
            assertThat(mapper.map("DATE", Types.DATE, null, null)).isEqualTo(CanonicalType.TIMESTAMP);
        }

        @Test
        @DisplayName("带时区的 TIMESTAMP 与不带的必须区分")
        void distinguishesZonedTimestamps() {
            assertThat(mapper.map("TIMESTAMP(6)", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP);
            assertThat(mapper.map("TIMESTAMP(6) WITH TIME ZONE", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP_TZ);
            assertThat(mapper.map("TIMESTAMP(6) WITH LOCAL TIME ZONE", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP_TZ);
        }

        @Test
        @DisplayName("区间类型没有安全的对应,标 UNKNOWN 交人工决策")
        void intervalIsUnknown() {
            assertThat(mapper.map("INTERVAL DAY(2) TO SECOND(6)", Types.OTHER, null, null))
                    .isEqualTo(CanonicalType.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("MySQL")
    class MySql {

        private final TypeMapper mapper = TypeMappers.forType(DataSourceType.MYSQL);

        @Test
        @DisplayName("默认不把 tinyint(1) 当布尔")
        void tinyint1IsNotBooleanByDefault() {
            // tinyint(1) 可能是布尔,也可能是取值 0-127 的小整数。
            // 当成布尔会在后者上丢数据且不可逆;当成整数最多是布尔被存成 0/1,可逆。
            assertThat(mapper.map("tinyint", Types.TINYINT, 1, 0)).isEqualTo(CanonicalType.TINYINT);
        }

        @Test
        @DisplayName("显式开启时才把 tinyint(1) 当布尔")
        void tinyint1AsBooleanWhenEnabled() {
            TypeMapper booleanAware = TypeMappers.mySql(true);
            assertThat(booleanAware.map("tinyint", Types.TINYINT, 1, 0)).isEqualTo(CanonicalType.BOOLEAN);
            assertThat(booleanAware.map("tinyint", Types.TINYINT, 4, 0)).isEqualTo(CanonicalType.TINYINT);
        }

        @Test
        @DisplayName("MySQL 的 TIMESTAMP 会做时区转换,语义上带时区")
        void mysqlTimestampIsZoned() {
            assertThat(mapper.map("timestamp", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP_TZ);
            assertThat(mapper.map("datetime", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP);
        }

        @Test
        @DisplayName("unsigned 修饰不影响基础类型识别")
        void stripsUnsignedModifier() {
            assertThat(mapper.map("int unsigned", Types.INTEGER, null, null)).isEqualTo(CanonicalType.INT);
            assertThat(mapper.map("bigint unsigned", Types.BIGINT, null, null)).isEqualTo(CanonicalType.BIGINT);
        }

        @Test
        @DisplayName("长度修饰会被剥离")
        void stripsLengthModifier() {
            assertThat(mapper.map("varchar(255)", Types.VARCHAR, 255, null)).isEqualTo(CanonicalType.VARCHAR);
            assertThat(mapper.map("decimal(10,2)", Types.DECIMAL, 10, 2)).isEqualTo(CanonicalType.DECIMAL);
        }
    }

    @Nested
    @DisplayName("PostgreSQL")
    class PostgreSql {

        private final TypeMapper mapper = TypeMappers.forType(DataSourceType.POSTGRESQL);

        @Test
        @DisplayName("timestamptz 与 timestamp 必须区分")
        void distinguishesZonedTimestamps() {
            assertThat(mapper.map("timestamp", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP);
            assertThat(mapper.map("timestamptz", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP_TZ);
        }

        @Test
        @DisplayName("数组类型识别 —— 驱动会以下划线前缀上报")
        void recognizesArrays() {
            assertThat(mapper.map("_text", Types.ARRAY, null, null)).isEqualTo(CanonicalType.ARRAY);
            assertThat(mapper.map("_int4", Types.ARRAY, null, null)).isEqualTo(CanonicalType.ARRAY);
            assertThat(mapper.map("integer[]", Types.ARRAY, null, null)).isEqualTo(CanonicalType.ARRAY);
        }

        @Test
        @DisplayName("jsonb 与 json 都映射为 JSON")
        void mapsJson() {
            assertThat(mapper.map("jsonb", Types.OTHER, null, null)).isEqualTo(CanonicalType.JSON);
            assertThat(mapper.map("json", Types.OTHER, null, null)).isEqualTo(CanonicalType.JSON);
        }
    }

    @Nested
    @DisplayName("SQL Server 与达梦")
    class SqlServerAndDameng {

        @Test
        @DisplayName("SQL Server 的 timestamp 其实是行版本戳,不是时间 —— 标 UNKNOWN")
        void sqlServerTimestampIsRowVersion() {
            TypeMapper mapper = TypeMappers.forType(DataSourceType.SQLSERVER);
            // 这是 SQL Server 一个著名的命名陷阱。映射成 TIMESTAMP 会让同步任务
            // 试图把一个二进制行版本当时间搬走。
            assertThat(mapper.map("timestamp", Types.BINARY, null, null)).isEqualTo(CanonicalType.UNKNOWN);
            assertThat(mapper.map("rowversion", Types.BINARY, null, null)).isEqualTo(CanonicalType.UNKNOWN);
            assertThat(mapper.map("datetimeoffset", Types.TIMESTAMP, null, null))
                    .isEqualTo(CanonicalType.TIMESTAMP_TZ);
        }

        @Test
        @DisplayName("达梦以 Oracle 映射为底,并覆盖自有类型")
        void damengInheritsOracleAndOverrides() {
            TypeMapper mapper = TypeMappers.forType(DataSourceType.DAMENG);
            // 继承自 Oracle 的部分
            assertThat(mapper.map("VARCHAR2", Types.VARCHAR, 50, null)).isEqualTo(CanonicalType.VARCHAR);
            assertThat(mapper.map("NUMBER", Types.NUMERIC, 9, 0)).isEqualTo(CanonicalType.INT);
            // 达梦自有的
            assertThat(mapper.map("BIT", Types.BIT, null, null)).isEqualTo(CanonicalType.BOOLEAN);
            assertThat(mapper.map("TEXT", Types.LONGVARCHAR, null, null)).isEqualTo(CanonicalType.TEXT);
        }
    }

    @Nested
    @DisplayName("Doris / StarRocks")
    class Doris {

        private final TypeMapper mapper = TypeMappers.forType(DataSourceType.DORIS);

        @Test
        @DisplayName("largeint 是 128 位整数,没有对应的规范整数类型,退到 DECIMAL")
        void largeIntFallsBackToDecimal() {
            assertThat(mapper.map("largeint", Types.BIGINT, null, null)).isEqualTo(CanonicalType.DECIMAL);
        }

        @Test
        @DisplayName("聚合中间态类型映射为二进制")
        void aggregateStateTypesAreBinary() {
            assertThat(mapper.map("hll", Types.OTHER, null, null)).isEqualTo(CanonicalType.BINARY);
            assertThat(mapper.map("bitmap", Types.OTHER, null, null)).isEqualTo(CanonicalType.BINARY);
        }

        @Test
        @DisplayName("StarRocks 与 Doris 共用同一套映射")
        void starRocksSharesMapping() {
            TypeMapper starRocks = TypeMappers.forType(DataSourceType.STARROCKS);
            assertThat(starRocks.map("largeint", Types.BIGINT, null, null)).isEqualTo(CanonicalType.DECIMAL);
        }
    }

    @Nested
    @DisplayName("兜底行为")
    class Fallback {

        @Test
        @DisplayName("非 JDBC 类型没有列的概念,恒为 UNKNOWN")
        void nonJdbcTypesAreAlwaysUnknown() {
            for (DataSourceType type : new DataSourceType[]{
                    DataSourceType.FTP, DataSourceType.SFTP, DataSourceType.REST_API}) {
                assertThat(TypeMappers.forType(type).map("anything", Types.VARCHAR, null, null))
                        .isEqualTo(CanonicalType.UNKNOWN);
            }
        }

        @Test
        @DisplayName("认不出的类型名退回 JDBC 标准类型,再认不出才是 UNKNOWN")
        void unknownNameFallsBackToJdbcType() {
            TypeMapper mapper = TypeMappers.forType(DataSourceType.POSTGRESQL);
            assertThat(mapper.map("some_custom_domain", Types.VARCHAR, null, null))
                    .isEqualTo(CanonicalType.VARCHAR);
            assertThat(mapper.map("some_custom_domain", Types.STRUCT, null, null))
                    .isEqualTo(CanonicalType.UNKNOWN);
        }

        @Test
        @DisplayName("类型名为 null 不应抛异常")
        void nullTypeNameIsSafe() {
            TypeMapper mapper = TypeMappers.forType(DataSourceType.MYSQL);
            assertThat(mapper.map(null, Types.INTEGER, null, null)).isEqualTo(CanonicalType.INT);
        }
    }
}
