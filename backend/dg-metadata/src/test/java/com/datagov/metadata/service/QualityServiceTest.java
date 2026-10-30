package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("资产质量")
class QualityServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private QualityRuleMapper ruleMapper;
    private QualityRunMapper runMapper;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private QualityRunner runner;
    private QualityService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(QualityRuleMapper.class);
        runMapper = mock(QualityRunMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        runner = mock(QualityRunner.class);
        service = new QualityService(ruleMapper, runMapper, datasetMapper, columnMapper,
                runner, new ObjectMapper());
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(datasetMapper.selectOne(any())).thenReturn(dataset());
        when(columnMapper.selectById("catcol_1")).thenReturn(column());
        when(ruleMapper.insert(any(QualityRule.class))).thenReturn(1);
        when(ruleMapper.updateById(any(QualityRule.class))).thenReturn(1);
        when(runMapper.insert(any(QualityRun.class))).thenReturn(1);
        when(runMapper.updateById(any(QualityRun.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("规则校验")
    class Validation {

        @Test
        @DisplayName("ROW_COUNT_RANGE min 大于 max —— 400")
        void rowCountRangeInvalid() {
            assertThatThrownBy(() -> service.createRule(new QualityService.RuleRequest(
                    "catds_1", null, "ROW_COUNT_RANGE",
                    Map.of("min", 100, "max", 1), "HIGH", true)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_QUALITY_RULE_INVALID));
            verify(ruleMapper, never()).insert(any(QualityRule.class));
        }

        @Test
        @DisplayName("字段不属于数据集 —— 400")
        void columnMustBelongToDataset() {
            CatalogColumn foreign = column();
            foreign.setDatasetId("catds_2");
            when(columnMapper.selectById("catcol_1")).thenReturn(foreign);

            assertThatThrownBy(() -> service.createRule(new QualityService.RuleRequest(
                    "catds_1", "catcol_1", "NOT_NULL",
                    Map.of("maxNullRatio", 0.01), "HIGH", true)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("字段不属于");
        }

        @Test
        @DisplayName("SCHEMA_DRIFT 未填基线时使用当前 schemaHash")
        void schemaDriftDefaultsBaseline() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(columnMapper.selectList(any())).thenReturn(List.of());
            service.createRule(new QualityService.RuleRequest(
                    "catds_1", null, "SCHEMA_DRIFT", Map.of(), "CRITICAL", true));

            ArgumentCaptor<QualityRule> captor = ArgumentCaptor.forClass(QualityRule.class);
            verify(ruleMapper).insert(captor.capture());
            assertThat(captor.getValue().getConfigJson()).contains("baselineSchemaHash");
            assertThat(captor.getValue().getConfigJson()).contains("hash-1");
        }
    }

    @Nested
    @DisplayName("运行")
    class Runs {

        @Test
        @DisplayName("数据集运行中单条失败不阻断其他规则")
        void partialFailureDoesNotBlock() {
            QualityRule first = rule("qrule_1", "ROW_COUNT_RANGE", "HIGH");
            QualityRule second = rule("qrule_2", "UNIQUE", "CRITICAL");
            when(ruleMapper.selectList(any())).thenReturn(List.of(first, second));
            when(runner.evaluate(eq(first), any(), any())).thenReturn(
                    new QualityResult(true, Map.of("rowCount", 10), 0L, null));
            when(runner.evaluate(eq(second), any(), any()))
                    .thenThrow(new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                            "不支持的类型"));

            List<QualityService.RunView> runs = service.runDataset("catds_1");

            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).status()).isEqualTo("SUCCEEDED");
            assertThat(runs.get(0).passed()).isTrue();
            assertThat(runs.get(1).status()).isEqualTo("FAILED");
            assertThat(runs.get(1).errorMessage()).contains("不支持");
        }

        @Test
        @DisplayName("超时记为 FAILED 并保留原因，不标为通过")
        void timeoutRecordedAsFailed() {
            QualityRule rule = rule("qrule_1", "ROW_COUNT_RANGE", "HIGH");
            when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
            when(runner.evaluate(eq(rule), any(), any()))
                    .thenThrow(new BizException(ErrorCode.DAT_TIMEOUT, "查询超时"));

            List<QualityService.RunView> runs = service.runDataset("catds_1");

            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).status()).isEqualTo("FAILED");
            assertThat(runs.get(0).passed()).isFalse();
            assertThat(runs.get(0).errorMessage()).contains("超时");
        }
    }

    @Nested
    @DisplayName("摘要")
    class Summary {

        @Test
        @DisplayName("按严重度加权计算分数")
        void weightedScore() {
            QualityRule critical = rule("qrule_1", "ROW_COUNT_RANGE", "CRITICAL");
            QualityRule low = rule("qrule_2", "ROW_COUNT_RANGE", "LOW");
            when(ruleMapper.selectList(any())).thenReturn(List.of(critical, low));
            when(runMapper.selectList(any())).thenReturn(List.of(
                    run("qrule_1", true, Instant.now()),
                    run("qrule_2", false, Instant.now().minusSeconds(60))));

            QualityService.QualitySummary summary = service.summary("catds_1");

            assertThat(summary.score()).isEqualTo(83);
            assertThat(summary.evaluatedRules()).isEqualTo(2);
            assertThat(summary.passedRules()).isEqualTo(1);
            assertThat(summary.failedRules()).isEqualTo(1);
        }

        @Test
        @DisplayName("没有运行记录 —— 未评估")
        void neverRunIsUnassessed() {
            QualityRule rule = rule("qrule_1", "ROW_COUNT_RANGE", "HIGH");
            when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
            when(runMapper.selectList(any())).thenReturn(List.of());

            QualityService.QualitySummary summary = service.summary("catds_1");

            assertThat(summary.score()).isNull();
            assertThat(summary.evaluatedRules()).isZero();
            assertThat(summary.rules().get(0).latestStatus()).isEqualTo("NOT_RUN");
        }
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName("orders");
        dataset.setQualifiedName("dwd.orders");
        dataset.setSchemaHash("hash-1");
        return dataset;
    }

    private static CatalogColumn column() {
        CatalogColumn column = new CatalogColumn();
        column.setId("catcol_1");
        column.setDatasetId("catds_1");
        column.setName("amount");
        return column;
    }

    private static QualityRule rule(String id, String type, String severity) {
        QualityRule rule = new QualityRule();
        rule.setId(id);
        rule.setWorkspaceId(WORKSPACE);
        rule.setDatasetId("catds_1");
        rule.setRuleType(type);
        rule.setSeverity(severity);
        rule.setEnabled(true);
        rule.setConfigJson("{\"min\":1}");
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        return rule;
    }

    private static QualityRun run(String ruleId, boolean passed, Instant startedAt) {
        QualityRun run = new QualityRun();
        run.setId("qrun_" + ruleId);
        run.setWorkspaceId(WORKSPACE);
        run.setRuleId(ruleId);
        run.setDatasetId("catds_1");
        run.setStatus("SUCCEEDED");
        run.setPassed(passed);
        run.setStartedAt(startedAt);
        run.setDurationMs(12L);
        return run;
    }
}
