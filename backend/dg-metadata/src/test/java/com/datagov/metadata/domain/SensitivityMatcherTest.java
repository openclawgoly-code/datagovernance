package com.datagov.metadata.domain;

import com.datagov.metadata.entity.SensitivityEntities.SensitivityRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("敏感规则匹配")
class SensitivityMatcherTest {

    @Test
    @DisplayName("中文身份证字段命中 L4")
    void chineseIdCard() {
        SensitivityRule rule = rule("身份证号", "PII", "L4", "(身份证|证件号|id.?card)");
        assertThat(SensitivityMatcher.match(rule, "身份证号", null))
                .get().satisfies(match -> {
                    assertThat(match.level()).isEqualTo("L4");
                    assertThat(match.category()).isEqualTo("PII");
                });
    }

    @Test
    @DisplayName("英文字段与注释都能命中")
    void englishAndComment() {
        SensitivityRule rule = rule("手机号", "PII", "L3", "(手机号|mobile|phone)");
        assertThat(SensitivityMatcher.match(rule, "mobile_no", null)).isPresent();
        assertThat(SensitivityMatcher.match(rule, "contact", "客户手机号")).isPresent();
    }

    @Test
    @DisplayName("普通业务字段不误报")
    void noFalsePositive() {
        SensitivityRule rule = rule("身份证号", "PII", "L4", "(身份证|证件号|id.?card)");
        assertThat(SensitivityMatcher.match(rule, "order_id", "订单编号")).isEmpty();
        assertThat(SensitivityMatcher.match(rule, "created_at", null)).isEmpty();
    }

    @Test
    @DisplayName("禁用规则 / 非字段名规则 / 坏正则不参与匹配")
    void ignoresUnusableRules() {
        SensitivityRule disabled = rule("身份证号", "PII", "L4", "身份证");
        disabled.setEnabled(false);
        assertThat(SensitivityMatcher.match(disabled, "身份证号", null)).isEmpty();

        SensitivityRule content = rule("手机号", "PII", "L3", "1[3-9]\\d{9}");
        content.setDetectorType("REGEX");
        assertThat(SensitivityMatcher.match(content, "手机号", null)).isEmpty();

        SensitivityRule broken = rule("坏正则", "PII", "L1", "([");
        assertThat(SensitivityMatcher.match(broken, "坏正则", null)).isEmpty();
    }

    private static SensitivityRule rule(String name, String category, String level,
                                        String pattern) {
        SensitivityRule rule = new SensitivityRule();
        rule.setId("rule_" + name);
        rule.setName(name);
        rule.setCategory(category);
        rule.setLevel(level);
        rule.setDetectorType("COLUMN_NAME");
        rule.setPattern(pattern);
        rule.setEnabled(true);
        return rule;
    }
}
