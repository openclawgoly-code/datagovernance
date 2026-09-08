package com.datagov.data.spi.query;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读 SQL 护栏。
 *
 * <p>功能 7 的原话是「支持<b>自定义查询类的</b> SQL 语句」—— 查询类,不是任意语句。
 * 平台在这里持有的是用户配置的数据源凭据,那往往是一个有写权限的账号;
 * 若不加限制,数据源管理页就变成了一个面向所有业务库的通用 SQL 客户端,
 * 任何有"浏览"权限的人都能 DROP 掉别人的表。
 *
 * <p><b>这一层是纵深防御,不是唯一防线。</b> 真正可靠的做法是给平台配一个
 * 只读数据库账号 —— 语法层面的白名单永远可能被某种方言绕过(存储过程、
 * 带副作用的函数、CTE 里的 DML)。因此:
 * <ul>
 *   <li>本类拦掉绝大多数明显的写操作,让误操作在第一时间失败</li>
 *   <li>部署文档必须要求为数据查询配置只读账号</li>
 *   <li>P4 的 Governance 会把每一次查询记入审计</li>
 * </ul>
 * 把这三条都做了,才算把风险降到可接受。
 */
public final class ReadOnlySqlGuard {

    /** 允许的语句起始关键字。白名单而非黑名单 —— 黑名单永远列不全。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "VALUES");

    /**
     * 即便语句以 SELECT 开头也要拦掉的写意图。
     *
     * <p>典型绕过:{@code WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x}
     * ——— PostgreSQL 支持在 CTE 里写 DML,它确实以 WITH 开头。
     */
    private static final Pattern WRITE_INTENT = Pattern.compile(
            "(?is)\\b(INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM|MERGE\\s+INTO|"
                    + "DROP\\s+(TABLE|VIEW|DATABASE|SCHEMA|INDEX|USER)|"
                    + "TRUNCATE\\s+TABLE|ALTER\\s+(TABLE|VIEW|DATABASE|SCHEMA|USER)|"
                    + "CREATE\\s+(TABLE|VIEW|DATABASE|SCHEMA|INDEX|USER|FUNCTION|PROCEDURE)|"
                    + "GRANT\\s|REVOKE\\s|CALL\\s|EXEC(UTE)?\\s|"
                    + "INTO\\s+OUTFILE|INTO\\s+DUMPFILE|LOAD\\s+DATA)\\b");

    private ReadOnlySqlGuard() {
    }

    /**
     * 校验并返回规范化后的单条 SQL。
     *
     * @throws BizException 语句为空、含多条语句、或包含写意图
     */
    public static String requireReadOnly(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED, "SQL 语句不能为空");
        }

        String stripped = stripComments(rawSql).trim();
        // 去掉结尾分号后若还有分号,说明是多条语句 —— 多语句是最常见的注入形态,
        // 也让"只校验第一条"的护栏形同虚设
        String withoutTrailingSemicolon = stripped.endsWith(";")
                ? stripped.substring(0, stripped.length() - 1).trim()
                : stripped;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED,
                    "一次只能执行一条语句,请去掉多余的分号");
        }
        if (withoutTrailingSemicolon.isEmpty()) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED, "SQL 语句不能为空");
        }

        String firstWord = firstWord(withoutTrailingSemicolon).toUpperCase(Locale.ROOT);
        if (!ALLOWED_PREFIXES.contains(firstWord)) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED,
                    "只允许执行查询类语句(%s),当前语句以 %s 开头"
                            .formatted(String.join(" / ", ALLOWED_PREFIXES), firstWord));
        }
        // 检测写意图前必须先把字符串字面量挖空,否则一个正常的查询
        // (WHERE action = 'DELETE FROM t')会被误杀 —— 审计表、日志表里
        // 存着 SQL 关键字是很常见的事。
        if (WRITE_INTENT.matcher(blankStringLiterals(withoutTrailingSemicolon)).find()) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED,
                    "语句中包含写操作或副作用调用,已拒绝执行",
                    "以查询关键字开头不代表整条语句只读,例如 CTE 内可以写 DML");
        }
        return withoutTrailingSemicolon;
    }

    /**
     * 去掉注释。
     *
     * <p>必须先去注释再判断,否则 {@code /*x*}{@code / DELETE FROM t} 这类
     * 以注释开头的语句会让"第一个词"判断失效。
     */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char quote = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            // 字符串字面量里的 -- 和 /* 不是注释
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(c);
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 把字符串字面量的内容替换成等长空格,保留引号本身。
     *
     * <p>只用于关键字检测,不影响真正执行的语句 —— 返回值是丢弃的,
     * 执行的仍是原文。等长替换是为了让任何基于位置的诊断信息仍然对得上。
     */
    static String blankStringLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                }
                out.append(c);
            } else {
                if (c == quote) {
                    quote = 0;
                    out.append(c);
                } else {
                    // 换行保留,免得把多行语句压成一行影响后续判断
                    out.append(c == '\n' ? '\n' : ' ');
                }
            }
        }
        return out.toString();
    }

    private static String firstWord(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        // 允许以 ( 开头的 (SELECT ...) 形式
        while (i < sql.length() && sql.charAt(i) == '(') {
            i++;
            while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
        }
        int start = i;
        while (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return start == i ? sql.substring(start, Math.min(start + 1, sql.length())) : sql.substring(start, i);
    }
}
