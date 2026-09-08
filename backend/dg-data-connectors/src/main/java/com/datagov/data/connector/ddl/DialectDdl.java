package com.datagov.data.connector.ddl;

import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.ddl.TableDdl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 各方言的建表语句渲染(功能 9)。
 *
 * <p><b>这是 R7 收敛的兑现处。</b> 若没有 {@link CanonicalType},"从 Oracle 迁到
 * Doris"需要一张 Oracle 类型 × Doris 类型的映射表,N 种数据库就是 N² 张;
 * 有了规范类型之后,每种数据库只需要两张:读进来一张(方言 → 规范,在
 * {@code TypeMappers} 里),写出去一张(规范 → 方言,就是这里)。N 种数据库
 * 2N 张表,而不是 N²。
 *
 * <p>写成静态方法而不是每种方言一个类,是因为这些渲染逻辑<b>短而密集</b>:
 * 放在一起才看得出"Doris 没有 TEXT 所以退化成 STRING""达梦的 CLOB 不能建索引"
 * 这类差异,分成六个文件之后没有人会去对照着读。
 */
public final class DialectDdl {

    private DialectDdl() {
    }

    /** VARCHAR 未给长度时的默认值。太小会截断,太大在某些引擎上浪费行空间。 */
    private static final int DEFAULT_VARCHAR_LENGTH = 255;
    /** DECIMAL 未给精度时的默认值。金额类字段最常见的形状。 */
    private static final int DEFAULT_DECIMAL_PRECISION = 18;
    private static final int DEFAULT_DECIMAL_SCALE = 4;

    public static TableDdl.GeneratedDdl generate(DataSourceType type,
                                                 TableDdl.CreateTableSpec spec) {
        List<String> warnings = new ArrayList<>();
        String table = qualifiedName(type, spec);

        List<String> columnClauses = new ArrayList<>(spec.columns().size());
        for (TableDdl.ColumnSpec column : spec.columns()) {
            columnClauses.add(renderColumn(type, column, spec, warnings));
        }
        if (columnClauses.isEmpty()) {
            warnings.add("没有任何列,生成的建表语句不可用");
        }

        return switch (type) {
            case MYSQL -> mysql(table, columnClauses, spec, warnings);
            case DORIS, STARROCKS -> doris(type, table, columnClauses, spec, warnings);
            case POSTGRESQL -> postgres(table, columnClauses, spec, warnings);
            case ORACLE -> oracle(table, columnClauses, spec, warnings);
            case SQLSERVER -> sqlServer(table, columnClauses, spec, warnings);
            case DAMENG -> dameng(table, columnClauses, spec, warnings);
            default -> throw new IllegalArgumentException(
                    "%s 不支持建表 —— 它不是关系型数据源".formatted(type.displayName()));
        };
    }

    // ── 各方言 ──────────────────────────────────────────────────────────

    private static TableDdl.GeneratedDdl mysql(String table, List<String> columns,
                                               TableDdl.CreateTableSpec spec,
                                               List<String> warnings) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns));
        if (!spec.primaryKeys().isEmpty()) {
            sql.append(",\n  PRIMARY KEY (")
                    .append(joinQuoted(DataSourceType.MYSQL, spec.primaryKeys())).append(')');
        }
        sql.append("\n)");
        // utf8mb4 而不是 utf8:MySQL 的 "utf8" 只有三字节,存不下 emoji 与部分
        // 生僻汉字。这是一个至今仍在制造事故的历史遗留默认值。
        sql.append(" ENGINE=InnoDB DEFAULT CHARSET=")
                .append(spec.options().getOrDefault("charset", "utf8mb4"));
        appendComment(sql, spec.comment(), " COMMENT=");
        return new TableDdl.GeneratedDdl(List.of(sql.toString()), warnings);
    }

    private static TableDdl.GeneratedDdl doris(DataSourceType type, String table,
                                               List<String> columns,
                                               TableDdl.CreateTableSpec spec,
                                               List<String> warnings) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns)).append("\n)");

        // Doris 必须声明数据模型与分桶。没有主键时用 DUPLICATE(明细模型)——
        // 那是唯一不要求键的模型。选错模型的后果是重复数据被静默聚合掉。
        List<String> keys = spec.primaryKeys();
        if (keys.isEmpty()) {
            // 明细模型的 DUPLICATE KEY 必须是前若干列,取第一列即可
            String firstColumn = spec.columns().isEmpty() ? null : spec.columns().get(0).name();
            if (firstColumn != null) {
                sql.append("\nDUPLICATE KEY(").append(quote(type, firstColumn)).append(')');
                warnings.add("源表没有主键,目标表使用明细模型(DUPLICATE KEY),"
                        + "重复写入不会去重");
            }
        } else {
            sql.append("\nUNIQUE KEY(").append(joinQuoted(type, keys)).append(')');
        }
        appendComment(sql, spec.comment(), "\nCOMMENT ");

        String buckets = spec.options().getOrDefault("buckets", "10");
        String bucketColumn = keys.isEmpty()
                ? (spec.columns().isEmpty() ? null : spec.columns().get(0).name())
                : keys.get(0);
        if (bucketColumn != null) {
            sql.append("\nDISTRIBUTED BY HASH(").append(quote(type, bucketColumn))
                    .append(") BUCKETS ").append(buckets);
        }
        // 副本数默认 1 而不是 3:迁移的目标常常是单节点测试集群,默认 3 会直接
        // 建表失败。生产环境应当在选项里显式指定。
        sql.append("\nPROPERTIES (\"replication_num\" = \"")
                .append(spec.options().getOrDefault("replication_num", "1")).append("\")");
        warnings.add("副本数默认为 1;生产集群请在选项里指定 replication_num");
        return new TableDdl.GeneratedDdl(List.of(sql.toString()), warnings);
    }

    private static TableDdl.GeneratedDdl postgres(String table, List<String> columns,
                                                  TableDdl.CreateTableSpec spec,
                                                  List<String> warnings) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns));
        if (!spec.primaryKeys().isEmpty()) {
            sql.append(",\n  PRIMARY KEY (")
                    .append(joinQuoted(DataSourceType.POSTGRESQL, spec.primaryKeys())).append(')');
        }
        sql.append("\n)");

        // PostgreSQL 的注释是独立语句,不能内联 —— 所以这里会返回多条
        List<String> statements = new ArrayList<>();
        statements.add(sql.toString());
        if (isPresent(spec.comment())) {
            statements.add("COMMENT ON TABLE %s IS %s".formatted(table, literal(spec.comment())));
        }
        for (TableDdl.ColumnSpec column : spec.columns()) {
            if (isPresent(column.comment())) {
                statements.add("COMMENT ON COLUMN %s.%s IS %s".formatted(
                        table, quote(DataSourceType.POSTGRESQL, column.name()),
                        literal(column.comment())));
            }
        }
        return new TableDdl.GeneratedDdl(statements, warnings);
    }

    private static TableDdl.GeneratedDdl oracle(String table, List<String> columns,
                                                TableDdl.CreateTableSpec spec,
                                                List<String> warnings) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns));
        if (!spec.primaryKeys().isEmpty()) {
            sql.append(",\n  PRIMARY KEY (")
                    .append(joinQuoted(DataSourceType.ORACLE, spec.primaryKeys())).append(')');
        }
        sql.append("\n)");

        List<String> statements = new ArrayList<>();
        statements.add(sql.toString());
        if (isPresent(spec.comment())) {
            statements.add("COMMENT ON TABLE %s IS %s".formatted(table, literal(spec.comment())));
        }
        return new TableDdl.GeneratedDdl(statements, warnings);
    }

    private static TableDdl.GeneratedDdl sqlServer(String table, List<String> columns,
                                                   TableDdl.CreateTableSpec spec,
                                                   List<String> warnings) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns));
        if (!spec.primaryKeys().isEmpty()) {
            sql.append(",\n  PRIMARY KEY (")
                    .append(joinQuoted(DataSourceType.SQLSERVER, spec.primaryKeys())).append(')');
        }
        sql.append("\n)");
        if (isPresent(spec.comment())) {
            warnings.add("SQL Server 的表注释需要 sp_addextendedproperty,已略过");
        }
        return new TableDdl.GeneratedDdl(List.of(sql.toString()), warnings);
    }

    private static TableDdl.GeneratedDdl dameng(String table, List<String> columns,
                                                TableDdl.CreateTableSpec spec,
                                                List<String> warnings) {
        // 达梦语法与 Oracle 高度兼容,注释也是独立语句
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n  ")
                .append(String.join(",\n  ", columns));
        if (!spec.primaryKeys().isEmpty()) {
            sql.append(",\n  PRIMARY KEY (")
                    .append(joinQuoted(DataSourceType.DAMENG, spec.primaryKeys())).append(')');
        }
        sql.append("\n)");

        List<String> statements = new ArrayList<>();
        statements.add(sql.toString());
        if (isPresent(spec.comment())) {
            statements.add("COMMENT ON TABLE %s IS %s".formatted(table, literal(spec.comment())));
        }
        return new TableDdl.GeneratedDdl(statements, warnings);
    }

    // ── 列渲染 ──────────────────────────────────────────────────────────

    private static String renderColumn(DataSourceType type, TableDdl.ColumnSpec column,
                                       TableDdl.CreateTableSpec spec, List<String> warnings) {
        String sqlType = renderType(type, column, warnings);
        StringBuilder clause = new StringBuilder(quote(type, column.name()))
                .append(' ').append(sqlType);

        // 主键列强制 NOT NULL:多数方言会自己加,但 Doris 的 UNIQUE KEY 不会,
        // 而一个可空的主键列会让后续的 UPSERT 行为变得不可预测
        boolean isPrimaryKey = spec.primaryKeys().contains(column.name());
        if (!column.nullable() || isPrimaryKey) {
            clause.append(" NOT NULL");
        }

        // MySQL / Doris 的列注释可以内联,其余方言要单独 COMMENT ON
        if (isPresent(column.comment()) && supportsInlineColumnComment(type)) {
            clause.append(" COMMENT ").append(literal(column.comment()));
        }
        return clause.toString();
    }

    /**
     * 规范类型 → 方言类型。
     *
     * <p>降级的地方一律记 warning:整库迁移最常见的事故是某个字段悄悄变窄了,
     * 而这类降级只会在这里出现一次 —— 错过了就再没有第二次提醒。
     */
    private static String renderType(DataSourceType type, TableDdl.ColumnSpec column,
                                     List<String> warnings) {
        CanonicalType canonical = column.canonicalType() == null
                ? CanonicalType.VARCHAR : column.canonicalType();
        Integer precision = column.precision();
        Integer scale = column.scale();

        return switch (canonical) {
            case BOOLEAN -> switch (type) {
                // Oracle 12c 以下没有 BOOLEAN;达梦有 BIT
                case ORACLE -> {
                    warnings.add("列 %s:Oracle 无 BOOLEAN,使用 NUMBER(1)".formatted(column.name()));
                    yield "NUMBER(1)";
                }
                case SQLSERVER -> "BIT";
                case DAMENG -> "BIT";
                default -> "BOOLEAN";
            };
            case TINYINT -> type == DataSourceType.ORACLE ? "NUMBER(3)" : "TINYINT";
            case SMALLINT -> type == DataSourceType.ORACLE ? "NUMBER(5)" : "SMALLINT";
            case INT -> switch (type) {
                case ORACLE -> "NUMBER(10)";
                case POSTGRESQL -> "INTEGER";
                default -> "INT";
            };
            case BIGINT -> type == DataSourceType.ORACLE ? "NUMBER(19)" : "BIGINT";
            case FLOAT -> switch (type) {
                case POSTGRESQL -> "REAL";
                case ORACLE -> "BINARY_FLOAT";
                default -> "FLOAT";
            };
            case DOUBLE -> switch (type) {
                case POSTGRESQL -> "DOUBLE PRECISION";
                case ORACLE -> "BINARY_DOUBLE";
                case SQLSERVER -> "FLOAT(53)";
                default -> "DOUBLE";
            };
            case DECIMAL -> {
                int p = precision == null || precision <= 0 ? DEFAULT_DECIMAL_PRECISION : precision;
                int s = scale == null || scale < 0 ? DEFAULT_DECIMAL_SCALE : scale;
                if (precision == null) {
                    warnings.add("列 %s:源端未给出精度,使用 DECIMAL(%d,%d)"
                            .formatted(column.name(), p, s));
                }
                yield (type == DataSourceType.ORACLE ? "NUMBER(%d,%d)" : "DECIMAL(%d,%d)")
                        .formatted(p, s);
            }
            case CHAR -> {
                int len = precision == null || precision <= 0 ? 1 : precision;
                yield type == DataSourceType.ORACLE ? "CHAR(%d CHAR)".formatted(len)
                        : "CHAR(%d)".formatted(len);
            }
            case VARCHAR -> {
                int len = precision == null || precision <= 0 ? DEFAULT_VARCHAR_LENGTH : precision;
                yield switch (type) {
                    // Oracle 的 VARCHAR2 上限 4000 字节;超了要用 CLOB
                    case ORACLE -> len > 4000
                            ? warnAnd(warnings, "列 %s:长度 %d 超过 Oracle VARCHAR2 上限,改用 CLOB"
                            .formatted(column.name(), len), "CLOB")
                            : "VARCHAR2(%d CHAR)".formatted(len);
                    case SQLSERVER -> len > 4000 ? "NVARCHAR(MAX)" : "NVARCHAR(%d)".formatted(len);
                    // Doris 的 VARCHAR 按字节算长度,中文一字三字节 —— 不放大三倍
                    // 会让「姓名 VARCHAR(10)」在存四个汉字时就截断
                    case DORIS, STARROCKS -> "VARCHAR(%d)".formatted(Math.min(len * 3, 65533));
                    default -> "VARCHAR(%d)".formatted(len);
                };
            }
            case TEXT -> switch (type) {
                case ORACLE, DAMENG -> "CLOB";
                case SQLSERVER -> "NVARCHAR(MAX)";
                // Doris 没有 TEXT,STRING 是它的等价物(上限 1MB)
                case DORIS, STARROCKS -> "STRING";
                default -> "TEXT";
            };
            case DATE -> "DATE";
            case TIME -> switch (type) {
                case ORACLE -> warnAnd(warnings,
                        "列 %s:Oracle 无独立 TIME 类型,使用 TIMESTAMP".formatted(column.name()),
                        "TIMESTAMP");
                case DORIS, STARROCKS -> warnAnd(warnings,
                        "列 %s:Doris 无 TIME 类型,使用 VARCHAR(16)".formatted(column.name()),
                        "VARCHAR(16)");
                default -> "TIME";
            };
            case TIMESTAMP -> switch (type) {
                case MYSQL -> "DATETIME";
                case SQLSERVER -> "DATETIME2";
                case DORIS, STARROCKS -> "DATETIME";
                default -> "TIMESTAMP";
            };
            case TIMESTAMP_TZ -> switch (type) {
                case POSTGRESQL -> "TIMESTAMP WITH TIME ZONE";
                case ORACLE -> "TIMESTAMP WITH TIME ZONE";
                case SQLSERVER -> "DATETIMEOFFSET";
                // MySQL / Doris 没有带时区的时间戳。这是<b>会丢信息</b>的降级,
                // 必须提醒 —— 时区丢了之后数据看着还在,只是不知道是哪个时区的
                default -> warnAnd(warnings,
                        "列 %s:%s 无带时区的时间戳,降级为不带时区 —— 时区信息会丢失"
                                .formatted(column.name(), type.displayName()),
                        type == DataSourceType.MYSQL ? "DATETIME" : "DATETIME");
            };
            case BINARY, VARBINARY -> switch (type) {
                case POSTGRESQL -> "BYTEA";
                case ORACLE, DAMENG -> "BLOB";
                case SQLSERVER -> "VARBINARY(MAX)";
                case DORIS, STARROCKS -> warnAnd(warnings,
                        "列 %s:Doris 不支持二进制类型,使用 STRING".formatted(column.name()),
                        "STRING");
                default -> "VARBINARY(%d)".formatted(
                        precision == null || precision <= 0 ? 255 : precision);
            };
            case BLOB -> switch (type) {
                case POSTGRESQL -> "BYTEA";
                case SQLSERVER -> "VARBINARY(MAX)";
                case DORIS, STARROCKS -> warnAnd(warnings,
                        "列 %s:Doris 不支持 BLOB,使用 STRING".formatted(column.name()),
                        "STRING");
                default -> "BLOB";
            };
            case JSON -> switch (type) {
                case MYSQL -> "JSON";
                case POSTGRESQL -> "JSONB";
                case DORIS, STARROCKS -> "JSON";
                default -> warnAnd(warnings,
                        "列 %s:%s 无 JSON 类型,使用文本".formatted(column.name(), type.displayName()),
                        type == DataSourceType.ORACLE || type == DataSourceType.DAMENG
                                ? "CLOB" : "NVARCHAR(MAX)");
            };
            case ARRAY -> switch (type) {
                case POSTGRESQL -> warnAnd(warnings,
                        "列 %s:数组元素类型未知,使用 TEXT[]".formatted(column.name()), "TEXT[]");
                case DORIS, STARROCKS -> warnAnd(warnings,
                        "列 %s:数组元素类型未知,使用 ARRAY<STRING>".formatted(column.name()),
                        "ARRAY<STRING>");
                default -> warnAnd(warnings,
                        "列 %s:%s 无数组类型,序列化为文本".formatted(column.name(), type.displayName()),
                        "TEXT");
            };
            case UNKNOWN -> warnAnd(warnings,
                    "列 %s:源端类型无法识别,退化为 VARCHAR(%d)——请人工确认"
                            .formatted(column.name(), DEFAULT_VARCHAR_LENGTH),
                    "VARCHAR(%d)".formatted(DEFAULT_VARCHAR_LENGTH));
        };
    }

    private static String warnAnd(List<String> warnings, String warning, String sqlType) {
        warnings.add(warning);
        return sqlType;
    }

    // ── 标识符与字面量 ──────────────────────────────────────────────────

    private static String qualifiedName(DataSourceType type, TableDdl.CreateTableSpec spec) {
        List<String> parts = new ArrayList<>(2);
        if (isPresent(spec.targetSchema())) {
            parts.add(quote(type, spec.targetSchema()));
        } else if (isPresent(spec.targetDatabase())) {
            parts.add(quote(type, spec.targetDatabase()));
        }
        parts.add(quote(type, spec.targetTable()));
        return String.join(".", parts);
    }

    /** 标识符引用符按方言取。用错会让带保留字的表名(如 order)整批失败。 */
    public static String quote(DataSourceType type, String identifier) {
        return switch (type) {
            case MYSQL, DORIS, STARROCKS -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    private static String joinQuoted(DataSourceType type, List<String> identifiers) {
        return identifiers.stream().map(i -> quote(type, i))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** 单引号字面量。转义只处理单引号 —— 注释文本不该出现别的需要转义的东西。 */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void appendComment(StringBuilder sql, String comment, String keyword) {
        if (isPresent(comment)) {
            sql.append(keyword).append(literal(comment));
        }
    }

    private static boolean supportsInlineColumnComment(DataSourceType type) {
        return type == DataSourceType.MYSQL || type == DataSourceType.DORIS
                || type == DataSourceType.STARROCKS;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** 方言选项的说明,供 UI 展示可填哪些 key。 */
    public static Map<String, String> supportedOptions(DataSourceType type) {
        return switch (type) {
            case MYSQL -> Map.of("charset", "字符集,默认 utf8mb4");
            case DORIS, STARROCKS -> Map.of(
                    "buckets", "分桶数,默认 10",
                    "replication_num", "副本数,默认 1(生产集群应设为 3)");
            default -> Map.of();
        };
    }
}
