package com.datagov.control.compile;

import com.datagov.data.spi.catalog.CanonicalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.datagov.control.compile.TypeCompatibility.INCOMPATIBLE;
import static com.datagov.control.compile.TypeCompatibility.LOSSY;
import static com.datagov.control.compile.TypeCompatibility.SAFE;
import static com.datagov.data.spi.catalog.CanonicalType.BIGINT;
import static com.datagov.data.spi.catalog.CanonicalType.BOOLEAN;
import static com.datagov.data.spi.catalog.CanonicalType.DATE;
import static com.datagov.data.spi.catalog.CanonicalType.DECIMAL;
import static com.datagov.data.spi.catalog.CanonicalType.DOUBLE;
import static com.datagov.data.spi.catalog.CanonicalType.INT;
import static com.datagov.data.spi.catalog.CanonicalType.JSON;
import static com.datagov.data.spi.catalog.CanonicalType.SMALLINT;
import static com.datagov.data.spi.catalog.CanonicalType.TEXT;
import static com.datagov.data.spi.catalog.CanonicalType.TIMESTAMP;
import static com.datagov.data.spi.catalog.CanonicalType.TIMESTAMP_TZ;
import static com.datagov.data.spi.catalog.CanonicalType.UNKNOWN;
import static com.datagov.data.spi.catalog.CanonicalType.VARCHAR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型兼容判定 —— 风险 R7 的第二半。
 */
@DisplayName("类型兼容判定")
class TypeCompatibilityTest {

    @Nested
    @DisplayName("数值")
    class Numeric {

        @Test
        @DisplayName("变宽安全,变窄有损")
        void wideningIsSafeNarrowingIsLossy() {
            assertThat(TypeCompatibility.between(INT, BIGINT)).isEqualTo(SAFE);
            assertThat(TypeCompatibility.between(SMALLINT, INT)).isEqualTo(SAFE);
            assertThat(TypeCompatibility.between(BIGINT, INT)).isEqualTo(LOSSY);
        }

        @Test
        @DisplayName("BIGINT → DOUBLE 是有损的,尽管看着'变宽了'")
        void bigintToDoubleLosesPrecision() {
            // 超过 2^53 的整数在 DOUBLE 里已经不精确。这是最容易被忽略的一种损失:
            // 类型看着变宽,有效位却少了。
            assertThat(TypeCompatibility.between(BIGINT, DOUBLE)).isEqualTo(LOSSY);
            assertThat(TypeCompatibility.between(INT, DOUBLE)).isEqualTo(SAFE);
        }

        @Test
        @DisplayName("DOUBLE → DECIMAL 会按目标标度四舍五入")
        void doubleToDecimalRounds() {
            assertThat(TypeCompatibility.between(DOUBLE, DECIMAL)).isEqualTo(LOSSY);
        }

        @Test
        @DisplayName("数值写进 BOOLEAN 不允许")
        void numericToBooleanIsRejected() {
            assertThat(TypeCompatibility.between(INT, BOOLEAN)).isEqualTo(INCOMPATIBLE);
            assertThat(TypeCompatibility.between(BOOLEAN, INT)).isEqualTo(SAFE);
        }
    }

    @Nested
    @DisplayName("文本")
    class Text {

        @Test
        @DisplayName("任何类型写进文本都行,反过来不行")
        void anythingToTextButNotBack() {
            assertThat(TypeCompatibility.between(INT, VARCHAR)).isEqualTo(LOSSY);
            assertThat(TypeCompatibility.between(TIMESTAMP, TEXT)).isEqualTo(LOSSY);
            // 文本 → 非文本靠隐式转换,一条脏数据就整批失败。是"会炸"不是"丢精度"
            assertThat(TypeCompatibility.between(VARCHAR, INT)).isEqualTo(INCOMPATIBLE);
        }

        @Test
        @DisplayName("TEXT → VARCHAR 可能被截断")
        void textToVarcharMayTruncate() {
            assertThat(TypeCompatibility.between(TEXT, VARCHAR)).isEqualTo(LOSSY);
            assertThat(TypeCompatibility.between(VARCHAR, TEXT)).isEqualTo(SAFE);
        }

        @Test
        @DisplayName("JSON → 文本无损:JSON 本来就以文本形式存")
        void jsonToTextIsSafe() {
            assertThat(TypeCompatibility.between(JSON, TEXT)).isEqualTo(SAFE);
        }
    }

    @Nested
    @DisplayName("时间")
    class Temporal {

        @Test
        @DisplayName("TIMESTAMP → DATE 丢时间部分")
        void timestampToDateLosesTime() {
            assertThat(TypeCompatibility.between(TIMESTAMP, DATE)).isEqualTo(LOSSY);
            assertThat(TypeCompatibility.between(DATE, TIMESTAMP)).isEqualTo(SAFE);
        }

        @Test
        @DisplayName("带时区 → 不带时区丢时区,是最阴的一种损失")
        void droppingTimezoneIsLossy() {
            // 数据看着还在,只是从此不知道它是哪个时区的。
            // 错位通常要到跨时区查询时才暴露。
            assertThat(TypeCompatibility.between(TIMESTAMP_TZ, TIMESTAMP)).isEqualTo(LOSSY);
            assertThat(TypeCompatibility.between(TIMESTAMP, TIMESTAMP_TZ)).isEqualTo(SAFE);
        }
    }

    @Test
    @DisplayName("跨大类一律拒绝")
    void crossCategoryIsRejected() {
        assertThat(TypeCompatibility.between(INT, DATE)).isEqualTo(INCOMPATIBLE);
        assertThat(TypeCompatibility.between(TIMESTAMP, DECIMAL)).isEqualTo(INCOMPATIBLE);
    }

    @Test
    @DisplayName("UNKNOWN 判 LOSSY 而不是拒绝 —— 不因为看不懂就不许用户干活")
    void unknownWarnsRatherThanBlocks() {
        assertThat(TypeCompatibility.between(UNKNOWN, VARCHAR)).isEqualTo(LOSSY);
        assertThat(TypeCompatibility.between(INT, UNKNOWN)).isEqualTo(LOSSY);
        assertThat(TypeCompatibility.between(UNKNOWN, UNKNOWN)).isEqualTo(SAFE);
    }

    @Test
    @DisplayName("同类型恒安全 —— 对全部 CanonicalType 成立")
    void identityIsAlwaysSafe() {
        for (CanonicalType type : CanonicalType.values()) {
            assertThat(TypeCompatibility.between(type, type))
                    .as("%s → %s", type, type)
                    .isEqualTo(SAFE);
        }
    }

    @Test
    @DisplayName("认不出的类型名不阻断编译")
    void unparseableTypeNameDoesNotBlock() {
        assertThat(TypeCompatibility.between("SOME_FUTURE_TYPE", "VARCHAR")).isEqualTo(SAFE);
        assertThat(TypeCompatibility.between(null, "VARCHAR")).isEqualTo(SAFE);
    }
}
