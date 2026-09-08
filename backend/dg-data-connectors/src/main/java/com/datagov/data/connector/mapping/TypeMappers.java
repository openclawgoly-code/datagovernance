package com.datagov.data.connector.mapping;

import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;

import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * 各引擎的类型映射表。
 *
 * <p>结构是"表驱动 + 少量例外",而不是几十个 if-else:
 * <ol>
 *   <li>先按引擎自己的类型名查表 —— 名字最准确,{@code timestamptz} 与
 *       {@code timestamp} 只差三个字母,语义却差一个时区</li>
 *   <li>查不到再退回 {@link java.sql.Types} 的通用映射</li>
 *   <li>几个按名字也判不准的情况(Oracle 的 NUMBER)交给专门的例外逻辑</li>
 * </ol>
 */
public final class TypeMappers {

    private TypeMappers() {
    }

    public static TypeMapper forType(DataSourceType type) {
        return switch (type) {
            case MYSQL -> mySql(false);
            case DORIS, STARROCKS -> doris();
            case POSTGRESQL -> postgreSql();
            case ORACLE -> oracle();
            case SQLSERVER -> sqlServer();
            case DAMENG -> dameng();
            // 非 JDBC 类型没有列的概念,给一个永远返回 UNKNOWN 的映射器,
            // 免得调用方还要判空。
            case FTP, SFTP, REST_API -> (raw, jdbc, p, s) -> CanonicalType.UNKNOWN;
        };
    }

    // ── MySQL ───────────────────────────────────────────────────────────

    private static final Map<String, CanonicalType> MYSQL_TYPES = Map.ofEntries(
            Map.entry("bit", CanonicalType.BOOLEAN),
            Map.entry("bool", CanonicalType.BOOLEAN),
            Map.entry("boolean", CanonicalType.BOOLEAN),
            Map.entry("tinyint", CanonicalType.TINYINT),
            Map.entry("smallint", CanonicalType.SMALLINT),
            Map.entry("mediumint", CanonicalType.INT),
            Map.entry("int", CanonicalType.INT),
            Map.entry("integer", CanonicalType.INT),
            Map.entry("bigint", CanonicalType.BIGINT),
            Map.entry("float", CanonicalType.FLOAT),
            Map.entry("double", CanonicalType.DOUBLE),
            Map.entry("decimal", CanonicalType.DECIMAL),
            Map.entry("numeric", CanonicalType.DECIMAL),
            Map.entry("char", CanonicalType.CHAR),
            Map.entry("varchar", CanonicalType.VARCHAR),
            Map.entry("tinytext", CanonicalType.TEXT),
            Map.entry("text", CanonicalType.TEXT),
            Map.entry("mediumtext", CanonicalType.TEXT),
            Map.entry("longtext", CanonicalType.TEXT),
            Map.entry("date", CanonicalType.DATE),
            Map.entry("time", CanonicalType.TIME),
            Map.entry("year", CanonicalType.SMALLINT),
            Map.entry("datetime", CanonicalType.TIMESTAMP),
            // MySQL 的 TIMESTAMP 会按会话时区做转换,语义上等价于带时区
            Map.entry("timestamp", CanonicalType.TIMESTAMP_TZ),
            Map.entry("binary", CanonicalType.BINARY),
            Map.entry("varbinary", CanonicalType.VARBINARY),
            Map.entry("tinyblob", CanonicalType.BLOB),
            Map.entry("blob", CanonicalType.BLOB),
            Map.entry("mediumblob", CanonicalType.BLOB),
            Map.entry("longblob", CanonicalType.BLOB),
            Map.entry("json", CanonicalType.JSON),
            Map.entry("enum", CanonicalType.VARCHAR),
            Map.entry("set", CanonicalType.VARCHAR)
    );

    /**
     * @param tinyint1AsBoolean 是否把 {@code tinyint(1)} 当布尔。
     *        默认 false: MySQL 里 {@code tinyint(1)} 既可能是布尔,也可能是
     *        一个真的取值 0-127 的小整数。默认当布尔会在后者上丢数据,
     *        而默认当整数最多是"布尔被同步成 0/1",后者可逆、前者不可逆。
     */
    static TypeMapper mySql(boolean tinyint1AsBoolean) {
        return (raw, jdbcType, precision, scale) -> {
            String name = normalize(raw);
            if (tinyint1AsBoolean && "tinyint".equals(name)
                    && precision != null && precision == 1) {
                return CanonicalType.BOOLEAN;
            }
            CanonicalType byName = MYSQL_TYPES.get(name);
            return byName != null ? byName : byJdbcType(jdbcType);
        };
    }

    /** Doris / StarRocks: MySQL 线协议,类型集是 MySQL 的子集加几个自有类型。 */
    static TypeMapper doris() {
        TypeMapper base = mySql(false);
        return (raw, jdbcType, precision, scale) -> {
            String name = normalize(raw);
            return switch (name) {
                case "largeint" -> CanonicalType.DECIMAL;   // 128 位整数,没有对应的规范整数类型
                case "hll", "bitmap", "quantile_state" -> CanonicalType.BINARY;  // 聚合中间态
                case "array" -> CanonicalType.ARRAY;
                case "datev2" -> CanonicalType.DATE;
                case "datetimev2" -> CanonicalType.TIMESTAMP;
                case "string" -> CanonicalType.TEXT;
                default -> base.map(raw, jdbcType, precision, scale);
            };
        };
    }

    // ── PostgreSQL ──────────────────────────────────────────────────────

    private static final Map<String, CanonicalType> PG_TYPES = Map.ofEntries(
            Map.entry("bool", CanonicalType.BOOLEAN),
            Map.entry("boolean", CanonicalType.BOOLEAN),
            Map.entry("int2", CanonicalType.SMALLINT),
            Map.entry("smallint", CanonicalType.SMALLINT),
            Map.entry("int4", CanonicalType.INT),
            Map.entry("integer", CanonicalType.INT),
            Map.entry("int", CanonicalType.INT),
            Map.entry("serial", CanonicalType.INT),
            Map.entry("int8", CanonicalType.BIGINT),
            Map.entry("bigint", CanonicalType.BIGINT),
            Map.entry("bigserial", CanonicalType.BIGINT),
            Map.entry("float4", CanonicalType.FLOAT),
            Map.entry("real", CanonicalType.FLOAT),
            Map.entry("float8", CanonicalType.DOUBLE),
            Map.entry("double precision", CanonicalType.DOUBLE),
            Map.entry("numeric", CanonicalType.DECIMAL),
            Map.entry("decimal", CanonicalType.DECIMAL),
            Map.entry("money", CanonicalType.DECIMAL),
            Map.entry("bpchar", CanonicalType.CHAR),
            Map.entry("char", CanonicalType.CHAR),
            Map.entry("character", CanonicalType.CHAR),
            Map.entry("varchar", CanonicalType.VARCHAR),
            Map.entry("character varying", CanonicalType.VARCHAR),
            Map.entry("text", CanonicalType.TEXT),
            Map.entry("date", CanonicalType.DATE),
            Map.entry("time", CanonicalType.TIME),
            Map.entry("timetz", CanonicalType.TIME),
            Map.entry("timestamp", CanonicalType.TIMESTAMP),
            // 这一对是跨库同步最容易出事的地方,必须分开
            Map.entry("timestamptz", CanonicalType.TIMESTAMP_TZ),
            Map.entry("timestamp with time zone", CanonicalType.TIMESTAMP_TZ),
            Map.entry("timestamp without time zone", CanonicalType.TIMESTAMP),
            Map.entry("bytea", CanonicalType.BLOB),
            Map.entry("json", CanonicalType.JSON),
            Map.entry("jsonb", CanonicalType.JSON),
            Map.entry("uuid", CanonicalType.VARCHAR),
            Map.entry("bit", CanonicalType.BINARY),
            Map.entry("varbit", CanonicalType.VARBINARY)
    );

    static TypeMapper postgreSql() {
        return (raw, jdbcType, precision, scale) -> {
            String name = normalize(raw);
            // PostgreSQL 的数组类型以下划线开头(_int4 即 int4[])
            if (name.startsWith("_") || name.endsWith("[]")) {
                return CanonicalType.ARRAY;
            }
            CanonicalType byName = PG_TYPES.get(name);
            return byName != null ? byName : byJdbcType(jdbcType);
        };
    }

    // ── Oracle ──────────────────────────────────────────────────────────

    static TypeMapper oracle() {
        return (raw, jdbcType, precision, scale) -> {
            String name = normalize(raw);

            // Oracle 的 NUMBER 是最大的映射陷阱: 它同时承载整数与定点数。
            // 按 scale 与 precision 细分,而不是一律映射成 DECIMAL 或 INT。
            if (name.startsWith("number")) {
                return mapOracleNumber(precision, scale);
            }
            if (name.startsWith("timestamp")) {
                // "TIMESTAMP(6) WITH TIME ZONE" / "WITH LOCAL TIME ZONE"
                return name.contains("time zone")
                        ? CanonicalType.TIMESTAMP_TZ
                        : CanonicalType.TIMESTAMP;
            }
            if (name.startsWith("interval")) {
                return CanonicalType.UNKNOWN;   // 规范类型系统里没有区间类型,不硬塞
            }
            return switch (name) {
                case "varchar2", "nvarchar2", "varchar" -> CanonicalType.VARCHAR;
                case "char", "nchar" -> CanonicalType.CHAR;
                case "clob", "nclob", "long" -> CanonicalType.TEXT;
                // Oracle 的 DATE 含时分秒,映射成 DATE 会丢掉时间部分
                case "date" -> CanonicalType.TIMESTAMP;
                case "binary_float" -> CanonicalType.FLOAT;
                case "binary_double" -> CanonicalType.DOUBLE;
                case "float" -> CanonicalType.DOUBLE;
                case "raw", "long raw" -> CanonicalType.VARBINARY;
                case "blob", "bfile" -> CanonicalType.BLOB;
                case "rowid", "urowid" -> CanonicalType.VARCHAR;
                default -> byJdbcType(jdbcType);
            };
        };
    }

    /**
     * Oracle NUMBER 的细分。
     *
     * <p>{@code NUMBER} 不带参数时精度可达 38 位,任何整数类型都装不下,
     * 只能是 DECIMAL。带 scale>0 一定是定点数。scale=0 时按 precision
     * 选最小够用的整数类型 —— 选小了会溢出,选大了浪费目标端存储。
     */
    private static CanonicalType mapOracleNumber(Integer precision, Integer scale) {
        if (scale == null || scale > 0) {
            return CanonicalType.DECIMAL;
        }
        if (precision == null || precision <= 0 || precision > 18) {
            // 无精度信息或超过 BIGINT 能表示的范围,退回 DECIMAL 保精度
            return CanonicalType.DECIMAL;
        }
        if (precision <= 2) return CanonicalType.TINYINT;
        if (precision <= 4) return CanonicalType.SMALLINT;
        if (precision <= 9) return CanonicalType.INT;
        return CanonicalType.BIGINT;
    }

    // ── SQL Server ──────────────────────────────────────────────────────

    private static final Map<String, CanonicalType> SQLSERVER_TYPES = Map.ofEntries(
            Map.entry("bit", CanonicalType.BOOLEAN),
            Map.entry("tinyint", CanonicalType.TINYINT),
            Map.entry("smallint", CanonicalType.SMALLINT),
            Map.entry("int", CanonicalType.INT),
            Map.entry("bigint", CanonicalType.BIGINT),
            Map.entry("real", CanonicalType.FLOAT),
            Map.entry("float", CanonicalType.DOUBLE),
            Map.entry("decimal", CanonicalType.DECIMAL),
            Map.entry("numeric", CanonicalType.DECIMAL),
            Map.entry("money", CanonicalType.DECIMAL),
            Map.entry("smallmoney", CanonicalType.DECIMAL),
            Map.entry("char", CanonicalType.CHAR),
            Map.entry("nchar", CanonicalType.CHAR),
            Map.entry("varchar", CanonicalType.VARCHAR),
            Map.entry("nvarchar", CanonicalType.VARCHAR),
            Map.entry("text", CanonicalType.TEXT),
            Map.entry("ntext", CanonicalType.TEXT),
            Map.entry("xml", CanonicalType.TEXT),
            Map.entry("date", CanonicalType.DATE),
            Map.entry("time", CanonicalType.TIME),
            Map.entry("datetime", CanonicalType.TIMESTAMP),
            Map.entry("datetime2", CanonicalType.TIMESTAMP),
            Map.entry("smalldatetime", CanonicalType.TIMESTAMP),
            Map.entry("datetimeoffset", CanonicalType.TIMESTAMP_TZ),
            Map.entry("binary", CanonicalType.BINARY),
            Map.entry("varbinary", CanonicalType.VARBINARY),
            Map.entry("image", CanonicalType.BLOB),
            Map.entry("uniqueidentifier", CanonicalType.VARCHAR),
            // rowversion 是行版本戳,跨库同步时不应原样搬运,标 UNKNOWN 逼人工决策
            Map.entry("timestamp", CanonicalType.UNKNOWN),
            Map.entry("rowversion", CanonicalType.UNKNOWN)
    );

    static TypeMapper sqlServer() {
        return (raw, jdbcType, precision, scale) -> {
            CanonicalType byName = SQLSERVER_TYPES.get(normalize(raw));
            return byName != null ? byName : byJdbcType(jdbcType);
        };
    }

    // ── 达梦 DM8 ────────────────────────────────────────────────────────

    /**
     * 达梦兼容 Oracle 语法,类型系统也高度相似(NUMBER、VARCHAR2、CLOB),
     * 因此以 Oracle 映射为底,再覆盖它自有的几个类型。
     */
    static TypeMapper dameng() {
        TypeMapper base = oracle();
        return (raw, jdbcType, precision, scale) -> {
            String name = normalize(raw);
            return switch (name) {
                case "bit" -> CanonicalType.BOOLEAN;
                case "byte" -> CanonicalType.TINYINT;
                case "tinyint" -> CanonicalType.TINYINT;
                case "smallint" -> CanonicalType.SMALLINT;
                case "int", "integer", "pls_integer" -> CanonicalType.INT;
                case "bigint" -> CanonicalType.BIGINT;
                case "dec" -> CanonicalType.DECIMAL;
                case "double precision" -> CanonicalType.DOUBLE;
                case "text", "longvarchar" -> CanonicalType.TEXT;
                case "image", "longvarbinary" -> CanonicalType.BLOB;
                case "datetime" -> CanonicalType.TIMESTAMP;
                default -> base.map(raw, jdbcType, precision, scale);
            };
        };
    }

    // ── 通用兜底 ────────────────────────────────────────────────────────

    /**
     * 按 {@link java.sql.Types} 的通用映射。
     *
     * <p>只在按类型名查不到时使用。它足够安全但不够精确 —— 比如
     * {@code TIMESTAMP} 与 {@code TIMESTAMP_WITH_TIMEZONE} 在这里能区分,
     * 但驱动是否如实上报就不一定了,所以优先按名字判。
     */
    static CanonicalType byJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> CanonicalType.BOOLEAN;
            case Types.TINYINT -> CanonicalType.TINYINT;
            case Types.SMALLINT -> CanonicalType.SMALLINT;
            case Types.INTEGER -> CanonicalType.INT;
            case Types.BIGINT -> CanonicalType.BIGINT;
            case Types.REAL -> CanonicalType.FLOAT;
            case Types.FLOAT, Types.DOUBLE -> CanonicalType.DOUBLE;
            case Types.NUMERIC, Types.DECIMAL -> CanonicalType.DECIMAL;
            case Types.CHAR, Types.NCHAR -> CanonicalType.CHAR;
            case Types.VARCHAR, Types.NVARCHAR -> CanonicalType.VARCHAR;
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> CanonicalType.TEXT;
            case Types.DATE -> CanonicalType.DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> CanonicalType.TIME;
            case Types.TIMESTAMP -> CanonicalType.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> CanonicalType.TIMESTAMP_TZ;
            case Types.BINARY -> CanonicalType.BINARY;
            case Types.VARBINARY -> CanonicalType.VARBINARY;
            case Types.LONGVARBINARY, Types.BLOB -> CanonicalType.BLOB;
            case Types.ARRAY -> CanonicalType.ARRAY;
            // STRUCT / REF / DATALINK / SQLXML 等一律不猜
            default -> CanonicalType.UNKNOWN;
        };
    }

    /** 去掉长度修饰与多余空白: {@code "NUMBER(10,2)"} → {@code "number(10,2)"} 的基名 {@code "number"} 由调用方按前缀判。 */
    private static String normalize(String rawTypeName) {
        if (rawTypeName == null) {
            return "";
        }
        String name = rawTypeName.trim().toLowerCase(Locale.ROOT);
        // 去掉 "varchar2(50)" 里的括号部分,但保留 "timestamp(6) with time zone" 的尾巴
        int open = name.indexOf('(');
        if (open >= 0) {
            int close = name.indexOf(')', open);
            name = close >= 0
                    ? (name.substring(0, open) + name.substring(close + 1)).trim()
                    : name.substring(0, open).trim();
        }
        // MySQL 会给出 "int unsigned" / "bigint unsigned"
        if (name.endsWith(" unsigned") || name.endsWith(" zerofill")) {
            name = name.replace(" unsigned", "").replace(" zerofill", "").trim();
        }
        return name;
    }
}
