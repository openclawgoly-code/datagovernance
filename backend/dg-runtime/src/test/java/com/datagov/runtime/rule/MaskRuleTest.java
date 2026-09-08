package com.datagov.runtime.rule;

import com.datagov.runtime.rule.RuleInterpreter.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏(序号 35)。
 *
 * <p>健康医疗数据属敏感个人信息(架构风险 R8)。这些断言里最重要的一条不是
 * "遮对了几位",而是<b>任何算不出来的情况都不会放过原值</b> —— 别的规则
 * 出错时保留原值是安全的,脱敏出错时保留原值就是一次数据泄露。
 */
class MaskRuleTest {

    private static Rule mask(Map<String, String> params) {
        return new Rule("MASK", params);
    }

    @Nested
    @DisplayName("部分遮蔽(默认)")
    class Partial {

        @Test
        @DisplayName("身份证号保留前 3 后 4")
        void idCard() {
            Object result = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "PARTIAL")));
            assertThat(result).isEqualTo("320***********4512");
            // 关键:中间那一段真的没了,不是被截断
            assertThat(String.valueOf(result)).doesNotContain("199003");
        }

        @Test
        @DisplayName("手机号可以自定义保留位数")
        void phone() {
            Object result = RuleInterpreter.apply("13812345678",
                    mask(Map.of("mode", "PARTIAL", "keepPrefix", "3", "keepSuffix", "4")));
            assertThat(result).isEqualTo("138****5678");
        }

        @Test
        @DisplayName("遮蔽字符可以换")
        void customMaskChar() {
            Object result = RuleInterpreter.apply("13812345678",
                    mask(Map.of("mode", "PARTIAL", "maskChar", "#")));
            assertThat(String.valueOf(result)).contains("#").doesNotContain("*");
        }

        @Test
        @DisplayName("太短的值整个遮掉 —— 留头留尾之后剩一两位就够还原了")
        void shortValueFullyMasked() {
            Object result = RuleInterpreter.apply("李四", mask(Map.of("mode", "PARTIAL")));
            assertThat(result).isEqualTo("**");
            assertThat(String.valueOf(result)).doesNotContain("李").doesNotContain("四");
        }

        @Test
        @DisplayName("长度恰好等于保留位数时也整个遮掉")
        void exactLengthFullyMasked() {
            Object result = RuleInterpreter.apply("1234567",
                    mask(Map.of("mode", "PARTIAL", "keepPrefix", "3", "keepSuffix", "4")));
            assertThat(result).isEqualTo("*******");
        }
    }

    @Nested
    @DisplayName("哈希(不可逆但可比较)")
    class Hash {

        @Test
        @DisplayName("有盐时同一个值总是脱成同一个 —— 仍能 join 与去重")
        void stableWithSalt() {
            Rule rule = mask(Map.of("mode", "HASH", "__salt", "s3cr3t"));
            Object a = RuleInterpreter.apply("320981199003074512", rule);
            Object b = RuleInterpreter.apply("320981199003074512", rule);
            assertThat(a).isEqualTo(b);
            assertThat(String.valueOf(a)).doesNotContain("3209");
        }

        @Test
        @DisplayName("不同的值脱成不同的结果")
        void distinctInputsDistinctOutputs() {
            Rule rule = mask(Map.of("mode", "HASH", "__salt", "s3cr3t"));
            assertThat(RuleInterpreter.apply("A", rule))
                    .isNotEqualTo(RuleInterpreter.apply("B", rule));
        }

        @Test
        @DisplayName("换了盐结果就变 —— 盐真的参与了计算")
        void saltMatters() {
            Object a = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "HASH", "__salt", "salt-a")));
            Object b = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "HASH", "__salt", "salt-b")));
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("没有盐时<b>不退化成无盐哈希</b>,而是给固定掩码")
        void noSaltDoesNotDegrade() {
            // 身份证号的取值空间有限,无盐哈希可以被穷举还原 —— 那不叫脱敏。
            // 所以缺盐时宁可给一个什么都不含的掩码
            Object result = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "HASH")));
            assertThat(result).isEqualTo("********");
        }
    }

    @Nested
    @DisplayName("整体替换")
    class Fixed {

        @Test
        @DisplayName("全部替换,且不泄露长度")
        void fixedMask() {
            Object shortValue = RuleInterpreter.apply("张三", mask(Map.of("mode", "FIXED")));
            Object longValue = RuleInterpreter.apply(
                    "江苏省盐城市亭湖区某某街道某某小区3号楼201", mask(Map.of("mode", "FIXED")));
            // 长值被截到固定长度:否则掩码的长度本身就泄露了原值的长度
            assertThat(String.valueOf(longValue)).hasSizeLessThanOrEqualTo(8);
            assertThat(String.valueOf(shortValue)).doesNotContain("张");
        }
    }

    @Nested
    @DisplayName("绝不放过原值")
    class NeverLeak {

        @Test
        @DisplayName("null 仍然是 null —— 没有值就没有什么可泄露的")
        void nullStaysNull() {
            assertThat(RuleInterpreter.apply(null, mask(Map.of("mode", "PARTIAL")))).isNull();
        }

        @Test
        @DisplayName("未知模式落到部分遮蔽,而不是原样返回")
        void unknownModeStillMasks() {
            Object result = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "NOT_A_REAL_MODE")));
            assertThat(String.valueOf(result)).contains("*");
            assertThat(String.valueOf(result)).doesNotContain("199003");
        }

        @Test
        @DisplayName("参数是垃圾时也不放过原值")
        void garbageParamsStillMask() {
            Object result = RuleInterpreter.apply("320981199003074512",
                    mask(Map.of("mode", "PARTIAL", "keepPrefix", "不是数字",
                            "keepSuffix", "也不是")));
            assertThat(String.valueOf(result)).doesNotContain("199003");
        }

        @Test
        @DisplayName("负数的保留位数被夹到 0,不会变成越界")
        void negativeKeepIsClamped() {
            Object result = RuleInterpreter.apply("13812345678",
                    mask(Map.of("mode", "PARTIAL", "keepPrefix", "-5", "keepSuffix", "-5")));
            assertThat(result).isEqualTo("***********");
        }

        @Test
        @DisplayName("非字符串的值也被脱敏 —— 数字类型的身份证号同样敏感")
        void nonStringValuesAreMasked() {
            Object result = RuleInterpreter.apply(13812345678L, mask(Map.of("mode", "PARTIAL")));
            assertThat(String.valueOf(result)).contains("*");
        }
    }
}
