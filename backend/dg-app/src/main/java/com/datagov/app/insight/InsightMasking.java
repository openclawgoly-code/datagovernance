package com.datagov.app.insight;

import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问数结果的兜底脱敏。
 *
 * <p>真正的第一道防线是语义模型:模型里不暴露原始 PII 字段,只暴露已脱敏字段。
 * 这里是第二道防线,处理“模型漏配/直接生成 SELECT *”的情况。
 *
 * <p>匹配方式刻意保守:
 * <ul>
 *   <li>结果列名/内部列名命中敏感字段名 → 脱敏</li>
 *   <li>SQL 的 {@code expr AS alias} 中 expr 命中敏感字段 → alias 脱敏</li>
 * </ul>
 * 不因为 WHERE 里出现敏感字段就遮掉整张结果表 —— 那会把“按手机号过滤”变成
 * “所有列都看不见”,业务上不可用。
 */
public final class InsightMasking {

    private static final Pattern ALIAS = Pattern.compile(
            "(?is)([a-z0-9_.\"`]+(?:\\([^)]*\\))?)\\s+as\\s+[`\"]?([a-z0-9_]+)[`\"]?");

    private InsightMasking() {
    }

    public record Masked(List<Map<String, Object>> rows, List<String> maskedColumns) {
    }

    public static Masked mask(List<Map<String, Object>> rows,
                              List<SuperSonicClient.Column> columns,
                              String sql,
                              List<SensitiveColumnView> sensitiveColumns) {
        if (rows == null || rows.isEmpty() || sensitiveColumns == null
                || sensitiveColumns.isEmpty()) {
            return new Masked(rows == null ? List.of() : rows, List.of());
        }
        Set<String> sensitive = new HashSet<>();
        for (SensitiveColumnView column : sensitiveColumns) {
            if (column.columnName() != null) {
                sensitive.add(normalize(column.columnName()));
            }
        }
        if (sensitive.isEmpty()) {
            return new Masked(rows, List.of());
        }

        Set<String> maskedNames = new HashSet<>(aliasesOf(sql, sensitive));
        for (SuperSonicClient.Column column : columns) {
            if (isSensitive(column.name(), sensitive) || isSensitive(column.bizName(), sensitive)) {
                maskedNames.add(column.name());
                maskedNames.add(column.bizName());
            }
        }
        maskedNames.remove(null);
        if (maskedNames.isEmpty()) {
            return new Masked(rows, List.of());
        }

        Set<String> lowerMasked = new HashSet<>();
        for (String name : maskedNames) {
            lowerMasked.add(normalize(name));
            lowerMasked.add(stripIdentifierQuotes(name).toLowerCase(Locale.ROOT));
        }

        List<Map<String, Object>> maskedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new HashMap<>(row);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (lowerMasked.contains(normalize(entry.getKey()))
                        && entry.getValue() != null) {
                    copy.put(entry.getKey(), "***");
                }
            }
            maskedRows.add(copy);
        }
        List<String> applied = maskedNames.stream().filter(name -> !name.isBlank())
                .sorted().toList();
        return new Masked(maskedRows, applied);
    }

    private static Set<String> aliasesOf(String sql, Set<String> sensitive) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        Set<String> aliases = new HashSet<>();
        Matcher matcher = ALIAS.matcher(sql);
        while (matcher.find()) {
            String expression = normalize(matcher.group(1));
            String alias = matcher.group(2);
            for (String name : sensitive) {
                if (expression.contains(name)) {
                    aliases.add(alias);
                    break;
                }
            }
        }
        return aliases;
    }

    private static boolean isSensitive(String value, Set<String> sensitive) {
        if (value == null) {
            return false;
        }
        String normalized = normalize(value);
        if (sensitive.contains(normalized)) {
            return true;
        }
        // 别名常用 _phone_ / __email__ 包裹,去掉首尾下划线再比一次
        String trimmed = normalized.replaceAll("^_+|_+$", "");
        if (sensitive.contains(trimmed)) {
            return true;
        }
        // 不做 contains:避免 amount 命中 account、name 命中 username 之类的误伤
        return false;
    }

    private static String normalize(String value) {
        return stripIdentifierQuotes(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String stripIdentifierQuotes(String value) {
        return value.replace("`", "").replace("\"", "");
    }
}
