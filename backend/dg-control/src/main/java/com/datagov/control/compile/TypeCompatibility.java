package com.datagov.control.compile;

import com.datagov.data.spi.catalog.CanonicalType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 两个规范类型之间能不能写、写了会不会丢东西。
 *
 * <p><b>这是风险 R7 的第二半。</b> R7 的第一半是类型<b>识别</b>(N 种源类型 ×
 * N 种目标类型 = N² 的映射矩阵),由 {@link CanonicalType} 收敛成 2N。
 * 这里是第二半:识别出来之后,判定这一对能不能对接。因为两边都已经是规范类型,
 * 这张表的规模是 {@code |CanonicalType|²} 而不是 {@code |方言类型|²} ——
 * 加一种数据库不会让它变大。
 *
 * <p>判定<b>只看类型不看长度</b>:VARCHAR(10) → VARCHAR(5) 会截断,但长度信息
 * 在 CanonicalType 里已经被抹掉了。那一层校验属于运行期的目标表 DDL 检查,
 * 放在这里会给出一堆基于陈旧快照的假警报。
 */
public enum TypeCompatibility {

    /** 完全兼容,不丢任何东西 */
    SAFE,

    /**
     * 能写,但可能丢精度或被截断。
     *
     * <p>不拦下来,因为用户往往就是要这么干(把 DECIMAL 的金额抽成 DOUBLE 做分析)。
     * 但必须提醒 —— 这类问题在半年后表现为"报表对不上账",那时已经无从追溯。
     */
    LOSSY,

    /** 写不进去,跑起来一定报错 */
    INCOMPATIBLE;

    /** 数值类型,按精度从低到高 */
    private static final CanonicalType[] NUMERIC_LADDER = {
            CanonicalType.BOOLEAN, CanonicalType.TINYINT, CanonicalType.SMALLINT,
            CanonicalType.INT, CanonicalType.BIGINT, CanonicalType.DECIMAL,
            CanonicalType.FLOAT, CanonicalType.DOUBLE
    };

    private static final Set<CanonicalType> TEXT =
            EnumSet.of(CanonicalType.CHAR, CanonicalType.VARCHAR, CanonicalType.TEXT);
    private static final Set<CanonicalType> BINARY =
            EnumSet.of(CanonicalType.BINARY, CanonicalType.VARBINARY, CanonicalType.BLOB);
    private static final Set<CanonicalType> TEMPORAL =
            EnumSet.of(CanonicalType.DATE, CanonicalType.TIME, CanonicalType.TIMESTAMP,
                    CanonicalType.TIMESTAMP_TZ);

    private static final Map<CanonicalType, Integer> NUMERIC_RANK = new EnumMap<>(CanonicalType.class);

    static {
        for (int i = 0; i < NUMERIC_LADDER.length; i++) {
            NUMERIC_RANK.put(NUMERIC_LADDER[i], i);
        }
    }

    public static TypeCompatibility between(String sourceTypeName, String targetTypeName) {
        CanonicalType source = parse(sourceTypeName);
        CanonicalType target = parse(targetTypeName);
        if (source == null || target == null) {
            // 认不出来的类型不阻断编译。快照可能是旧版本平台写的,
            // 而"因为我不认识这个类型所以你不能发布"是个糟糕的理由。
            return SAFE;
        }
        return between(source, target);
    }

    public static TypeCompatibility between(CanonicalType source, CanonicalType target) {
        if (source == target) {
            return SAFE;
        }

        // UNKNOWN 表示连接器没能把目标端的类型归到任何一类。判 LOSSY 而不是
        // INCOMPATIBLE:阻断发布等于因为"我们看不懂"就不许用户干活,而放行
        // 又该让他知道这一对没被真正校验过。
        if (source == CanonicalType.UNKNOWN || target == CanonicalType.UNKNOWN) {
            return LOSSY;
        }

        // JSON → 文本是无损的(JSON 本来就以文本形式存);反过来要靠目标端解析,
        // 一条格式不对的记录就会让整批失败
        if (source == CanonicalType.JSON && TEXT.contains(target)) {
            return SAFE;
        }

        // 任何类型写进文本都行(退化成字符串),但反过来不一定
        if (TEXT.contains(target)) {
            return TEXT.contains(source) ? textToText(source, target) : LOSSY;
        }
        if (TEXT.contains(source)) {
            // 文本 → 非文本:要靠隐式转换,值里有一个脏数据就整批失败。
            // 判 INCOMPATIBLE 而不是 LOSSY,因为它不是"丢精度",是"会炸"。
            return INCOMPATIBLE;
        }

        if (NUMERIC_RANK.containsKey(source) && NUMERIC_RANK.containsKey(target)) {
            return numericToNumeric(source, target);
        }
        if (TEMPORAL.contains(source) && TEMPORAL.contains(target)) {
            return temporalToTemporal(source, target);
        }
        if (BINARY.contains(source) && BINARY.contains(target)) {
            return SAFE;
        }

        // 跨大类(数值 ↔ 时间、二进制 ↔ 数值……)一律不许
        return INCOMPATIBLE;
    }

    private static TypeCompatibility textToText(CanonicalType source, CanonicalType target) {
        // TEXT → CHAR/VARCHAR 可能被截断;反向安全
        if (source == CanonicalType.TEXT && target != CanonicalType.TEXT) {
            return LOSSY;
        }
        return SAFE;
    }

    private static TypeCompatibility numericToNumeric(CanonicalType source, CanonicalType target) {
        int from = NUMERIC_RANK.get(source);
        int to = NUMERIC_RANK.get(target);

        // 浮点 → 定点:DOUBLE 写进 DECIMAL 会按目标标度四舍五入
        if ((source == CanonicalType.FLOAT || source == CanonicalType.DOUBLE)
                && target == CanonicalType.DECIMAL) {
            return LOSSY;
        }
        // 定点/整数 → 浮点:超过 2^53 的整数在 DOUBLE 里已经不精确了。
        // 表面上"类型变宽了"实际会丢有效位,是最容易被忽略的一种损失。
        if (target == CanonicalType.FLOAT || target == CanonicalType.DOUBLE) {
            return source == CanonicalType.BIGINT || source == CanonicalType.DECIMAL ? LOSSY : SAFE;
        }
        // BOOLEAN 只能进 BOOLEAN 或更宽的整数
        if (source == CanonicalType.BOOLEAN) {
            return SAFE;
        }
        if (target == CanonicalType.BOOLEAN) {
            return INCOMPATIBLE;
        }
        return to >= from ? SAFE : LOSSY;
    }

    private static TypeCompatibility temporalToTemporal(CanonicalType source, CanonicalType target) {
        if (target == CanonicalType.DATE && source != CanonicalType.DATE) {
            return LOSSY;       // 丢时间部分
        }
        if (target == CanonicalType.TIME && source != CanonicalType.TIME) {
            return LOSSY;       // 丢日期部分
        }
        if (source == CanonicalType.DATE && target == CanonicalType.TIME) {
            return INCOMPATIBLE;
        }
        // TIMESTAMP_TZ → TIMESTAMP 丢时区。这是最阴的一种:数据看着还在,
        // 只是从此不知道它是哪个时区的,而错位通常在跨时区查询时才暴露。
        if (source == CanonicalType.TIMESTAMP_TZ && target == CanonicalType.TIMESTAMP) {
            return LOSSY;
        }
        return SAFE;
    }

    private static CanonicalType parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return CanonicalType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
