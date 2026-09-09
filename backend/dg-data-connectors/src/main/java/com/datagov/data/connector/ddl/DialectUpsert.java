package com.datagov.data.connector.ddl;

import com.datagov.data.spi.DataSourceType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 各方言的「按主键写入」语句生成(功能 11 的 UPSERT 写入模式)。
 *
 * <p>与 {@link DialectDdl} 并排放:两者是同一件事的两半 —— 那边负责把规范类型
 * 落成建表语句,这边负责把「插入或更新」落成各方言认得的写法。差异集中在一个
 * 文件里,才看得出"Oracle 的 ON 子句里的列不能被 UPDATE""Doris 根本没有
 * ON DUPLICATE KEY"这类事。
 *
 * <p><b>四种写法,不是一种</b>:
 * <ul>
 *   <li>PostgreSQL —— {@code INSERT ... ON CONFLICT (pk) DO UPDATE SET}</li>
 *   <li>MySQL —— {@code INSERT ... ON DUPLICATE KEY UPDATE}</li>
 *   <li>Oracle / SQL Server / 达梦 —— {@code MERGE INTO ... USING ... WHEN MATCHED}</li>
 *   <li>Doris / StarRocks —— <b>普通 INSERT</b>。它们没有 upsert 语法,按主键
 *       去重是<b>表模型</b>的职责(UNIQUE KEY / PRIMARY KEY 模型写入即覆盖)。
 *       目标表若建成了明细模型(DUPLICATE KEY),写多少遍就留多少份 —— 这是
 *       本文件里唯一一种"语句正确但结果仍可能不对"的情形,所以它带 warning。</li>
 * </ul>
 *
 * <p><b>参数顺序恒等于 columns</b>。六种方言的语句形状差得很远,但都被安排成
 * 每个列值只绑定一次、顺序与 {@code columns} 一致 —— 否则调用方要为每种方言
 * 记一套不同的绑定顺序,那是必然出错的地方。MERGE 之所以能做到,是因为值先进
 * {@code USING} 子句成为一行"源",后面的 UPDATE 与 INSERT 都引用它而不再重复绑定。
 */
public final class DialectUpsert {

    private DialectUpsert() {
    }

    /**
     * 一条可批量执行的 upsert 语句。
     *
     * @param sql       带 {@code ?} 占位符的语句,参数顺序与生成时传入的 columns 一致
     * @param warnings  语句本身合法、但结果可能不符合预期的地方
     */
    public record UpsertStatement(String sql, List<String> warnings) {

        public UpsertStatement {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * @param qualifiedTable 已经按方言限定并引用好的表名(见 {@link DialectDdl#quote})
     * @param columns        要写入的列,顺序即参数顺序
     * @param primaryKeys    判断"这行已存在"所依据的列;必须是 columns 的子集
     */
    public static UpsertStatement generate(DataSourceType type, String qualifiedTable,
                                           List<String> columns, List<String> primaryKeys) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("没有要写入的列");
        }
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "UPSERT 必须指定主键 —— 没有主键的「按主键更新」是一句自相矛盾的话");
        }
        // 用 LinkedHashSet 而不是 List.contains:主键重复填两遍会生成
        // "ON t.id = s.id AND t.id = s.id",语法上通过,读起来像是有 bug
        Set<String> keys = new LinkedHashSet<>(primaryKeys);
        List<String> missing = keys.stream().filter(k -> !columns.contains(k)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "主键 %s 不在写入列里 —— 它插入时会是 NULL,永远匹配不上".formatted(missing));
        }

        // 更新的只有非主键列。这不是风格选择:Oracle 的 ON 子句里出现过的列
        // 不允许出现在 UPDATE SET 里(ORA-38104),而更新主键本身也没有意义 ——
        // 那等于把这一行变成另一行。
        List<String> updatable = columns.stream().filter(c -> !keys.contains(c)).toList();

        List<String> warnings = new ArrayList<>();
        if (updatable.isEmpty()) {
            warnings.add("所有写入列都是主键,匹配到的行没有可更新的内容 —— "
                    + "重复的行会被跳过,而不是被更新");
        }

        return switch (type) {
            case POSTGRESQL -> postgres(qualifiedTable, columns, keys, updatable, warnings);
            case MYSQL -> mysql(qualifiedTable, columns, keys, updatable, warnings);
            case DORIS, STARROCKS -> doris(type, qualifiedTable, columns, warnings);
            case ORACLE, DAMENG -> mergeWithDual(type, qualifiedTable, columns, keys,
                    updatable, warnings);
            case SQLSERVER -> mergeWithValues(qualifiedTable, columns, keys, updatable, warnings);
            default -> throw new IllegalArgumentException(
                    "%s 不支持 UPSERT —— 它不是关系型数据源".formatted(type.displayName()));
        };
    }

    // ── 各方言 ──────────────────────────────────────────────────────────

    private static UpsertStatement postgres(String table, List<String> columns,
                                            Set<String> keys, List<String> updatable,
                                            List<String> warnings) {
        DataSourceType t = DataSourceType.POSTGRESQL;
        StringBuilder sql = new StringBuilder(insertHead(t, table, columns));
        sql.append(" ON CONFLICT (").append(joinQuoted(t, keys)).append(')');
        if (updatable.isEmpty()) {
            sql.append(" DO NOTHING");
        } else {
            // EXCLUDED 是 PostgreSQL 给"本来要插入的那一行"起的名字
            sql.append(" DO UPDATE SET ").append(assignments(t, updatable, "EXCLUDED."));
        }
        // ON CONFLICT 认的是<b>约束</b>而不是列:目标表这几列上没有唯一约束或
        // 主键时,PostgreSQL 报 42P10,而不是默默退化成普通 INSERT。这一条要说
        // 在前面 —— 报错信息("there is no unique or exclusion constraint
        // matching the ON CONFLICT specification")对没见过的人不好懂。
        warnings.add("PostgreSQL 的 ON CONFLICT 依赖目标表上真实存在的主键或唯一约束;"
                + "这几列上没有约束时会直接报错(42P10),不会退化成普通插入");
        return new UpsertStatement(sql.toString(), warnings);
    }

    private static UpsertStatement mysql(String table, List<String> columns,
                                         Set<String> keys, List<String> updatable,
                                         List<String> warnings) {
        DataSourceType t = DataSourceType.MYSQL;
        StringBuilder sql = new StringBuilder(insertHead(t, table, columns));
        sql.append(" ON DUPLICATE KEY UPDATE ");
        if (updatable.isEmpty()) {
            // 语法上必须给一条赋值。拿主键给自己赋值是这个场景的惯用写法,
            // 效果等同于"什么都不做"
            String first = DialectDdl.quote(t, keys.iterator().next());
            sql.append(first).append(" = ").append(first);
        } else {
            // VALUES(col) 取的是"本来要插入的那个值"。MySQL 8.0.20 起标记为
            // 废弃(建议改用行别名 AS new),但 5.7、8.x 与 MariaDB 全都认它,
            // 而行别名在 5.7 上是语法错误 —— 兼容面更宽的那个更适合做默认。
            sql.append(assignmentsFromValuesFunction(t, updatable));
        }
        // MySQL 的 ON DUPLICATE KEY 认的是<b>任意</b>唯一索引,不只是我们指定的
        // 那几列。目标表上若还有别的唯一索引,撞上它同样会走 UPDATE 分支 ——
        // 结果是一行被"按另一个键"更新掉了。这与 PostgreSQL 的行为差别很大。
        warnings.add("MySQL 的 ON DUPLICATE KEY 对目标表上的任意唯一索引生效,"
                + "不只是这里指定的主键;表上有其它唯一索引时,撞上它也会走更新分支");
        return new UpsertStatement(sql.toString(), warnings);
    }

    private static UpsertStatement doris(DataSourceType type, String table,
                                         List<String> columns, List<String> warnings) {
        // Doris / StarRocks 没有 upsert 语法。UNIQUE KEY(Doris)与主键模型
        // (StarRocks)本身就是"同键覆盖",所以普通 INSERT 就是 upsert。
        warnings.add("%s 没有 UPSERT 语法,写的是普通 INSERT;按主键覆盖由表模型保证 —— "
                .formatted(type.displayName())
                + "目标表必须建成 UNIQUE KEY(Doris)或主键模型(StarRocks)。"
                + "建成明细模型(DUPLICATE KEY)的话,写几遍就留几份,不会去重");
        return new UpsertStatement(insertHead(type, table, columns), warnings);
    }

    /**
     * Oracle 与达梦:{@code USING (SELECT ? AS c ... FROM dual)}。
     *
     * <p>达梦与 Oracle 在 MERGE 上高度兼容,连 {@code DUAL} 都有 —— 所以共用一份。
     */
    private static UpsertStatement mergeWithDual(DataSourceType type, String table,
                                                 List<String> columns, Set<String> keys,
                                                 List<String> updatable, List<String> warnings) {
        String source = columns.stream()
                .map(c -> "? AS " + DialectDdl.quote(type, c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(table).append(" tgt")
                .append(" USING (SELECT ").append(source).append(" FROM dual) src")
                .append(" ON (").append(joinCondition(type, keys)).append(')');
        appendMergeBranches(type, sql, columns, updatable);
        return new UpsertStatement(sql.toString(), warnings);
    }

    /**
     * SQL Server:{@code USING (VALUES (?, ...)) AS src (cols)}。
     *
     * <p>它没有 {@code DUAL},但有表值构造器,效果一样。
     */
    private static UpsertStatement mergeWithValues(String table, List<String> columns,
                                                   Set<String> keys, List<String> updatable,
                                                   List<String> warnings) {
        DataSourceType t = DataSourceType.SQLSERVER;
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(table).append(" AS tgt")
                .append(" USING (VALUES (").append(placeholders(columns.size())).append("))")
                .append(" AS src (").append(joinQuoted(t, columns)).append(')')
                .append(" ON ").append(joinCondition(t, keys));
        appendMergeBranches(t, sql, columns, updatable);
        // MERGE 在 SQL Server 里<b>必须</b>以分号结束,少了它报 155xx 系列的语法错
        sql.append(';');
        return new UpsertStatement(sql.toString(), warnings);
    }

    private static void appendMergeBranches(DataSourceType type, StringBuilder sql,
                                            List<String> columns, List<String> updatable) {
        if (!updatable.isEmpty()) {
            sql.append(" WHEN MATCHED THEN UPDATE SET ")
                    .append(assignments(type, updatable, "src."));
        }
        // 没有可更新列时只留 WHEN NOT MATCHED 分支 —— 空的 UPDATE SET 是语法错误。
        // 三种方言都允许 MERGE 只有一个分支。
        sql.append(" WHEN NOT MATCHED THEN INSERT (").append(joinQuoted(type, columns))
                .append(") VALUES (")
                .append(columns.stream().map(c -> "src." + DialectDdl.quote(type, c))
                        .reduce((a, b) -> a + ", " + b).orElseThrow())
                .append(')');
    }

    // ── 片段 ────────────────────────────────────────────────────────────

    private static String insertHead(DataSourceType type, String table, List<String> columns) {
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(
                table, joinQuoted(type, columns), placeholders(columns.size()));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /** {@code "c" = <prefix>"c", ...} */
    private static String assignments(DataSourceType type, List<String> columns, String prefix) {
        return columns.stream()
                .map(c -> {
                    String quoted = DialectDdl.quote(type, c);
                    return quoted + " = " + prefix + quoted;
                })
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    /** {@code `c` = VALUES(`c`), ...} —— MySQL 专用 */
    private static String assignmentsFromValuesFunction(DataSourceType type,
                                                        List<String> columns) {
        return columns.stream()
                .map(c -> {
                    String quoted = DialectDdl.quote(type, c);
                    return quoted + " = VALUES(" + quoted + ")";
                })
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    /** {@code tgt."k" = src."k" AND ...} */
    private static String joinCondition(DataSourceType type, Set<String> keys) {
        return keys.stream()
                .map(k -> {
                    String quoted = DialectDdl.quote(type, k);
                    return "tgt." + quoted + " = src." + quoted;
                })
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
    }

    private static String joinQuoted(DataSourceType type, java.util.Collection<String> names) {
        return names.stream().map(n -> DialectDdl.quote(type, n))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }
}
