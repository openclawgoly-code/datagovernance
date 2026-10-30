package com.datagov.app.insight;

import com.datagov.app.insight.entity.InsightDashboard;
import com.datagov.app.insight.entity.InsightDashboardAcl;
import com.datagov.app.insight.mapper.InsightDashboardAclMapper;
import com.datagov.app.insight.mapper.InsightDashboardItemMapper;
import com.datagov.app.insight.mapper.InsightDashboardMapper;
import com.datagov.app.insight.mapper.InsightDashboardVersionMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("看板 ACL 成员校验")
class InsightDashboardAclTest {

    private static final String WORKSPACE = "ws_alpha";

    private InsightDashboardMapper dashboardMapper;
    private InsightDashboardAclMapper aclMapper;
    private WorkspaceService workspaceService;
    private UserService userService;
    private InsightDashboardService service;

    @BeforeEach
    void setUp() {
        dashboardMapper = mock(InsightDashboardMapper.class);
        aclMapper = mock(InsightDashboardAclMapper.class);
        workspaceService = mock(WorkspaceService.class);
        userService = mock(UserService.class);
        service = new InsightDashboardService(dashboardMapper,
                mock(InsightDashboardItemMapper.class), aclMapper,
                mock(InsightDashboardVersionMapper.class),
                mock(InsightQueryService.class), new ObjectMapper(),
                workspaceService, userService);
        WorkspaceContext.set(new Caller("usr_alice", "alice", WORKSPACE, false,
                Set.of("insight:dashboard:manage")));
        InsightDashboard dashboard = new InsightDashboard();
        dashboard.setId("dash_1");
        dashboard.setWorkspaceId(WORKSPACE);
        dashboard.setCreatedBy("alice");
        dashboard.setName("看板");
        when(dashboardMapper.selectOne(any())).thenReturn(dashboard);
        when(aclMapper.selectOne(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("直接调 API 添加非空间成员 —— 400 且不写 ACL")
    void rejectsNonMember() {
        when(workspaceService.listMemberIds(WORKSPACE)).thenReturn(List.of("usr_alice"));
        when(userService.listByIds(List.of("usr_alice"))).thenReturn(List.of(
                user("usr_alice", "alice", "ACTIVE")));

        assertThatThrownBy(() -> service.addAcl("dash_1",
                new InsightDashboardService.AclRequest("outsider", "READ")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ACTIVE 成员");
        verify(aclMapper, never()).insert(any(InsightDashboardAcl.class));
    }

    @Test
    @DisplayName("已禁用成员不能加入 ACL")
    void rejectsDisabledMember() {
        when(workspaceService.listMemberIds(WORKSPACE))
                .thenReturn(List.of("usr_alice", "usr_bob"));
        when(userService.listByIds(List.of("usr_alice", "usr_bob"))).thenReturn(List.of(
                user("usr_alice", "alice", "ACTIVE"),
                user("usr_bob", "bob", "DISABLED")));

        assertThatThrownBy(() -> service.addAcl("dash_1",
                new InsightDashboardService.AclRequest("bob", "READ")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ACTIVE 成员");
        verify(aclMapper, never()).insert(any(InsightDashboardAcl.class));
    }

    @Test
    @DisplayName("空间 ACTIVE 成员可以加入 ACL")
    void acceptsActiveMember() {
        when(workspaceService.listMemberIds(WORKSPACE))
                .thenReturn(List.of("usr_alice", "usr_bob"));
        when(userService.listByIds(List.of("usr_alice", "usr_bob"))).thenReturn(List.of(
                user("usr_alice", "alice", "ACTIVE"),
                user("usr_bob", "bob", "ACTIVE")));

        var view = service.addAcl("dash_1",
                new InsightDashboardService.AclRequest(" bob ", "READ"));

        assertThat(view.username()).isEqualTo("bob");
        assertThat(view.permission()).isEqualTo("READ");
        verify(aclMapper).insert(any(InsightDashboardAcl.class));
    }

    private static UserView user(String id, String username, String status) {
        return new UserView(id, username, username, null, null, status, false,
                null, null);
    }
}
