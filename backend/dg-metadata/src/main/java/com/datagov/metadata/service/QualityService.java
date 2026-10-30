package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import com.datagov.metadata.entity.QualityEntities.QualityRun;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.QualityRuleMapper;
import com.datagov.metadata.mapper.QualityRunMapper;
import com.datagov.metadata.service.QualityRunner.QualityResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资产质量（P2）：规则 CRUD、手动运行、运行历史与加权分数。
 *
 * <p>分数只覆盖“已配置且至少运行过一次”的规则，不能表述为全量数据质量。
 * 规则是配置，运行是不可变事实；单条规则失败不会影响同数据集的其他规则。
 */
@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    private static final Set<String> RULE_TYPES = Set.of(
            "ROW_COUNT_RANGE", "NOT_NULL", "UNIQUE", "FRESHNESS", "SCHEMA_DRIFT");
    private static final Set<String> COLUMN_RULES = Set.of("NOT_NULL", "UNIQUE", "FRESHNESS");
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Map<String, Integer> WEIGHTS = Map.of(
            "CRITICAL", 5, "HIGH", 3, "MEDIUM", 2, "LOW", 1);

    private final QualityRuleMapper ruleMapper;
    private final QualityRunMapper runMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final QualityRunner runner;
    private final ObjectMapper objectMapper;

    public QualityService(QualityRuleMapper ruleMapper,
                          QualityRunMapper runMapper,
                          CatalogDatasetMapper datasetMapper,
                          CatalogColumnMapper columnMapper,
                          QualityRunner runner,
                          ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.runMapper = runMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    // ── DTO ─────────────────────────────────────────────────────────────

    public record RuleRequest(String datasetId, String columnId, String ruleType,
                              Map<String, Object> config, String severity,
                              Boolean enabled) {
    }

    public record RuleView(String id, String datasetId, String datasetName,
                           String datasetQualifiedName, String columnId, String columnName,
                           String ruleType, Map<String, Object> config, String severity,
                           boolean enabled, Instant createdAt, Instant updatedAt,
                           String updatedBy) {
    }

    public record RunView(String id, String ruleId, String ruleType, String datasetId,
                          String datasetName, String datasetQualifiedName, String columnId,
                          String columnName, String status, boolean passed,
                          Map<String, Object> metric, Long violations, String errorMessage,
                          Instant startedAt, Instant finishedAt, Long durationMs,
                          String executedBy) {
    }

    public record RuleSummary(String ruleId, String ruleType, String severity,
                              String columnId, String columnName, boolean enabled,
                              String latestStatus, Boolean latestPassed,
                              Map<String, Object> metric, Long violations,
                              Long durationMs, Instant executedAt, String errorMessage) {
    }

    public record QualitySummary(String datasetId, Integer score, int totalRules,
                                 int evaluatedRules, int passedRules, int failedRules,
                                 List<RuleSummary> rules) {
    }

    // ── 规则 CRUD ───────────────────────────────────────────────────────

    public PageResult<RuleView> listRules(long page, long size, String datasetId,
                                          String ruleType) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String type = ruleType == null || ruleType.isBlank()
                ? null : ruleType.trim().toUpperCase();
        if (type != null && !RULE_TYPES.contains(type)) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "未知的质量规则类型: " + ruleType);
        }
        LambdaQueryWrapper<QualityRule> query = new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getWorkspaceId, workspaceId)
                .eq(datasetId != null && !datasetId.isBlank(), QualityRule::getDatasetId, datasetId)
                .eq(type != null, QualityRule::getRuleType, type)
                .orderByDesc(QualityRule::getUpdatedAt);
        Page<QualityRule> result = ruleMapper.selectPage(Page.of(page, size), query);
        Map<String, CatalogDataset> datasets = datasets(workspaceId,
                result.getRecords().stream().map(QualityRule::getDatasetId).distinct().toList());
        Map<String, CatalogColumn> columns = columns(result.getRecords());
        return PageResult.of(result.getRecords().stream()
                        .map(rule -> toRuleView(rule, datasets, columns)).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public RuleView createRule(RuleRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(workspaceId, request == null ? null
                : request.datasetId());
        RuleContext context = validate(workspaceId, dataset, request);
        Instant now = Instant.now();
        QualityRule rule = new QualityRule();
        rule.setId(Ids.of("qrule"));
        rule.setWorkspaceId(workspaceId);
        rule.setDatasetId(dataset.getId());
        rule.setColumnId(context.columnId());
        rule.setRuleType(context.ruleType());
        rule.setConfigJson(writeConfig(context.config()));
        rule.setSeverity(context.severity());
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setCreatedAt(now);
        rule.setCreatedBy(WorkspaceContext.require().userId());
        rule.setUpdatedAt(now);
        rule.setUpdatedBy(WorkspaceContext.require().userId());
        ruleMapper.insert(rule);
        Map<String, CatalogColumn> columns = columns(List.of(rule));
        return toRuleView(rule, Map.of(dataset.getId(), dataset), columns);
    }

    @Transactional
    public RuleView updateRule(String id, RuleRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        QualityRule rule = requireRule(workspaceId, id);
        CatalogDataset dataset = requireDataset(workspaceId, request == null ? null
                : request.datasetId());
        RuleContext context = validate(workspaceId, dataset, request);
        rule.setDatasetId(dataset.getId());
        rule.setColumnId(context.columnId());
        rule.setRuleType(context.ruleType());
        rule.setConfigJson(writeConfig(context.config()));
        rule.setSeverity(context.severity());
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        rule.setUpdatedAt(Instant.now());
        rule.setUpdatedBy(WorkspaceContext.require().userId());
        ruleMapper.updateById(rule);
        return toRuleView(rule, Map.of(dataset.getId(), dataset), columns(List.of(rule)));
    }

    @Transactional
    public void deleteRule(String id) {
        QualityRule rule = requireRule(WorkspaceContext.requireWorkspaceId(), id);
        // 运行历史保留：删的是“以后还要不要跑”，不是“过去跑过什么”。
        ruleMapper.deleteById(rule.getId());
    }

    // ── 运行 ────────────────────────────────────────────────────────────

    public RunView runRule(String ruleId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        QualityRule rule = requireRule(workspaceId, ruleId);
        CatalogDataset dataset = requireDataset(workspaceId, rule.getDatasetId());
        CatalogColumn column = rule.getColumnId() == null
                ? null : columnMapper.selectById(rule.getColumnId());
        return execute(rule, dataset, column);
    }

    /**
     * 运行数据集下所有启用规则。
     *
     * <p>刻意不加 {@code @Transactional}：一条规则超时/失败不应回滚其他规则
     * 已经写入的运行事实。每条规则各自提交，失败只在自身运行记录里体现。
     */
    public List<RunView> runDataset(String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(workspaceId, datasetId);
        List<QualityRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getWorkspaceId, workspaceId)
                .eq(QualityRule::getDatasetId, datasetId)
                .eq(QualityRule::getEnabled, true)
                .orderByAsc(QualityRule::getCreatedAt));
        List<RunView> results = new ArrayList<>(rules.size());
        for (QualityRule rule : rules) {
            CatalogColumn column = rule.getColumnId() == null
                    ? null : columnMapper.selectById(rule.getColumnId());
            results.add(execute(rule, dataset, column));
        }
        return results;
    }

    private RunView execute(QualityRule rule, CatalogDataset dataset, CatalogColumn column) {
        Instant startedAt = Instant.now();
        QualityRun run = new QualityRun();
        run.setId(Ids.of("qrun"));
        run.setWorkspaceId(rule.getWorkspaceId());
        run.setRuleId(rule.getId());
        run.setDatasetId(rule.getDatasetId());
        run.setStatus("RUNNING");
        run.setPassed(false);
        run.setStartedAt(startedAt);
        run.setExecutedBy(WorkspaceContext.require().userId());
        runMapper.insert(run);
        try {
            QualityResult result = runner.evaluate(rule, dataset, column);
            run.setStatus("SUCCEEDED");
            run.setPassed(result.passed());
            run.setMetricJson(writeConfig(result.metric()));
            run.setViolations(result.violations());
            run.setErrorMessage(result.errorMessage());
        } catch (RuntimeException ex) {
            run.setStatus("FAILED");
            run.setPassed(false);
            run.setErrorMessage(truncate(ex.getMessage(), 2000));
            log.warn("质量规则运行失败 rule={} dataset={} type={}", rule.getId(),
                    rule.getDatasetId(), rule.getRuleType(), ex);
        }
        Instant finishedAt = Instant.now();
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()));
        runMapper.updateById(run);
        return toRunView(run, rule, dataset, column);
    }

    // ── 摘要与历史 ──────────────────────────────────────────────────────

    public QualitySummary summary(String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(workspaceId, datasetId);
        List<QualityRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getWorkspaceId, workspaceId)
                .eq(QualityRule::getDatasetId, datasetId)
                .orderByAsc(QualityRule::getCreatedAt));
        List<QualityRun> runs = runMapper.selectList(new LambdaQueryWrapper<QualityRun>()
                .eq(QualityRun::getWorkspaceId, workspaceId)
                .eq(QualityRun::getDatasetId, datasetId)
                .orderByDesc(QualityRun::getStartedAt));
        Map<String, QualityRun> latestByRule = new LinkedHashMap<>();
        for (QualityRun run : runs) {
            latestByRule.putIfAbsent(run.getRuleId(), run);
        }
        Map<String, CatalogColumn> columns = columns(rules);
        List<RuleSummary> summaries = new ArrayList<>(rules.size());
        double weightSum = 0;
        double passedWeight = 0;
        int evaluated = 0;
        int passed = 0;
        int failed = 0;
        for (QualityRule rule : rules) {
            QualityRun latest = latestByRule.get(rule.getId());
            CatalogColumn column = rule.getColumnId() == null
                    ? null : columns.get(rule.getColumnId());
            summaries.add(new RuleSummary(rule.getId(), rule.getRuleType(),
                    rule.getSeverity(), rule.getColumnId(),
                    column == null ? null : column.getName(),
                    Boolean.TRUE.equals(rule.getEnabled()),
                    latest == null ? "NOT_RUN" : latest.getStatus(),
                    latest == null ? null : Boolean.TRUE.equals(latest.getPassed()),
                    latest == null ? Map.of() : readConfig(latest.getMetricJson()),
                    latest == null ? null : latest.getViolations(),
                    latest == null ? null : latest.getDurationMs(),
                    latest == null ? null : latest.getStartedAt(),
                    latest == null ? null : latest.getErrorMessage()));
            if (latest == null) {
                continue;
            }
            evaluated++;
            weightSum += weight(rule.getSeverity());
            if (Boolean.TRUE.equals(latest.getPassed())) {
                passed++;
                passedWeight += weight(rule.getSeverity());
            } else {
                failed++;
            }
        }
        Integer score = evaluated == 0 ? null
                : (int) Math.round(100.0 * passedWeight / weightSum);
        return new QualitySummary(dataset.getId(), score, rules.size(), evaluated,
                passed, failed, summaries);
    }

    public PageResult<RunView> listRuns(long page, long size, String status, String ruleType,
                                        String datasetId) {
        return listRuns(page, size, status, ruleType, datasetId, null, false);
    }

    public PageResult<RunView> listRuns(long page, long size, String status, String ruleType,
                                        String datasetId, Boolean passed, Boolean latest) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String statusFilter = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (statusFilter != null && !Set.of("RUNNING", "SUCCEEDED", "FAILED").contains(statusFilter)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "未知的运行状态: " + status);
        }
        String typeFilter = ruleType == null || ruleType.isBlank()
                ? null : ruleType.trim().toUpperCase();
        List<String> ruleIds = null;
        if (typeFilter != null) {
            ruleIds = ruleMapper.selectList(new LambdaQueryWrapper<QualityRule>()
                            .eq(QualityRule::getWorkspaceId, workspaceId)
                            .eq(QualityRule::getRuleType, typeFilter)).stream()
                    .map(QualityRule::getId).toList();
            if (ruleIds.isEmpty()) {
                return PageResult.of(List.of(), 0, page, size);
            }
        }
        LambdaQueryWrapper<QualityRun> query = new LambdaQueryWrapper<QualityRun>()
                .eq(QualityRun::getWorkspaceId, workspaceId)
                .eq(statusFilter != null, QualityRun::getStatus, statusFilter)
                .eq(passed != null, QualityRun::getPassed, passed)
                .eq(datasetId != null && !datasetId.isBlank(), QualityRun::getDatasetId, datasetId)
                .in(ruleIds != null, QualityRun::getRuleId, ruleIds)
                // 总览的“失败资产”只取每条规则最近一次运行，否则修复后旧失败会一直挂着
                .apply(Boolean.TRUE.equals(latest),
                        "id = (SELECT q2.id FROM md_quality_run q2 "
                                + "WHERE q2.rule_id = md_quality_run.rule_id "
                                + "ORDER BY q2.started_at DESC, q2.id DESC LIMIT 1)")
                .orderByDesc(QualityRun::getStartedAt);
        Page<QualityRun> result = runMapper.selectPage(Page.of(page, size), query);
        Map<String, QualityRule> rules = rules(workspaceId,
                result.getRecords().stream().map(QualityRun::getRuleId).distinct().toList());
        Map<String, CatalogDataset> datasets = datasets(workspaceId,
                result.getRecords().stream().map(QualityRun::getDatasetId).distinct().toList());
        Map<String, CatalogColumn> columns = columns(new ArrayList<>(rules.values()));
        return PageResult.of(result.getRecords().stream().map(run -> {
            QualityRule rule = rules.get(run.getRuleId());
            CatalogDataset dataset = datasets.get(run.getDatasetId());
            CatalogColumn column = rule == null || rule.getColumnId() == null
                    ? null : columns.get(rule.getColumnId());
            return toRunView(run, rule, dataset, column);
        }).toList(), result.getTotal(), page, size);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private record RuleContext(String ruleType, String columnId, String severity,
                               Map<String, Object> config) {
    }

    private RuleContext validate(String workspaceId, CatalogDataset dataset, RuleRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "质量规则请求不能为空");
        }
        String type = request.ruleType() == null || request.ruleType().isBlank()
                ? null : request.ruleType().trim().toUpperCase();
        if (type == null || !RULE_TYPES.contains(type)) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "ruleType 只能是 " + RULE_TYPES);
        }
        String severity = request.severity() == null || request.severity().isBlank()
                ? "MEDIUM" : request.severity().trim().toUpperCase();
        if (!SEVERITIES.contains(severity)) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "severity 只能是 CRITICAL / HIGH / MEDIUM / LOW");
        }
        Map<String, Object> config = request.config() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.config());
        String columnId = request.columnId() == null || request.columnId().isBlank()
                ? null : request.columnId().trim();
        if (COLUMN_RULES.contains(type)) {
            if (columnId == null) {
                throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                        type + " 必须指定字段");
            }
            CatalogColumn column = columnMapper.selectById(columnId);
            if (column == null || !dataset.getId().equals(column.getDatasetId())) {
                throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                        "字段不属于该数据集");
            }
        } else if (columnId != null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    type + " 是数据集级规则，不能指定字段");
        }

        validateConfig(dataset, type, config);
        return new RuleContext(type, columnId, severity, config);
    }

    private void validateConfig(CatalogDataset dataset, String type, Map<String, Object> config) {
        switch (type) {
            case "ROW_COUNT_RANGE" -> {
                Double min = number(config.get("min"));
                Double max = number(config.get("max"));
                if (min == null && max == null) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "ROW_COUNT_RANGE 至少需要 min 或 max");
                }
                if ((min != null && min < 0) || (max != null && max < 0)) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "行数阈值不能为负数");
                }
                if (min != null && max != null && min > max) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "ROW_COUNT_RANGE 的 min 不能大于 max");
                }
            }
            case "NOT_NULL" -> requireRatio(config, "maxNullRatio", "NOT_NULL");
            case "UNIQUE" -> requireRatio(config, "maxDuplicateRatio", "UNIQUE");
            case "FRESHNESS" -> {
                Double hours = number(config.get("maxAgeHours"));
                if (hours == null || hours <= 0) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "FRESHNESS 需要大于 0 的 maxAgeHours");
                }
            }
            case "SCHEMA_DRIFT" -> {
                Object baseline = config.get("baselineSchemaHash");
                if (baseline == null || baseline.toString().isBlank()) {
                    // 未填基线时以创建时的 schema 作为基线，避免规则一创建就永远漂移。
                    if (dataset.getSchemaHash() == null) {
                        throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                                "数据集没有 schema hash，SCHEMA_DRIFT 需要显式 baselineSchemaHash");
                    }
                    config.put("baselineSchemaHash", dataset.getSchemaHash());
                }
            }
            default -> throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "未知的质量规则类型: " + type);
        }
    }

    private static void requireRatio(Map<String, Object> config, String key, String type) {
        Double value = number(config.get(key));
        if (value == null || value < 0 || value > 1) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    type + " 的 " + key + " 必须在 0–1 之间");
        }
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "配置值不是数字: " + value);
        }
    }

    private QualityRule requireRule(String workspaceId, String id) {
        QualityRule rule = ruleMapper.selectById(id);
        if (rule == null || !workspaceId.equals(rule.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_QUALITY_RULE_NOT_FOUND, id);
        }
        return rule;
    }

    private CatalogDataset requireDataset(String workspaceId, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "规则必须指定数据集");
        }
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }
        return dataset;
    }

    private Map<String, CatalogDataset> datasets(String workspaceId, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, workspaceId)
                        .in(CatalogDataset::getId, ids)).stream()
                .collect(Collectors.toMap(CatalogDataset::getId, Function.identity()));
    }

    private Map<String, CatalogColumn> columns(List<QualityRule> rules) {
        List<String> ids = rules.stream()
                .map(QualityRule::getColumnId)
                .filter(id -> id != null)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, ids)).stream()
                .collect(Collectors.toMap(CatalogColumn::getId, Function.identity()));
    }

    private Map<String, QualityRule> rules(String workspaceId, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ruleMapper.selectList(new LambdaQueryWrapper<QualityRule>()
                        .eq(QualityRule::getWorkspaceId, workspaceId)
                        .in(QualityRule::getId, ids)).stream()
                .collect(Collectors.toMap(QualityRule::getId, Function.identity()));
    }

    private RuleView toRuleView(QualityRule rule, Map<String, CatalogDataset> datasets,
                                Map<String, CatalogColumn> columns) {
        CatalogDataset dataset = datasets.get(rule.getDatasetId());
        CatalogColumn column = rule.getColumnId() == null
                ? null : columns.get(rule.getColumnId());
        return new RuleView(rule.getId(), rule.getDatasetId(),
                dataset == null ? null : dataset.getName(),
                dataset == null ? null : dataset.getQualifiedName(),
                rule.getColumnId(), column == null ? null : column.getName(),
                rule.getRuleType(), readConfig(rule.getConfigJson()), rule.getSeverity(),
                Boolean.TRUE.equals(rule.getEnabled()), rule.getCreatedAt(),
                rule.getUpdatedAt(), rule.getUpdatedBy());
    }

    private RunView toRunView(QualityRun run, QualityRule rule, CatalogDataset dataset,
                              CatalogColumn column) {
        return new RunView(run.getId(), run.getRuleId(),
                rule == null ? null : rule.getRuleType(),
                run.getDatasetId(), dataset == null ? null : dataset.getName(),
                dataset == null ? null : dataset.getQualifiedName(),
                rule == null ? null : rule.getColumnId(),
                column == null ? null : column.getName(), run.getStatus(),
                Boolean.TRUE.equals(run.getPassed()), readConfig(run.getMetricJson()),
                run.getViolations(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt(), run.getDurationMs(), run.getExecutedBy());
    }

    private static int weight(String severity) {
        return WEIGHTS.getOrDefault(severity, 2);
    }

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeConfig(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    "质量规则配置无法序列化", e.getMessage(), e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
