package com.datagov.platform.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.platform.config.SecurityProperties;
import com.datagov.platform.dto.LoginResult;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("认证与会话")
class AuthServiceTest {

    private static final String WS_A = "ws_alpha";
    private static final String WS_B = "ws_beta";

    private UserMapper userMapper;
    private WorkspaceService workspaceService;
    private PermissionService permissionService;
    private AuthService service;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        workspaceService = mock(WorkspaceService.class);
        permissionService = mock(PermissionService.class);

        SecurityProperties properties = new SecurityProperties();
        properties.setSecretKey("test-master-key");
        properties.getJwt().setIssuer("datagovernance");
        properties.getJwt().setTtlHours(8);
        properties.getJwt().setSigningKey("unit-test-signing-key-at-least-32-bytes-long!!");

        service = new AuthService(userMapper, encoder, workspaceService,
                permissionService, properties);

        when(permissionService.resolvePermissions(anyString(), any()))
                .thenReturn(Set.of("menu:metadata"));
        when(permissionService.buildMenuTree(any())).thenReturn(List.of());
        when(userMapper.updateById((User) any())).thenReturn(1);
    }

    @Test
    @DisplayName("口令正确则签发令牌")
    void loginIssuesToken() {
        when(userMapper.selectOne(any())).thenReturn(activeUser("s3cret"));
        when(workspaceService.listAccessible("usr_1")).thenReturn(List.of(workspace(WS_A)));

        LoginResult result = service.login("alice", "s3cret");

        assertThat(result.token()).isNotBlank();
        assertThat(result.user().username()).isEqualTo("alice");
        // 只有一个可访问空间时自动选中,省掉一次无意义的选择
        assertThat(result.currentWorkspaceId()).isEqualTo(WS_A);
    }

    @Test
    @DisplayName("多个可访问空间时不自动选中,由用户选择")
    void multipleWorkspacesRequireExplicitChoice() {
        when(userMapper.selectOne(any())).thenReturn(activeUser("s3cret"));
        when(workspaceService.listAccessible("usr_1"))
                .thenReturn(List.of(workspace(WS_A), workspace(WS_B)));

        assertThat(service.login("alice", "s3cret").currentWorkspaceId()).isNull();
    }

    @Test
    @DisplayName("用户名不存在与口令错误返回同一个错误码")
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        // 区分二者等于提供了一个用户名枚举接口
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException unknownUser = catchBiz(() -> service.login("nobody", "x"));

        when(userMapper.selectOne(any())).thenReturn(activeUser("correct"));
        BizException wrongPassword = catchBiz(() -> service.login("alice", "wrong"));

        assertThat(unknownUser.errorCode()).isEqualTo(wrongPassword.errorCode());
        assertThat(unknownUser.errorCode().code()).isEqualTo("PLT_BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("被停用的用户不能登录,且错误码与口令错误不同")
    void disabledUserCannotLogin() {
        User disabled = activeUser("s3cret");
        disabled.setStatus("DISABLED");
        when(userMapper.selectOne(any())).thenReturn(disabled);

        // 这里区分是合理的:能通过口令校验说明身份已确认,告诉他账号被停用
        // 不泄露额外信息,反而避免用户反复尝试
        assertThatThrownBy(() -> service.login("alice", "s3cret"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_USER_DISABLED"));
    }

    @Test
    @DisplayName("令牌往返后身份各字段一致")
    void tokenRoundTripPreservesIdentity() {
        User user = activeUser("s3cret");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.selectById("usr_1")).thenReturn(user);
        when(workspaceService.listAccessible("usr_1")).thenReturn(List.of(workspace(WS_A)));

        String token = service.login("alice", "s3cret").token();
        Caller caller = service.parse(token);

        assertThat(caller.userId()).isEqualTo("usr_1");
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.workspaceId()).isEqualTo(WS_A);
        assertThat(caller.platformAdmin()).isFalse();
    }

    @Test
    @DisplayName("权限每次重新解析,不从令牌里读 —— 撤权应立即生效")
    void permissionsAreResolvedFreshNotReadFromToken() {
        User user = activeUser("s3cret");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.selectById("usr_1")).thenReturn(user);
        when(workspaceService.listAccessible("usr_1")).thenReturn(List.of(workspace(WS_A)));

        String token = service.login("alice", "s3cret").token();

        // 签发之后管理员撤销了权限
        when(permissionService.resolvePermissions(anyString(), any())).thenReturn(Set.of());

        // 把权限写进令牌会让"撤权"变成最长要等 8 小时才生效的事
        assertThat(service.parse(token).permissions()).isEmpty();
    }

    @Test
    @DisplayName("伪造或过期的令牌一律拒绝")
    void invalidTokenIsRejected() {
        for (String bad : List.of("not-a-jwt", "a.b.c", "")) {
            assertThatThrownBy(() -> service.parse(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo("PLT_UNAUTHENTICATED"));
        }
    }

    @Test
    @DisplayName("切换到未授权空间被拒")
    void switchingToUnauthorizedWorkspaceIsRejected() {
        Caller caller = new Caller("usr_1", "alice", WS_A, false, Set.of());
        doThrow(new BizException(com.datagov.common.error.ErrorCode.PLT_WORKSPACE_FORBIDDEN))
                .when(workspaceService).requireAccess("usr_1", WS_B);

        // 令牌里带 workspaceId 且由服务端签名背书;切换必须重新校验成员资格,
        // 否则改个请求头就能越权
        assertThatThrownBy(() -> service.switchWorkspace(caller, WS_B))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_WORKSPACE_FORBIDDEN"));
    }

    @Test
    @DisplayName("切换空间后签发的新令牌带上新空间")
    void switchingWorkspaceIssuesNewToken() {
        User user = activeUser("s3cret");
        when(userMapper.selectById("usr_1")).thenReturn(user);
        when(workspaceService.listAccessible("usr_1")).thenReturn(List.of(workspace(WS_B)));

        Caller caller = new Caller("usr_1", "alice", WS_A, false, Set.of());
        LoginResult result = service.switchWorkspace(caller, WS_B);

        assertThat(result.currentWorkspaceId()).isEqualTo(WS_B);
        assertThat(service.parse(result.token()).workspaceId()).isEqualTo(WS_B);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private User activeUser(String rawPassword) {
        User user = new User();
        user.setId("usr_1");
        user.setUsername("alice");
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setDisplayName("Alice");
        user.setStatus("ACTIVE");
        user.setPlatformAdmin(false);
        user.setCreatedAt(Instant.now());
        return user;
    }

    private static WorkspaceView workspace(String id) {
        return new WorkspaceView(id, id, id, null, "ACTIVE", Instant.now(), Instant.now());
    }

    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BizException,但没有");
        } catch (BizException e) {
            return e;
        }
    }
}
