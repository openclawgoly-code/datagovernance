package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.domain.SensitivityMatcher;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.entity.SensitivityEntities.ScanFinding;
import com.datagov.metadata.entity.SensitivityEntities.ScanRun;
import com.datagov.metadata.entity.SensitivityEntities.SensitivityRule;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.ScanFindingMapper;
import com.datagov.metadata.mapper.ScanRunMapper;
import com.datagov.metadata.mapper.SensitivityRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感标签(P10.4)。
 *
 * <p>首期是“元数据规则扫描”:按字段名/注释匹配内置中文 PII 规则。
 * 内容采样(身份证/手机号/银行卡 Luhn)留到下一阶段;这里不假装做了内容识别。
 *
 * <p>人工确认过的标签不会被后续自动扫描覆盖 —— 自动推断与人工结论冲突时,
 * 应该让人工结论赢,否则复核就白做了。
 */
@Service
public class SensitivityService {

    private static final Logger log = LoggerFactory.getLogger(SensitivityService.class);

    private static final Set<String> LEVELS = Set.of("L1", "L2", "L3", "L4");
    private static final Set<String> REVIEW_STATUS = Set.of("CONFIRMED", "REJECTED");

    private final SensitivityRuleMapper ruleMapper;
    private final ColumnSensitivityMapper sensitivityMapper;
    private final ScanRunMapper scanRunMapper;
    private final ScanFindingMapper scanFindingMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final ObjectMapper objectMapper;

    public SensitivityService(SensitivityRuleMapper ruleMapper,
                              ColumnSensitivityMapper sensitivityMapper,
                              ScanRunMapper scanRunMapper,
                              ScanFindingMapper scanFindingMapper,
                              CatalogDatasetMapper datasetMapper,
                              CatalogColumnMapper columnMapper,
                              ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.sensitivityMapper = sensitivityMapper;
        this.scanRunMapper = scanRunMapper;
        this.scanFindingMapper = scanFindingMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.objectMapper = objectMapper;
    }

    public record ScanRequest(String datasetId) {
    }

    public record ScanResult(String runId, int scannedColumns, int findings,
                             long durationMillis) {
    }

    public record ReviewRequest(String status, String level) {
    }

    public record RuleView(String id, String name, String category, String level,
                           String detectorType, String pattern, Integer priority,
                           Boolean enabled, String workspaceId) {
        static RuleView from(SensitivityRule rule) {
            return new RuleView(rule.getId(), rule.getName(), rule.getCategory(),
                    rule.getLevel(), rule.getDetectorType(), rule.getPattern(),
                    rule.getPriority(), rule.getEnabled(), rule.getWorkspaceId());
        }
    }

    public record SensitivityView(String id, String datasetId, String columnId,
                                  String columnName, String category, String level,
                                  Double confidence, String status, String evidenceJson,
                                  String detectedBy, Instant detectedAt,
                                  String reviewedBy, Instant reviewedAt) {
        static SensitivityView from(ColumnSensitivity value, String columnName) {
            return new SensitivityView(value.getId(), value.getDatasetId(),
                    value.getColumnId(), columnName, value.getCategory(), value.getLevel(),
                    value.getConfidence(), value.getStatus(), value.getEvidenceJson(),
                    value.getDetectedBy(), value.getDetectedAt(), value.getReviewedBy(),
                    value.getReviewedAt());
        }
    }

    // ── 扫描 ────────────────────────────────────────────────────────────

    /**
     * 按元数据规则扫描字段名/注释。可指定 datasetId,也可全工作区扫描。
     */
    public ScanResult scan(ScanRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Instant startedAt = Instant.now();

        ScanRun run = new ScanRun();
        run.setId(Ids.of("scan"));
        run.setWorkspaceId(workspaceId);
        run.setStartedAt(startedAt);
        run.setStatus("RUNNING");
        scanRunMapper.insert(run);

        try {
            String requestedDatasetId = request == null ? null : request.datasetId();
            List<CatalogDataset> datasets = datasets(workspaceId, requestedDatasetId);
            if (requestedDatasetId != null && !requestedDatasetId.isBlank()
                    && datasets.isEmpty()) {
                throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND,
                        requestedDatasetId);
            }
            if (datasets.isEmpty()) {
                finishRun(run, 0, 0);
                return new ScanResult(run.getId(), 0, 0, elapsed(startedAt));
            }
            List<String> datasetIds = datasets.stream().map(CatalogDataset::getId).toList();
            List<CatalogColumn> columns = columnMapper.selectList(
                    new LambdaQueryWrapper<CatalogColumn>()
                            .in(CatalogColumn::getDatasetId, datasetIds));
            List<SensitivityRule> rules = activeRules(workspaceId);
            int findings = 0;
            if (!rules.isEmpty()) {
                for (CatalogColumn column : columns) {
                    for (SensitivityRule rule : rules) {
                        SensitivityMatcher.Match match = SensitivityMatcher
                                .match(rule, column.getName(), column.getComment())
                                .orElse(null);
                        if (match == null) {
                            continue;
                        }
                        findings++;
                        upsertLabel(workspaceId, column, match);
                        recordFinding(run.getId(), column, match);
                    }
                }
            }
            finishRun(run, columns.size(), findings);
            return new ScanResult(run.getId(), columns.size(), findings,
                    elapsed(startedAt));
        } catch (RuntimeException ex) {
            run.setFinishedAt(Instant.now());
            run.setStatus("FAILED");
            run.setErrorMessage(truncate(ex.getMessage()));
            scanRunMapper.updateById(run);
            log.warn("敏感扫描失败 workspace={} dataset={}", workspaceId,
                    request == null ? null : request.datasetId(), ex);
            if (ex instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.MTD_SENSITIVITY_SCAN_FAILED,
                    "敏感扫描失败: " + ex.getMessage(), null, ex);
        }
    }

    private List<CatalogDataset> datasets(String workspaceId, String datasetId) {
        LambdaQueryWrapper<CatalogDataset> query = new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getWorkspaceId, workspaceId);
        if (datasetId != null && !datasetId.isBlank()) {
            query.eq(CatalogDataset::getId, datasetId);
        }
        return datasetMapper.selectList(query);
    }

    private List<SensitivityRule> activeRules(String workspaceId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<SensitivityRule>()
                .eq(SensitivityRule::getEnabled, true)
                .and(w -> w.isNull(SensitivityRule::getWorkspaceId)
                        .or().eq(SensitivityRule::getWorkspaceId, workspaceId))
                .orderByAsc(SensitivityRule::getPriority));
    }

    private void upsertLabel(String workspaceId, CatalogColumn column,
                             SensitivityMatcher.Match match) {
        ColumnSensitivity existing = sensitivityMapper.selectOne(
                new LambdaQueryWrapper<ColumnSensitivity>()
                        .eq(ColumnSensitivity::getDatasetId, column.getDatasetId())
                        .eq(ColumnSensitivity::getColumnId, column.getId())
                        .eq(ColumnSensitivity::getCategory, match.category()));
        if (existing != null
                && ("CONFIRMED".equals(existing.getStatus())
                || "REJECTED".equals(existing.getStatus()))) {
            // 人工确认/拒绝过的标签不被自动扫描覆盖
            return;
        }
        Instant now = Instant.now();
        ColumnSensitivity entity = existing == null ? new ColumnSensitivity() : existing;
        if (existing == null) {
            entity.setId(Ids.of("sens"));
            entity.setWorkspaceId(workspaceId);
            entity.setDatasetId(column.getDatasetId());
            entity.setColumnId(column.getId());
            entity.setCategory(match.category());
        }
        entity.setLevel(match.level());
        entity.setConfidence(match.confidence());
        entity.setStatus("SUGGESTED");
        entity.setEvidenceJson(writeJson(Map.of(
                "ruleId", match.ruleId(),
                "ruleName", match.ruleName())));
        entity.setDetectedBy("RULE");
        entity.setDetectedAt(now);
        if (existing == null) {
            sensitivityMapper.insert(entity);
        } else {
            sensitivityMapper.updateById(entity);
        }
    }

    private void recordFinding(String runId, CatalogColumn column,
                               SensitivityMatcher.Match match) {
        ScanFinding finding = new ScanFinding();
        finding.setId(Ids.of("scanf"));
        finding.setRunId(runId);
        finding.setDatasetId(column.getDatasetId());
        finding.setColumnId(column.getId());
        finding.setRuleId(match.ruleId());
        finding.setCategory(match.category());
        finding.setLevel(match.level());
        finding.setMatchCount(1L);
        finding.setSampleCount(0L);
        finding.setConfidence(match.confidence());
        finding.setSampleMasked(null);
        finding.setCreatedAt(Instant.now());
        scanFindingMapper.insert(finding);
    }

    private void finishRun(ScanRun run, int columns, int findings) {
        Instant finishedAt = Instant.now();
        run.setFinishedAt(finishedAt);
        run.setStatus("SUCCESS");
        run.setStatsJson(writeJson(Map.of(
                "scannedColumns", columns,
                "findings", findings)));
        scanRunMapper.updateById(run);
    }

    // ── 查询与复核 ──────────────────────────────────────────────────────

    public PageResult<SensitivityView> list(long page, long size, String level,
                                            String status, String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String levelFilter = level == null || level.isBlank()
                ? null : level.trim().toUpperCase();
        String statusFilter = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        LambdaQueryWrapper<ColumnSensitivity> query = new LambdaQueryWrapper<ColumnSensitivity>()
                .eq(ColumnSensitivity::getWorkspaceId, workspaceId)
                .eq(levelFilter != null, ColumnSensitivity::getLevel, levelFilter)
                .eq(statusFilter != null, ColumnSensitivity::getStatus, statusFilter)
                .eq(datasetId != null && !datasetId.isBlank(),
                        ColumnSensitivity::getDatasetId, datasetId)
                .orderByDesc(ColumnSensitivity::getDetectedAt);
        Page<ColumnSensitivity> result = sensitivityMapper.selectPage(Page.of(page, size), query);

        Map<String, String> columnNames = new HashMap<>();
        List<String> columnIds = result.getRecords().stream()
                .map(ColumnSensitivity::getColumnId).distinct().toList();
        if (!columnIds.isEmpty()) {
            for (CatalogColumn column : columnMapper.selectList(
                    new LambdaQueryWrapper<CatalogColumn>()
                            .in(CatalogColumn::getId, columnIds))) {
                columnNames.put(column.getId(), column.getName());
            }
        }
        return PageResult.of(result.getRecords().stream()
                        .map(value -> SensitivityView.from(value,
                                columnNames.getOrDefault(value.getColumnId(), value.getColumnId())))
                        .toList(),
                result.getTotal(), page, size);
    }

    public List<RuleView> rules() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return ruleMapper.selectList(new LambdaQueryWrapper<SensitivityRule>()
                        .and(w -> w.isNull(SensitivityRule::getWorkspaceId)
                                .or().eq(SensitivityRule::getWorkspaceId, workspaceId))
                        .orderByAsc(SensitivityRule::getPriority)).stream()
                .map(RuleView::from)
                .toList();
    }

    @Transactional
    public SensitivityView review(String id, ReviewRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        ColumnSensitivity value = sensitivityMapper.selectOne(
                new LambdaQueryWrapper<ColumnSensitivity>()
                        .eq(ColumnSensitivity::getId, id)
                        .eq(ColumnSensitivity::getWorkspaceId, workspaceId));
        if (value == null) {
            throw BizException.notFound(ErrorCode.MTD_SENSITIVITY_NOT_FOUND, id);
        }
        if (request == null || request.status() == null
                || !REVIEW_STATUS.contains(request.status().toUpperCase())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "复核状态只能是 CONFIRMED / REJECTED");
        }
        if (request.level() != null && !request.level().isBlank()
                && !LEVELS.contains(request.level().toUpperCase())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "敏感级别只能是 L1 / L2 / L3 / L4");
        }
        value.setStatus(request.status().toUpperCase());
        if (request.level() != null && !request.level().isBlank()) {
            value.setLevel(request.level().toUpperCase());
        }
        value.setReviewedBy(WorkspaceContext.require().userId());
        value.setReviewedAt(Instant.now());
        sensitivityMapper.updateById(value);

        CatalogColumn column = columnMapper.selectOne(new LambdaQueryWrapper<CatalogColumn>()
                .eq(CatalogColumn::getId, value.getColumnId()));
        return SensitivityView.from(value,
                column == null ? value.getColumnId() : column.getName());
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "敏感标签 JSON 序列化失败", null, e);
        }
    }

    private static long elapsed(Instant startedAt) {
        return Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "…";
    }
}
