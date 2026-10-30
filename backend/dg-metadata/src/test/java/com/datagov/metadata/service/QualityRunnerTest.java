package com.datagov.metadata.service;

import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("质量规则执行器")
class QualityRunnerTest {

    private static final String WORKSPACE = "ws_alpha";

    private CatalogSourceMapper sourceMapper;
    private DataSourceService dataSourceService;
    private ConnectionConfigAssembler assembler;
    private DataAccessGateway gateway;
    private QualityRunner runner;

    @BeforeEach
    void setUp() {
        sourceMapper = mock(CatalogSourceMapper.class);
        dataSourceService = mock(DataSourceService.class);
        assembler = mock(ConnectionConfigAssembler.class);
        gateway = mock(DataAccessGateway.class);
        runner = new QualityRunner(sourceMapper, dataSourceService, assembler, gateway,
                new ObjectMapper());
        when(sourceMapper.selectById("cat_1")).thenReturn(source());
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(dataSource());
        when(assembler.assemble(any(), eq(WORKSPACE))).thenReturn(
                ConnectionConfig.builder().host("db").database("dwd").build());
    }

    @Test
    @DisplayName("NOT_NULL 按 null 比例判定")
    void notNull() {
        when(gateway.executeQuery(eq(DataSourceType.MYSQL), any(), any()))
                .thenReturn(result(List.of("100", "98")));

        var passed = runner.evaluate(rule("NOT_NULL", "{\"maxNullRatio\":0.05}"),
                dataset(), column());
        var failed = runner.evaluate(rule("NOT_NULL", "{\"maxNullRatio\":0.01}"),
                dataset(), column());

        assertThat(passed.passed()).isTrue();
        assertThat(passed.metric()).containsEntry("nullRows", 2L);
        assertThat(failed.passed()).isFalse();
    }

    @Test
    @DisplayName("UNIQUE 按重复比例判定")
    void unique() {
        when(gateway.executeQuery(eq(DataSourceType.MYSQL), any(), any()))
                .thenReturn(result(List.of("100", "100")));

        var result = runner.evaluate(rule("UNIQUE", "{\"maxDuplicateRatio\":0}"),
                dataset(), column());

        assertThat(result.passed()).isTrue();
        assertThat(result.metric()).containsEntry("duplicateRows", 0L);
    }

    @Test
    @DisplayName("FRESHNESS 解析数据库时间并按小时判定")
    void freshness() {
        String recent = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        when(gateway.executeQuery(eq(DataSourceType.MYSQL), any(), any()))
                .thenReturn(result(List.of(recent)));

        var result = runner.evaluate(rule("FRESHNESS", "{\"maxAgeHours\":24}"),
                dataset(), column());

        assertThat(result.passed()).isTrue();
        assertThat(result.metric()).containsKey("ageHours");
    }

    @Test
    @DisplayName("FRESHNESS 支持 JDBC 时间戳格式（含小数秒/时区）")
    void freshnessJdbcTimestamp() {
        String timestamp = "2026-10-30 11:52:31.052912+00";
        when(gateway.executeQuery(eq(DataSourceType.MYSQL), any(), any()))
                .thenReturn(result(List.of(timestamp)));

        var result = runner.evaluate(rule("FRESHNESS", "{\"maxAgeHours\":8760}"),
                dataset(), column());

        assertThat(result.passed()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("SCHEMA_DRIFT 与基线比较")
    void schemaDrift() {
        var same = runner.evaluate(rule("SCHEMA_DRIFT",
                "{\"baselineSchemaHash\":\"hash-1\"}"), dataset(), null);
        var drifted = runner.evaluate(rule("SCHEMA_DRIFT",
                "{\"baselineSchemaHash\":\"hash-2\"}"), dataset(), null);

        assertThat(same.passed()).isTrue();
        assertThat(drifted.passed()).isFalse();
    }

    @Test
    @DisplayName("ROW_COUNT_RANGE 生成带方言 quoting 的 COUNT 查询")
    void rowCountRangeSql() {
        when(gateway.executeQuery(eq(DataSourceType.MYSQL), any(), any()))
                .thenReturn(result(List.of("42")));

        var result = runner.evaluate(rule("ROW_COUNT_RANGE",
                "{\"min\":10,\"max\":100}"), dataset(), null);

        ArgumentCaptor<SqlQuery.Request> captor = ArgumentCaptor.forClass(SqlQuery.Request.class);
        org.mockito.Mockito.verify(gateway).executeQuery(eq(DataSourceType.MYSQL), any(),
                captor.capture());
        assertThat(captor.getValue().sql()).contains("`dwd`.`orders`");
        assertThat(result.passed()).isTrue();
        assertThat(result.metric()).containsEntry("rowCount", 42L);
    }

    @Test
    @DisplayName("非 JDBC 来源明确失败，不伪造通过")
    void nonJdbcSourceFails() {
        CatalogSource source = source();
        source.setSourceType("PARQUET_DIRECTORY");
        when(sourceMapper.selectById("cat_1")).thenReturn(source);

        assertThatThrownBy(() -> runner.evaluate(rule("ROW_COUNT_RANGE", "{\"min\":1}"),
                dataset(), null))
                .hasMessageContaining("不支持质量 SQL");
    }

    private static SqlQuery.Result result(List<String> row) {
        return new SqlQuery.Result(List.of(), List.of(row), 1, false, 3);
    }

    private static CatalogSource source() {
        CatalogSource source = new CatalogSource();
        source.setId("cat_1");
        source.setWorkspaceId(WORKSPACE);
        source.setSourceType("JDBC");
        source.setDataSourceId("ds_1");
        return source;
    }

    private static DataSourceEntity dataSource() {
        DataSourceEntity dataSource = new DataSourceEntity();
        dataSource.setId("ds_1");
        dataSource.setWorkspaceId(WORKSPACE);
        dataSource.setType(DataSourceType.MYSQL);
        dataSource.setStatus(DataSourceStatus.AVAILABLE);
        return dataSource;
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setSourceId("cat_1");
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

    private static QualityRule rule(String type, String configJson) {
        QualityRule rule = new QualityRule();
        rule.setId("qrule_1");
        rule.setDatasetId("catds_1");
        rule.setRuleType(type);
        rule.setConfigJson(configJson);
        return rule;
    }
}
