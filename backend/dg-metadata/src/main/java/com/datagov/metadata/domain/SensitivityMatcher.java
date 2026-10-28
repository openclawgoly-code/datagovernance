package com.datagov.metadata.domain;

import com.datagov.metadata.entity.SensitivityEntities.SensitivityRule;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 敏感规则匹配。
 *
 * <p>纯函数,便于单测。当前只支持按字段名/注释匹配(COLUMN_NAME);
 * 内容扫描需要读取样本值,由后续 ScanService 接入。
 *
 * <p>规则里的 pattern 是正则,不是 SQL LIKE —— 写错的正则不应该让整次扫描
 * 失败,所以这里吞掉 PatternSyntaxException 并返回 empty,由调用方记录告警。
 */
public final class SensitivityMatcher {

    private SensitivityMatcher() {
    }

    public record Match(String ruleId, String ruleName, String category,
                        String level, double confidence) {
    }

    public static Optional<Match> match(SensitivityRule rule, String columnName, String comment) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())
                || !"COLUMN_NAME".equalsIgnoreCase(rule.getDetectorType())
                || rule.getPattern() == null || rule.getPattern().isBlank()) {
            return Optional.empty();
        }
        String text = ((columnName == null ? "" : columnName) + " "
                + (comment == null ? "" : comment)).toLowerCase(Locale.ROOT);
        try {
            if (Pattern.compile(rule.getPattern(),
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text).find()) {
                return Optional.of(new Match(rule.getId(), rule.getName(),
                        rule.getCategory(), rule.getLevel(), 0.85));
            }
        } catch (PatternSyntaxException ignored) {
            // 坏规则不应该让整次扫描失败;由调用方在规则列表里修正
        }
        return Optional.empty();
    }
}
