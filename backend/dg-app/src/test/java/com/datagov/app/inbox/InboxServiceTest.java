package com.datagov.app.inbox;

import com.datagov.common.api.PageResult;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.AlertStatus;
import com.datagov.governance.entity.GovernanceEntities.InboxState;
import com.datagov.governance.mapper.InboxStateMapper;
import com.datagov.governance.service.AlertService;
import com.datagov.governance.service.AlertService.AlertView;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitivityView;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.service.ExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("治理收件箱")
class InboxServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private ExecutionService executionService;
    private AlertService alertService;
    private SensitivityService sensitivityService;
    private InboxStateMapper stateMapper;
    private InboxService service;

    @BeforeEach
    void setUp() {
        executionService = mock(ExecutionService.class);
        alertService = mock(AlertService.class);
        sensitivityService = mock(SensitivityService.class);
        stateMapper = mock(InboxStateMapper.class);
        service = new InboxService(executionService, alertService, sensitivityService, stateMapper);
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false,
                Set.of("runtime:execution:read", "governance:alert:read",
                        "metadata:sensitivity:read", "governance:inbox:manage")));
        when(stateMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("按来源权限聚合,并用收件箱状态覆盖")
    void aggregatesAndAppliesState() {
        when(executionService.list(anyLong(), anyLong(), isNull(),
                eq(ExecutionStatus.FAILED), isNull()))
                .thenReturn(PageResult.of(List.of(execution("exec_1")), 1, 1, 200));
        when(executionService.list(anyLong(), anyLong(), isNull(),
                eq(ExecutionStatus.TIMEOUT), isNull()))
                .thenReturn(PageResult.empty(1, 200));
        when(alertService.list(anyLong(), anyLong(), isNull(), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(alert("alt_1")), 1, 1, 200));
        when(sensitivityService.list(anyLong(), anyLong(), isNull(), eq("SUGGESTED"), isNull()))
                .thenReturn(PageResult.of(List.of(sensitivity("sens_1")), 1, 1, 200));

        InboxState dismissed = new InboxState();
        dismissed.setWorkspaceId(WORKSPACE);
        dismissed.setSourceType("ALERT");
        dismissed.setSourceId("alt_1");
        dismissed.setState("DISMISSED");
        when(stateMapper.selectList(any())).thenReturn(List.of(dismissed));

        InboxService.InboxPage page = service.list(1, 20, null, "OPEN", null, null);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).extracting(InboxService.InboxItemView::sourceType)
                .containsExactly("SENSITIVITY", "EXECUTION");
        assertThat(page.records()).allMatch(item -> "OPEN".equals(item.state()));
        assertThat(page.truncated()).isFalse();

        InboxService.InboxPage dismissedPage = service.list(1, 20, null, "DISMISSED", null, null);
        assertThat(dismissedPage.total()).isEqualTo(1);
        assertThat(dismissedPage.records().get(0).sourceId()).isEqualTo("alt_1");
    }

    @Test
    @DisplayName("缺少来源权限时不查询该来源")
    void skipsSourcesWithoutPermission() {
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false,
                Set.of("metadata:sensitivity:read")));
        when(sensitivityService.list(anyLong(), anyLong(), isNull(), eq("SUGGESTED"), isNull()))
                .thenReturn(PageResult.of(List.of(sensitivity("sens_1")), 1, 1, 200));

        InboxService.InboxPage page = service.list(1, 20, null, "OPEN", null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records().get(0).sourceType()).isEqualTo("SENSITIVITY");
        verifyNoInteractions(executionService, alertService);
    }

    private static ExecutionView execution(String id) {
        Instant now = Instant.now();
        return new ExecutionView(id, JobRefType.OFFLINE_SYNC, "离线同步", null,
                "失败任务", 1, ExecutionStatus.FAILED, "失败", null, null,
                "MANUAL", "admin", 1, now, now, now, 1000L,
                "源表不存在", "SYS_NOT_FOUND", null, null, null);
    }

    private static AlertView alert(String id) {
        return new AlertView(id, "rule_1", "告警规则", AlertStatus.NOTIFIED, "已通知",
                "CRITICAL", "告警标题", "告警内容", null, null, "任务",
                Instant.now(), null, null, null, null, null, null, null);
    }

    private static SensitivityView sensitivity(String id) {
        return new SensitivityView(id, "ds_1", "col_1", "phone", "PII", "L4",
                0.9, "SUGGESTED", "{}", "RULE", Instant.now(), null, null,
                "客户表", "dwd/customer");
    }
}
