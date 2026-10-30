package com.datagov.app.insight;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.GrantCoverage;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.app.insight.entity.InsightQueryLog;
import com.datagov.app.insight.mapper.InsightQueryLogMapper;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import com.datagov.platform.service.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("问数脱敏与临时 grant")
class InsightQueryServiceGrantTest {

    private static final String WORKSPACE = "ws_alpha";

    private SuperSonicClient client;
    private InsightQueryLogMapper logMapper;
    private SensitivityService sensitivityService;
    private AccessRequestService accessRequestService;
    private AuditService auditService;
    private InsightQueryService service;

    @BeforeEach
    void setUp() {
        client = mock(SuperSonicClient.class);
        logMapper = mock(InsightQueryLogMapper.class);
        sensitivityService = mock(SensitivityService.class);
        accessRequestService = mock(AccessRequestService.class);
        auditService = mock(AuditService.class);
        InsightSupersonicMappingService mappingService = mock(InsightSupersonicMappingService.class);
        CredentialService credentialService = mock(CredentialService.class);
        InsightProperties properties = new InsightProperties();
        properties.setPassword("service-password");
        properties.setDefaultDatasetId(4);
        properties.setMaxRows(200);
        service = new InsightQueryService(client, properties, logMapper, sensitivityService,
                accessRequestService, auditService, mappingService, credentialService);
        WorkspaceContext.set(new Caller("usr_alice", "alice", WORKSPACE, false,
                Set.of("insight:ask")));
        when(mappingService.find(WORKSPACE, "alice")).thenReturn(null);
        when(client.ask(anyString(), anyInt(), anyLong(), anyString(), anyString()))
                .thenReturn(new SuperSonicClient.AskResult("q1", "SUCCESS",
                        "SELECT phone, id_card FROM customer",
                        List.of(new SuperSonicClient.Column("phone", "phone", "string"),
                                new SuperSonicClient.Column("id_card", "id_card", "string")),
                        List.of(Map.of("phone", "13800000000", "id_card", "110101199001011234")),
                        "1 行", null));
        when(sensitivityService.sensitiveColumnNames()).thenReturn(List.of(
                new SensitiveColumnView("phone", "PII", "L2"),
                new SensitiveColumnView("id_card", "PII", "L4")));
        when(accessRequestService.activeGrants(WORKSPACE, "alice")).thenReturn(List.of(
                new GrantCoverage("agrant_1", "PII", "L3", Instant.now().plusSeconds(3600))));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("PII/L3 grant 放行 L2，L4 仍脱敏，并在历史/审计中记录 grant")
    void grantCoversOnlyWithinLevelAndCategory() {
        InsightQueryService.Answer answer = service.ask(
                new InsightQueryService.AskRequest("查客户联系方式和证件号", 4L, 5));

        assertThat(answer.maskedColumns()).contains("id_card");
        assertThat(answer.maskedColumns()).doesNotContain("phone");
        assertThat(answer.grantIds()).containsExactly("agrant_1");

        ArgumentCaptor<InsightQueryLog> logCaptor = ArgumentCaptor.forClass(InsightQueryLog.class);
        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getMaskedColumns()).contains("id_card");
        assertThat(logCaptor.getValue().getMaskedColumns()).doesNotContain("phone");
        assertThat(logCaptor.getValue().getGrantIds()).isEqualTo("agrant_1");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().detail()).contains("grants=[agrant_1]");
    }
}
