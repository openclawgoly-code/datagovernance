package com.datagov.governance.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.AccessRequestEntities.AccessGrant;
import com.datagov.governance.entity.AccessRequestEntities.AccessRequest;
import com.datagov.governance.mapper.AccessGrantMapper;
import com.datagov.governance.mapper.AccessRequestMapper;
import com.datagov.governance.service.AccessRequestService.CreateRequest;
import com.datagov.governance.service.AccessRequestService.Decision;
import com.datagov.governance.service.AccessRequestService.GrantView;
import com.datagov.governance.service.AccessRequestService.RequestView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("敏感访问申请")
class AccessRequestServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String REQUESTER = "bob";
    private static final String APPROVER = "alice";

    private AccessRequestMapper requestMapper;
    private AccessGrantMapper grantMapper;
    private AccessRequestService service;

    @BeforeEach
    void setUp() {
        requestMapper = mock(AccessRequestMapper.class);
        grantMapper = mock(AccessGrantMapper.class);
        service = new AccessRequestService(requestMapper, grantMapper);
        WorkspaceContext.set(new Caller("usr_bob", REQUESTER, WORKSPACE, false,
                Set.of("metadata:access:request")));
        when(requestMapper.insert(any(AccessRequest.class))).thenReturn(1);
        when(requestMapper.updateById(any(AccessRequest.class))).thenReturn(1);
        when(grantMapper.insert(any(AccessGrant.class))).thenReturn(1);
        when(grantMapper.updateById(any(AccessGrant.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("申请")
    class Create {

        @Test
        @DisplayName("原因少于 10 字 —— 400")
        void shortReasonRejected() {
            assertThatThrownBy(() -> service.create(
                    new CreateRequest("PII", "L3", "太短", 8)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_ACCESS_REQUEST_INVALID));
            verify(requestMapper, never()).insert(any(AccessRequest.class));
        }

        @Test
        @DisplayName("时长超过 168 小时 —— 400")
        void durationOutOfRange() {
            assertThatThrownBy(() -> service.create(
                    new CreateRequest("PII", "L3", "需要排查客户投诉的具体记录", 169)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_ACCESS_REQUEST_INVALID));
        }

        @Test
        @DisplayName("合法申请为 PENDING")
        void pendingRequest() {
            RequestView view = service.create(
                    new CreateRequest("PII", "L3", "需要排查客户投诉的具体记录", 8));

            assertThat(view.status()).isEqualTo("PENDING");
            assertThat(view.requester()).isEqualTo(REQUESTER);
            assertThat(view.category()).isEqualTo("PII");
            assertThat(view.maxLevel()).isEqualTo("L3");
        }
    }

    @Nested
    @DisplayName("审批")
    class Approve {

        @Test
        @DisplayName("自审批 —— 403 且不创建 grant")
        void selfApprovalForbidden() {
            WorkspaceContext.set(new Caller("usr_bob", REQUESTER, WORKSPACE, false,
                    Set.of("metadata:access:request", "metadata:access:approve")));
            when(requestMapper.selectById("areq_1")).thenReturn(request("PENDING", REQUESTER));

            assertThatThrownBy(() -> service.approve("areq_1", new Decision("ok")))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_ACCESS_SELF_APPROVAL));
            verify(grantMapper, never()).insert(any(AccessGrant.class));
        }

        @Test
        @DisplayName("批准创建带到期时间的 grant")
        void approveCreatesGrant() {
            asApprover();
            when(requestMapper.selectById("areq_1")).thenReturn(request("PENDING", REQUESTER));

            RequestView view = service.approve("areq_1", new Decision("同意"));

            assertThat(view.status()).isEqualTo("APPROVED");
            assertThat(view.approver()).isEqualTo(APPROVER);
            assertThat(view.grant()).isNotNull();
            assertThat(view.grant().status()).isEqualTo("ACTIVE");
            assertThat(view.grant().expiresAt()).isAfter(Instant.now());
            ArgumentCaptor<AccessGrant> captor = ArgumentCaptor.forClass(AccessGrant.class);
            verify(grantMapper).insert(captor.capture());
            assertThat(captor.getValue().getUsername()).isEqualTo(REQUESTER);
            assertThat(captor.getValue().getMaxLevel()).isEqualTo("L3");
        }

        @Test
        @DisplayName("非 PENDING 再审批 —— 409")
        void nonPendingRejected() {
            asApprover();
            when(requestMapper.selectById("areq_1")).thenReturn(request("APPROVED", REQUESTER));

            assertThatThrownBy(() -> service.approve("areq_1", new Decision("again")))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_ACCESS_REQUEST_STATE));
            verify(grantMapper, never()).insert(any(AccessGrant.class));
        }
    }

    @Nested
    @DisplayName("授权")
    class Grants {

        @Test
        @DisplayName("activeGrants 只返回未撤销且未过期的记录")
        void activeGrantsFiltersExpired() {
            AccessGrant active = grant("agrant_1", Instant.now().plusSeconds(3600), null);
            AccessGrant expired = grant("agrant_2", Instant.now().minusSeconds(1), null);
            AccessGrant revoked = grant("agrant_3", Instant.now().plusSeconds(3600), Instant.now());
            when(grantMapper.selectList(any())).thenReturn(List.of(active, expired, revoked));

            var result = service.activeGrants(WORKSPACE, REQUESTER);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("agrant_1");
        }

        @Test
        @DisplayName("撤销后列表状态为 REVOKED")
        void revokeMarksGrant() {
            asApprover();
            AccessGrant grant = grant("agrant_1", Instant.now().plusSeconds(3600), null);
            when(grantMapper.selectById("agrant_1")).thenReturn(grant);

            GrantView view = service.revoke("agrant_1");

            assertThat(view.status()).isEqualTo("REVOKED");
            assertThat(grant.getRevokedBy()).isNotNull();
        }

        @Test
        @DisplayName("普通用户只能看自己的 grant")
        void listGrantsScopedToUser() {
            when(grantMapper.selectList(any())).thenReturn(List.of(
                    grant("agrant_1", Instant.now().plusSeconds(3600), null)));

            List<GrantView> grants = service.listGrants();

            assertThat(grants).hasSize(1);
            assertThat(grants.get(0).username()).isEqualTo(REQUESTER);
        }
    }

    private void asApprover() {
        WorkspaceContext.set(new Caller("usr_alice", APPROVER, WORKSPACE, false,
                Set.of("metadata:access:request", "metadata:access:approve")));
    }

    private static AccessRequest request(String status, String requester) {
        AccessRequest request = new AccessRequest();
        request.setId("areq_1");
        request.setWorkspaceId(WORKSPACE);
        request.setRequester(requester);
        request.setCategory("PII");
        request.setMaxLevel("L3");
        request.setReason("需要排查客户投诉的具体记录");
        request.setDurationHours(8);
        request.setStatus(status);
        request.setCreatedAt(Instant.now());
        return request;
    }

    private static AccessGrant grant(String id, Instant expiresAt, Instant revokedAt) {
        AccessGrant grant = new AccessGrant();
        grant.setId(id);
        grant.setWorkspaceId(WORKSPACE);
        grant.setRequestId("areq_1");
        grant.setUsername(REQUESTER);
        grant.setCategory("PII");
        grant.setMaxLevel("L3");
        grant.setExpiresAt(expiresAt);
        grant.setRevokedAt(revokedAt);
        grant.setCreatedAt(Instant.now());
        return grant;
    }
}
