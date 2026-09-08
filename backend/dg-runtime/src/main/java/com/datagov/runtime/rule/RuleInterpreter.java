package com.datagov.runtime.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 规则解释器(功能 17 的执行侧)。
 *
 * <p>规则是<b>双栖对象</b>:定义归 Metadata(那里有 {@code RuleKind} 与参数规格),
 * 解释执行归这里。Runtime 不知道"这条规则叫什么、归谁维护、有没有被别的任务
 * 引用" —— 它拿到的只是一个 kind 加一组参数,照着执行。
 *
 * <p><b>这里没有 dg-metadata 依赖</b>,所以 kind 是字符串而不是枚举。看着不如
 * 枚举优雅,但那条依赖会让 Runtime 能读到规则定义、进而读到整个 Metadata ——
 * 而 Runtime 的 must_not_do 第三条正是「不得直接修改 metadata 定义」。
 * 用字符串换一条不存在的依赖,是划算的。
 *
 * <p>解释器<b>不抛异常</b>:一条脏数据不该让整批同步失败。转换不了的值原样保留,
 * 由调用方决定要不要计数上报。这与"宁可少转换也不要中断"的取舍一致 ——
 * 中断一次几百万行的同步,代价远大于几行没转换成功。
 */
public final class RuleInterpreter {

    private RuleInterpreter() {
    }

    /**
     * 一条规则。
     *
     * @param kind   {@code RuleKind} 的 name()
     * @param params 参数
     */
    public record Rule(String kind, Map<String, String> params) {

        public Rule {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public String param(String key, String fallback) {
            String value = params.get(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        public boolean flag(String key, boolean fallback) {
            String value = params.get(key);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
        }

        public int number(String key, int fallback) {
            try {
                return Integer.parseInt(param(key, String.valueOf(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * 按顺序应用一串规则。
     *
     * <p>顺序有意义:先去空格再判空,与先判空再去空格,对 {@code "  "} 的结果
     * 完全不同。所以规则是 List 而不是 Set,且由用户排序。
     */
    public static Object applyAll(Object value, List<Rule> rules) {
        Object current = value;
        for (Rule rule : rules) {
            current = apply(current, rule);
        }
        return current;
    }

    public static Object apply(Object value, Rule rule) {
        try {
            return switch (rule.kind()) {
                case "NULL_FILL" -> nullFill(value, rule);
                case "TRIM" -> trim(value, rule);
                case "CHANGE_CASE" -> changeCase(value, rule);
                case "STRING_REPLACE" -> stringReplace(value, rule);
                case "AFFIX" -> affix(value, rule);
                case "DATE_FORMAT" -> dateFormat(value, rule);
                case "NUMBER_FORMAT" -> numberFormat(value, rule);
                // 解密需要 Platform 的密钥,不能在这个纯函数里做。由调用方在
                // 组装规则链时把它替换成一个带密钥的实现;这里保持原值不动,
                // 而不是假装解密成功。
                case "DECRYPT" -> value;
                case "MASK" -> mask(value, rule);
                default -> value;
            };
        } catch (RuntimeException e) {
            // 一条脏数据不该让整批同步失败。转换不了就原样保留 ——
            // 中断一次几百万行的同步,代价远大于几行没转换成功。
            //
            // <b>脱敏是这条规矩的例外</b>:原样保留意味着明文流进了目标库,
            // 而那正是脱敏要防的事。所以它在上面自己处理异常,
            // 任何算不出来的情况都返回固定掩码而不是原值。
            return value;
        }
    }

    // ── 脱敏(序号 35)──────────────────────────────────────────────────

    /**
     * 脱敏。
     *
     * <p><b>失败时返回掩码,不返回原值。</b> 这与其他规则的容错方向相反:
     * 别的规则算不出来时保留原值是安全的,而脱敏算不出来时保留原值意味着
     * 明文流进了目标库 —— 健康医疗数据属敏感个人信息(架构风险 R8),
     * 那是一次数据泄露,不是一条没转换成功的记录。
     */
    private static Object mask(Object value, Rule rule) {
        if (value == null) {
            return null;
        }
        try {
            String text = String.valueOf(value);
            String maskChar = rule.param("maskChar", "*");
            char fill = maskChar.isEmpty() ? '*' : maskChar.charAt(0);

            return switch (rule.param("mode", "PARTIAL")) {
                case "FIXED" -> String.valueOf(fill).repeat(Math.min(text.length(), 8));

                case "HASH" -> {
                    // 盐由调用方在组装规则链时从凭据托管取出并注入。
                    // 没有盐时<b>不退化成无盐哈希</b>:身份证号的取值空间有限,
                    // 无盐哈希可以被穷举还原,那不叫脱敏
                    String salt = rule.param("__salt", "");
                    if (salt.isEmpty()) {
                        yield String.valueOf(fill).repeat(8);
                    }
                    yield sha256Hex(salt + text).substring(0, 16);
                }

                default -> {
                    int keepPrefix = Math.max(0, rule.number("keepPrefix", 3));
                    int keepSuffix = Math.max(0, rule.number("keepSuffix", 4));
                    // 太短的值全部遮掉:留头留尾之后只剩一两位的话,
                    // 那一两位加上长度信息往往就够还原了
                    if (text.length() <= keepPrefix + keepSuffix) {
                        yield String.valueOf(fill).repeat(text.length());
                    }
                    yield text.substring(0, keepPrefix)
                            + String.valueOf(fill).repeat(text.length() - keepPrefix - keepSuffix)
                            + text.substring(text.length() - keepSuffix);
                }
            };
        } catch (RuntimeException e) {
            // 算不出来就给一个固定掩码 —— 绝不把原值放过去
            return "********";
        }
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ── 清洗 ────────────────────────────────────────────────────────────

    private static Object nullFill(Object value, Rule rule) {
        boolean blankIsNull = rule.flag("treatBlankAsNull", true);
        boolean isEmpty = value == null
                || (blankIsNull && value instanceof String s && s.isBlank());
        return isEmpty ? rule.param("defaultValue", "") : value;
    }

    private static Object numberFormat(Object value, Rule rule) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (rule.flag("stripGrouping", true)) {
            text = text.replace(",", "");
        }
        int scale = rule.number("scale", -1);
        if (scale < 0) {
            return text;
        }
        // BigDecimal 而不是 double:金额按 double 四舍五入会得到 0.1+0.2 那类结果
        return new BigDecimal(text).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 日期格式转换。
     *
     * <p>源格式可以不填 —— 那时按几种常见格式挨个试。这不是偷懒:源端的日期
     * 字符串格式往往不统一(同一列里既有 {@code 2026-01-01} 又有
     * {@code 2026/01/01}),强制用户填一个源格式反而处理不了这种现实。
     */
    private static Object dateFormat(Object value, Rule rule) {
        if (value == null) {
            return null;
        }
        String target = rule.param("targetPattern", "yyyy-MM-dd");
        DateTimeFormatter targetFormatter = DateTimeFormatter.ofPattern(target);

        if (value instanceof LocalDateTime dt) {
            return dt.format(targetFormatter);
        }
        if (value instanceof LocalDate d) {
            return d.format(targetFormatter);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(targetFormatter);
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().format(targetFormatter);
        }

        String text = String.valueOf(value).trim();
        String source = rule.params().get("sourcePattern");
        List<String> candidates = source != null && !source.isBlank()
                ? List.of(source)
                : List.of("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss",
                        "yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd");

        for (String pattern : candidates) {
            try {
                DateTimeFormatter parser = DateTimeFormatter.ofPattern(pattern);
                if (pattern.contains("H")) {
                    return LocalDateTime.parse(text, parser).format(targetFormatter);
                }
                return LocalDate.parse(text, parser).format(targetFormatter);
            } catch (DateTimeParseException ignored) {
                // 试下一个候选格式
            }
        }
        return value;   // 都试不出来就原样保留
    }

    // ── 转换 ────────────────────────────────────────────────────────────

    private static Object trim(Object value, Rule rule) {
        if (!(value instanceof String s)) {
            return value;
        }
        return switch (rule.param("mode", "BOTH")) {
            case "LEADING" -> s.stripLeading();
            case "TRAILING" -> s.stripTrailing();
            default -> s.strip();
        };
    }

    private static Object changeCase(Object value, Rule rule) {
        if (!(value instanceof String s)) {
            return value;
        }
        return "LOWER".equals(rule.param("mode", "UPPER")) ? s.toLowerCase() : s.toUpperCase();
    }

    private static Object stringReplace(Object value, Rule rule) {
        if (!(value instanceof String s)) {
            return value;
        }
        String search = rule.param("search", "");
        if (search.isEmpty()) {
            return value;
        }
        String replacement = rule.param("replacement", "");
        // 正则是可选的:多数替换是字面量,而对字面量用 replaceAll 会让
        // 「.」「$」这些字符出人意料地生效
        return rule.flag("regex", false)
                ? s.replaceAll(search, replacement)
                : s.replace(search, replacement);
    }

    private static Object affix(Object value, Rule rule) {
        if (!(value instanceof String s)) {
            return value;
        }
        String result = s;
        // 先去后加:两者同时配置时,用户想要的几乎一定是"换一个前缀"
        String stripPrefix = rule.param("stripPrefix", "");
        if (!stripPrefix.isEmpty() && result.startsWith(stripPrefix)) {
            result = result.substring(stripPrefix.length());
        }
        String stripSuffix = rule.param("stripSuffix", "");
        if (!stripSuffix.isEmpty() && result.endsWith(stripSuffix)) {
            result = result.substring(0, result.length() - stripSuffix.length());
        }
        return rule.param("prefix", "") + result + rule.param("suffix", "");
    }
}
