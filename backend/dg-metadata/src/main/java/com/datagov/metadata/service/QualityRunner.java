package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置质量规则执行器（P2）。
 *
 * <p>只生成平台可控的聚合 SQL：表/字段名全部来自 Catalog 并做方言 quoting，
 * 用户只能填阈值，不能填 SQL。执行走 {@link DataAccessGateway}，
 * 与自定义查询共享只读护栏、行数上限与超时。
 *
 * <p>文件/HTTP/外部 Catalog 数据集没有可执行 SQL 的数据源，直接失败并在
 * errorMessage 说明原因 —— 不把“无法评估”伪装成“通过”。
 */
@Component
public class QualityRunner {

    private static final int TIMEOUT_SECONDS = 30;

    private final CatalogSourceMapper sourceMapper;
    private final DataSourceService dataSourceService;
    private final ConnectionConfigAssembler assembler;
    private final DataAccessGateway gateway;
    private final ObjectMapper objectMapper;

    public QualityRunner(CatalogSourceMapper sourceMapper,
                         DataSourceService dataSourceService,
                         ConnectionConfigAssembler assembler,
                         DataAccessGateway gateway,
                         ObjectMapper objectMapper) {
        this.sourceMapper = sourceMapper;
        this.dataSourceService = dataSourceService;
        this.assembler = assembler;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public record QualityResult(boolean passed, Map<String, Object> metric,
                                Long violations, String errorMessage) {
    }

    public QualityResult evaluate(QualityRule rule, CatalogDataset dataset,
                                  CatalogColumn column) {
        Map<String, Object> config = readConfig(rule.getConfigJson());
        DataSourceType type = resolveType(dataset);
        return switch (rule.getRuleType()) {
            case "ROW_COUNT_RANGE" -> rowCountRange(dataset, type, config);
            case "NOT_NULL" -> notNull(dataset, type, column, config);
            case "UNIQUE" -> unique(dataset, type, column, config);
            case "FRESHNESS" -> freshness(dataset, type, column, config);
            case "SCHEMA_DRIFT" -> schemaDrift(dataset, config);
            default -> throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "不支持的质量规则类型: " + rule.getRuleType());
        };
    }

    // ── 规则实现 ────────────────────────────────────────────────────────

    private QualityResult rowCountRange(CatalogDataset dataset, DataSourceType type,
                                        Map<String, Object> config) {
        Long min = longValue(config.get("min"));
        Long max = longValue(config.get("max"));
        long count = firstLong(runSql(dataset, "SELECT COUNT(*) AS cnt FROM "
                + tableRef(dataset, type)));
        Map<String, Object> metric = metric("rowCount", count, "min", min, "max", max);
        boolean passed = (min == null || count >= min) && (max == null || count <= max);
        return new QualityResult(passed, metric, passed ? 0L : 1L, null);
    }

    private QualityResult notNull(CatalogDataset dataset, DataSourceType type,
                                  CatalogColumn column, Map<String, Object> config) {
        Double maxRatio = doubleValue(config.get("maxNullRatio"));
        String cleaned = columnRef(type, column, "NOT_NULL");
        SqlQuery.Result result = runSql(dataset,
                "SELECT COUNT(*) AS total, COUNT(" + cleaned + ") AS present FROM "
                        + tableRef(dataset, type));
        List<String> row = firstRow(result);
        long total = parseLong(row.get(0));
        long present = parseLong(row.get(1));
        long violations = Math.max(0, total - present);
        double ratio = total == 0 ? 0d : (double) violations / total;
        boolean passed = maxRatio == null || ratio <= maxRatio;
        Map<String, Object> metric = metric("totalRows", total, "nullRows", violations,
                "nullRatio", round(ratio), "maxNullRatio", maxRatio);
        return new QualityResult(passed, metric, violations, null);
    }

    private QualityResult unique(CatalogDataset dataset, DataSourceType type,
                                 CatalogColumn column, Map<String, Object> config) {
        Double maxRatio = doubleValue(config.get("maxDuplicateRatio"));
        String cleaned = columnRef(type, column, "UNIQUE");
        SqlQuery.Result result = runSql(dataset,
                "SELECT COUNT(*) AS total, COUNT(DISTINCT " + cleaned
                        + ") AS distinct_count FROM " + tableRef(dataset, type));
        List<String> row = firstRow(result);
        long total = parseLong(row.get(0));
        long distinct = parseLong(row.get(1));
        long violations = Math.max(0, total - distinct);
        double ratio = total == 0 ? 0d : (double) violations / total;
        boolean passed = maxRatio == null || ratio <= maxRatio;
        Map<String, Object> metric = metric("totalRows", total, "distinctRows", distinct,
                "duplicateRows", violations, "duplicateRatio", round(ratio),
                "maxDuplicateRatio", maxRatio);
        return new QualityResult(passed, metric, violations, null);
    }

    private QualityResult freshness(CatalogDataset dataset, DataSourceType type,
                                    CatalogColumn column, Map<String, Object> config) {
        Double maxAgeHours = doubleValue(config.get("maxAgeHours"));
        String cleaned = columnRef(type, column, "FRESHNESS");
        SqlQuery.Result result = runSql(dataset,
                "SELECT MAX(" + cleaned + ") AS latest FROM " + tableRef(dataset, type));
        String latestRaw = firstRow(result).get(0);
        Instant latest = parseInstant(latestRaw);
        if (latest == null) {
            return new QualityResult(false, metric("latest", null, "maxAgeHours", maxAgeHours),
                    1L, "字段没有可解析的时间值，无法评估新鲜度");
        }
        double ageHours = Duration.between(latest, Instant.now()).toMinutes() / 60.0;
        boolean passed = maxAgeHours == null || ageHours <= maxAgeHours;
        Map<String, Object> metric = metric("latest", latest.toString(),
                "ageHours", round(ageHours), "maxAgeHours", maxAgeHours);
        return new QualityResult(passed, metric, passed ? 0L : 1L, null);
    }

    private QualityResult schemaDrift(CatalogDataset dataset, Map<String, Object> config) {
        Object baseline = config.get("baselineSchemaHash");
        String expected = baseline == null ? null : baseline.toString();
        String current = dataset.getSchemaHash();
        boolean passed = expected == null || expected.isBlank()
                || expected.equalsIgnoreCase(current);
        Map<String, Object> metric = metric("baselineSchemaHash", expected,
                "currentSchemaHash", current);
        return new QualityResult(passed, metric, passed ? 0L : 1L, null);
    }

    // ── SQL 执行与标识符 ────────────────────────────────────────────────

    private SqlQuery.Result runSql(CatalogDataset dataset, String sql) {
        CatalogSource source = sourceMapper.selectById(dataset.getSourceId());
        if (source == null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "数据集所属目录来源已不存在，无法执行质量规则");
        }
        if (!"JDBC".equals(source.getSourceType())) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                    "该数据集来源(" + source.getSourceType()
                            + ")不支持质量 SQL；P2 只评估 JDBC 数据集");
        }
        if (source.getDataSourceId() == null || source.getDataSourceId().isBlank()) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                    "目录来源没有绑定数据源，无法执行质量规则");
        }
        DataSourceEntity dataSource = dataSourceService.requireInWorkspace(source.getDataSourceId());
        if (dataSource.getStatus() != DataSourceStatus.AVAILABLE) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "数据源当前状态为 %s，需先通过连通性测试才能运行质量规则"
                            .formatted(dataSource.getStatus()));
        }
        ConnectionConfig config = assembler.assemble(dataSource, dataset.getWorkspaceId());
        return gateway.executeQuery(dataSource.getType(), config,
                new SqlQuery.Request(sql, 1, TIMEOUT_SECONDS));
    }

    /**
     * 只取限定名最后两段：MySQL 是 db.table，PG/Oracle 是 schema.table。
     * 三段的 db.schema.table 直接引用在 PostgreSQL 上会报跨库错误，
     * 而连接本身已经指定了 database。
     */
    private static String tableRef(CatalogDataset dataset, DataSourceType type) {
        String qualified = dataset.getQualifiedName();
        if (qualified == null || qualified.isBlank()) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "数据集缺少限定名");
        }
        String[] parts = qualified.split("\\.");
        int start = Math.max(0, parts.length - 2);
        StringBuilder ref = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (ref.length() > 0) {
                ref.append('.');
            }
            ref.append(quote(type, parts[i]));
        }
        return ref.toString();
    }

    private DataSourceType resolveType(CatalogDataset dataset) {
        CatalogSource source = sourceMapper.selectById(dataset.getSourceId());
        if (source == null || source.getDataSourceId() == null) {
            return DataSourceType.POSTGRESQL;
        }
        return dataSourceService.requireInWorkspace(source.getDataSourceId()).getType();
    }

    private static String columnRef(DataSourceType type, CatalogColumn column, String ruleType) {
        if (column == null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    ruleType + " 必须指定字段");
        }
        return quote(type, column.getName());
    }

    private static String quote(DataSourceType type, String identifier) {
        return switch (type) {
            case MYSQL, DORIS, STARROCKS -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    // ── 配置与结果解析 ──────────────────────────────────────────────────

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "质量规则配置不是合法 JSON", e.getMessage(), e);
        }
    }

    private static Map<String, Object> metric(Object... values) {
        Map<String, Object> metric = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            metric.put(String.valueOf(values[i]), values[i + 1]);
        }
        return metric;
    }

    private static List<String> firstRow(SqlQuery.Result result) {
        if (result.rows().isEmpty()) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "质量查询没有返回结果");
        }
        return result.rows().get(0);
    }

    private static long firstLong(SqlQuery.Result result) {
        List<String> row = firstRow(result);
        if (row.isEmpty()) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "质量查询没有返回列");
        }
        return parseLong(row.get(0));
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return new BigDecimal(value.trim()).longValue();
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    private static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : Double.parseDouble(text);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(value);
            return value.length() == 13 ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            // 继续尝试常见数据库时间格式
        }
        // PostgreSQL JDBC 常见: 2026-10-30 11:52:31.052912+00
        try {
            return java.time.OffsetDateTime.parse(value.replace(' ', 'T'),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            // 继续
        }
        // 去掉小数秒与时区，只保留秒级本地时间。新鲜度按小时判断，秒以下无意义。
        if (value.length() >= 19 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            try {
                return LocalDateTime.parse(value.substring(0, 19),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored) {
                // 继续
            }
        }
        List<DateTimeFormatter> dateTimeFormats = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]"));
        for (DateTimeFormatter format : dateTimeFormats) {
            try {
                return LocalDateTime.parse(value, format).atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored) {
                // 下一个格式
            }
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
}
