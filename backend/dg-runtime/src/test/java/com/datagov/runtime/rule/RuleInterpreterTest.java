package com.datagov.runtime.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("规则解释器(功能 17 执行侧)")
class RuleInterpreterTest {

    private static RuleInterpreter.Rule rule(String kind, String... kv) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new RuleInterpreter.Rule(kind, params);
    }

    @Nested
    @DisplayName("清洗")
    class Cleanse {

        @Test
        @DisplayName("缺失值填充:null 与空白都算空")
        void nullFill() {
            var r = rule("NULL_FILL", "defaultValue", "未知");
            assertThat(RuleInterpreter.apply(null, r)).isEqualTo("未知");
            assertThat(RuleInterpreter.apply("   ", r)).isEqualTo("未知");
            assertThat(RuleInterpreter.apply("有值", r)).isEqualTo("有值");
        }

        @Test
        @DisplayName("空白是否算空可以关掉 —— 有些业务里空串是有意义的")
        void blankCanBeMeaningful() {
            var r = rule("NULL_FILL", "defaultValue", "X", "treatBlankAsNull", "false");
            assertThat(RuleInterpreter.apply("  ", r)).isEqualTo("  ");
            assertThat(RuleInterpreter.apply(null, r)).isEqualTo("X");
        }

        @Test
        @DisplayName("数值格式:去千分位并按标度四舍五入")
        void numberFormat() {
            var r = rule("NUMBER_FORMAT", "scale", "2");
            assertThat(RuleInterpreter.apply("1,234.567", r)).isEqualTo(new BigDecimal("1234.57"));
        }

        @Test
        @DisplayName("金额用 BigDecimal 而不是 double —— 否则会出现 0.1+0.2 那类结果")
        void moneyUsesBigDecimal() {
            var r = rule("NUMBER_FORMAT", "scale", "2");
            Object result = RuleInterpreter.apply("0.005", r);
            assertThat(result).isInstanceOf(BigDecimal.class);
            // HALF_UP:0.005 → 0.01,而 double 的 0.005 实际是 0.00499... 会舍成 0.00
            assertThat(result).isEqualTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("日期格式:不填源格式时按常见格式挨个试")
        void dateFormatGuessesSourcePattern() {
            var r = rule("DATE_FORMAT", "targetPattern", "yyyy-MM-dd");
            // 同一列里既有 - 又有 / 是常态,强制填一个源格式反而处理不了
            assertThat(RuleInterpreter.apply("2026/03/15", r)).isEqualTo("2026-03-15");
            assertThat(RuleInterpreter.apply("2026-03-15", r)).isEqualTo("2026-03-15");
            assertThat(RuleInterpreter.apply("20260315", r)).isEqualTo("2026-03-15");
        }

        @Test
        @DisplayName("日期格式:JDBC 取回的时间类型也能处理")
        void dateFormatHandlesJdbcTypes() {
            var r = rule("DATE_FORMAT", "targetPattern", "yyyy-MM-dd");
            assertThat(RuleInterpreter.apply(java.sql.Date.valueOf("2026-03-15"), r))
                    .isEqualTo("2026-03-15");
            assertThat(RuleInterpreter.apply(
                    java.sql.Timestamp.valueOf("2026-03-15 10:30:00"), r))
                    .isEqualTo("2026-03-15");
        }

        @Test
        @DisplayName("认不出的日期原样保留 —— 不因为一行脏数据中断整批同步")
        void unparseableDateIsKept() {
            var r = rule("DATE_FORMAT", "targetPattern", "yyyy-MM-dd");
            assertThat(RuleInterpreter.apply("不是日期", r)).isEqualTo("不是日期");
        }
    }

    @Nested
    @DisplayName("转换")
    class Transform {

        @Test
        @DisplayName("去空格支持三种模式")
        void trim() {
            assertThat(RuleInterpreter.apply("  x  ", rule("TRIM"))).isEqualTo("x");
            assertThat(RuleInterpreter.apply("  x  ", rule("TRIM", "mode", "LEADING")))
                    .isEqualTo("x  ");
            assertThat(RuleInterpreter.apply("  x  ", rule("TRIM", "mode", "TRAILING")))
                    .isEqualTo("  x");
        }

        @Test
        @DisplayName("大小写")
        void changeCase() {
            assertThat(RuleInterpreter.apply("aBc", rule("CHANGE_CASE", "mode", "UPPER")))
                    .isEqualTo("ABC");
            assertThat(RuleInterpreter.apply("aBc", rule("CHANGE_CASE", "mode", "LOWER")))
                    .isEqualTo("abc");
        }

        @Test
        @DisplayName("字符串替换默认按字面量,不是正则")
        void replaceIsLiteralByDefault() {
            // 对字面量用 replaceAll 会让「.」出人意料地匹配任意字符
            var literal = rule("STRING_REPLACE", "search", "a.c", "replacement", "X");
            assertThat(RuleInterpreter.apply("a.c abc", literal)).isEqualTo("X abc");

            var regex = rule("STRING_REPLACE", "search", "a.c", "replacement", "X",
                    "regex", "true");
            assertThat(RuleInterpreter.apply("a.c abc", regex)).isEqualTo("X X");
        }

        @Test
        @DisplayName("前后缀:先去后加 —— 同时配置时用户想要的是「换一个前缀」")
        void affixStripsBeforeAdding() {
            var r = rule("AFFIX", "stripPrefix", "old_", "prefix", "new_");
            assertThat(RuleInterpreter.apply("old_name", r)).isEqualTo("new_name");
        }

        @Test
        @DisplayName("解密不在这里做 —— 它需要 Platform 的密钥,原值保留而不是假装成功")
        void decryptIsNotDoneHere() {
            var r = rule("DECRYPT", "algorithm", "AES_GCM", "credentialId", "cred_1");
            assertThat(RuleInterpreter.apply("ciphertext", r)).isEqualTo("ciphertext");
        }

        @Test
        @DisplayName("非字符串值不被字符串规则破坏")
        void nonStringValuesArePreserved() {
            assertThat(RuleInterpreter.apply(42, rule("TRIM"))).isEqualTo(42);
            assertThat(RuleInterpreter.apply(42, rule("CHANGE_CASE", "mode", "UPPER")))
                    .isEqualTo(42);
        }
    }

    @Test
    @DisplayName("规则按顺序应用 —— 顺序不同结果不同")
    void orderMatters() {
        Object trimThenFill = RuleInterpreter.applyAll("  ", List.of(
                rule("TRIM"),
                rule("NULL_FILL", "defaultValue", "空的")));
        assertThat(trimThenFill).isEqualTo("空的");

        // 反过来:先判空(空白算空)→ 填成 "空的",再去空格 → 还是 "空的"
        Object fillThenTrim = RuleInterpreter.applyAll("  ", List.of(
                rule("NULL_FILL", "defaultValue", " 空的 "),
                rule("TRIM")));
        assertThat(fillThenTrim).isEqualTo("空的");
    }

    @Test
    @DisplayName("未知规则原样跳过,不抛异常")
    void unknownRuleIsSkipped() {
        assertThat(RuleInterpreter.apply("x", rule("SOME_FUTURE_RULE"))).isEqualTo("x");
    }

    @Test
    @DisplayName("规则内部出错时原值保留 —— 一行脏数据不该中断几百万行的同步")
    void ruleFailureKeepsOriginalValue() {
        // scale 是数字但值不是数字 —— BigDecimal 构造会抛,应被接住
        var r = rule("NUMBER_FORMAT", "scale", "2");
        assertThat(RuleInterpreter.apply("不是数字", r)).isEqualTo("不是数字");
    }
}
