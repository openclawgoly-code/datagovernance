package com.datagov.metadata.service;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
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
import com.datagov.metadata.service.SensitivityService.ReviewRequest;
import com.datagov.metadata.service.SensitivityService.ScanRequest;
import com.datagov.metadata.service.SensitivityService.ScanResult;
import com.datagov.metadata.service.SensitivityService.SensitivityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("敏感标签")
class SensitivityServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private SensitivityRuleMapper ruleMapper;
    private ColumnSensitivityMapper sensitivityMapper;
    private ScanRunMapper scanRunMapper;
    private ScanFindingMapper scanFindingMapper;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private SensitivityService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(SensitivityRuleMapper.class);
        sensitivityMapper = mock(ColumnSensitivityMapper.class);
        scanRunMapper = mock(ScanRunMapper.class);
        scanFindingMapper = mock(ScanFindingMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        service = new SensitivityService(ruleMapper, sensitivityMapper, scanRunMapper,
                scanFindingMapper, datasetMapper, columnMapper, new ObjectMapper());

        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(scanRunMapper.insert(any(ScanRun.class))).thenReturn(1);
        when(scanRunMapper.updateById(any(ScanRun.class))).thenReturn(1);
        when(sensitivityMapper.insert(any(ColumnSensitivity.class))).thenReturn(1);
        when(sensitivityMapper.updateById(any(ColumnSensitivity.class))).thenReturn(1);
        when(scanFindingMapper.insert(any(ScanFinding.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("扫描")
    class Scan {

        @Test
        @DisplayName("按字段名命中身份证规则,普通字段不产生标签")
        void scansMetadataRules() {
            CatalogDataset dataset = dataset();
            when(datasetMapper.selectList(any())).thenReturn(List.of(dataset));
            when(columnMapper.selectList(any())).thenReturn(List.of(
                    column("col_id", dataset.getId(), "身份证号", "证件号码"),
                    column("col_order", dataset.getId(), "order_id", "订单编号")));
            when(ruleMapper.selectList(any())).thenReturn(List.of(idCardRule()));
            when(sensitivityMapper.selectOne(any())).thenReturn(null);

            ScanResult result = service.scan(new ScanRequest(dataset.getId()));

            assertThat(result.scannedColumns()).isEqualTo(2);
            assertThat(result.findings()).isEqualTo(1);
            verify(sensitivityMapper).insert(any(ColumnSensitivity.class));
            verify(scanFindingMapper).insert(any(ScanFinding.class));
            verify(scanRunMapper).updateById(any(ScanRun.class));
        }

        @Test
        @DisplayName("人工确认过的标签不被自动扫描覆盖")
        void confirmedLabelIsNotOverwritten() {
            CatalogDataset dataset = dataset();
            when(datasetMapper.selectList(any())).thenReturn(List.of(dataset));
            when(columnMapper.selectList(any())).thenReturn(List.of(
                    column("col_id", dataset.getId(), "身份证号", null)));
            when(ruleMapper.selectList(any())).thenReturn(List.of(idCardRule()));

            ColumnSensitivity confirmed = new ColumnSensitivity();
            confirmed.setId("sens_1");
            confirmed.setDatasetId(dataset.getId());
            confirmed.setColumnId("col_id");
            confirmed.setCategory("PII");
            confirmed.setLevel("L4");
            confirmed.setStatus("CONFIRMED");
            when(sensitivityMapper.selectOne(any())).thenReturn(confirmed);

            ScanResult result = service.scan(new ScanRequest(dataset.getId()));

            // finding 仍记录扫描命中了规则,但 label 不更新
            assertThat(result.findings()).isEqualTo(1);
            org.mockito.Mockito.verify(sensitivityMapper, org.mockito.Mockito.never())
                    .updateById(any(ColumnSensitivity.class));
        }
    }

    @Nested
    @DisplayName("复核")
    class Review {

        @Test
        @DisplayName("CONFIRMED 写入复核人与时间")
        void confirmsLabel() {
            ColumnSensitivity label = new ColumnSensitivity();
            label.setId("sens_1");
            label.setWorkspaceId(WORKSPACE);
            label.setDatasetId("catds_1");
            label.setColumnId("col_id");
            label.setCategory("PII");
            label.setLevel("L3");
            label.setStatus("SUGGESTED");
            when(sensitivityMapper.selectOne(any())).thenReturn(label);
            when(columnMapper.selectOne(any())).thenReturn(
                    column("col_id", "catds_1", "身份证号", null));

            SensitivityView view = service.review("sens_1",
                    new ReviewRequest("CONFIRMED", "L4"));

            assertThat(view.status()).isEqualTo("CONFIRMED");
            assertThat(view.level()).isEqualTo("L4");
            assertThat(view.reviewedBy()).isEqualTo(USER);
            verify(sensitivityMapper).updateById(label);
        }
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setQualifiedName("ecology.oa_user");
        dataset.setName("oa_user");
        dataset.setDeleted(false);
        return dataset;
    }

    private static CatalogColumn column(String id, String datasetId, String name, String comment) {
        CatalogColumn column = new CatalogColumn();
        column.setId(id);
        column.setDatasetId(datasetId);
        column.setName(name);
        column.setComment(comment);
        column.setOrdinal(1);
        column.setCreatedAt(Instant.now());
        return column;
    }

    private static SensitivityRule idCardRule() {
        SensitivityRule rule = new SensitivityRule();
        rule.setId("rule_builtin_id_card");
        rule.setName("身份证号");
        rule.setCategory("PII");
        rule.setLevel("L4");
        rule.setDetectorType("COLUMN_NAME");
        rule.setPattern("(身份证|证件号|id.?card)");
        rule.setPriority(10);
        rule.setEnabled(true);
        return rule;
    }
}
