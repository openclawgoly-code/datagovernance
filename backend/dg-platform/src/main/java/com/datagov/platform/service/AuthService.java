package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.platform.config.SecurityProperties;
import com.datagov.platform.dto.LoginResult;
import com.datagov.platform.dto.MenuNode;
import com.datagov.platform.dto.SessionView;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 认证与会话(功能 30 的登录部分 + 空间切换)。
 *
 * <p><b>令牌里带 workspaceId</b>,而不是让前端每次请求自由指定空间。
 * 区别很关键:前者的空间归属由服务端签名背书,篡改会导致验签失败;
 * 后者只要改个请求头就能越权。切换空间必须重新签发令牌,
 * 而签发前会校验成员资格。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String CLAIM_USERNAME = "usr";
    private static final String CLAIM_WORKSPACE = "ws";
    private static final String CLAIM_PLATFORM_ADMIN = "adm";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final SecurityProperties properties;
    private final SecretKey signingKey;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       WorkspaceService workspaceService,
                       PermissionService permissionService,
                       SecurityProperties properties) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwt().getSigningKey().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 登录。
     *
     * <p>用户名不存在与口令错误返回<b>同一个</b>错误码,不区分 ——
     * 区分等于提供了一个用户名枚举接口,攻击者可以据此确认哪些账号存在。
     */
    @Transactional
    public LoginResult login(String username, String rawPassword) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));

        if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // 即便用户不存在也要走一遍散列比对是更严谨的做法(防时序侧信道),
            // 但 BCrypt 的耗时本身就在百毫秒量级,这里的差异不足以稳定区分。
            log.warn("登录失败 username={}", username);
            throw new BizException(ErrorCode.PLT_BAD_CREDENTIALS);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.PLT_USER_DISABLED);
        }

        user.setLastLoginAt(Instant.now());
        userMapper.updateById(user);

        List<WorkspaceView> workspaces = workspaceService.listAccessible(user.getId());
        // 只有一个可访问空间时直接选中,省掉一次无意义的选择
        String defaultWorkspaceId = workspaces.size() == 1 ? workspaces.get(0).id() : null;

        Set<String> permissions = permissionService.resolvePermissions(user.getId(), defaultWorkspaceId);
        String token = issueToken(user, defaultWorkspaceId);

        log.info("登录成功 user={} workspaces={} default={}",
                user.getUsername(), workspaces.size(), defaultWorkspaceId);

        return new LoginResult(token, toView(user), workspaces, defaultWorkspaceId,
                permissions, permissionService.buildMenuTree(permissions));
    }

    /** 切换空间 —— 校验成员资格后重新解析权限并签发新令牌。 */
    public LoginResult switchWorkspace(Caller caller, String workspaceId) {
        workspaceService.requireAccess(caller.userId(), workspaceId);

        User user = userMapper.selectById(caller.userId());
        if (user == null) {
            throw new BizException(ErrorCode.PLT_UNAUTHENTICATED);
        }
        Set<String> permissions = permissionService.resolvePermissions(user.getId(), workspaceId);
        String token = issueToken(user, workspaceId);

        return new LoginResult(token, toView(user),
                workspaceService.listAccessible(user.getId()), workspaceId,
                permissions, permissionService.buildMenuTree(permissions));
    }

    /** 当前会话详情,供前端刷新后恢复状态。 */
    public SessionView describeSession(Caller caller) {
        User user = userMapper.selectById(caller.userId());
        if (user == null) {
            throw new BizException(ErrorCode.PLT_UNAUTHENTICATED);
        }
        Set<String> permissions = permissionService.resolvePermissions(
                caller.userId(), caller.workspaceId());
        List<MenuNode> menus = permissionService.buildMenuTree(permissions);
        WorkspaceView current = caller.workspaceId() == null ? null
                : workspaceService.listAccessible(caller.userId()).stream()
                .filter(w -> w.id().equals(caller.workspaceId()))
                .findFirst().orElse(null);

        return new SessionView(toView(user), current,
                workspaceService.listAccessible(caller.userId()), permissions, menus);
    }

    /**
     * 解析令牌还原调用者身份。
     *
     * <p>权限每次都<b>重新解析</b>而不是从令牌里读:角色变更、用户被停用、
     * 空间授权被撤销都应当立即生效,而不是等到令牌过期。把权限写进令牌
     * 会让"撤权"变成一件最长要等 8 小时才生效的事。
     */
    public Caller parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String workspaceId = claims.get(CLAIM_WORKSPACE, String.class);
            boolean platformAdmin = Boolean.TRUE.equals(claims.get(CLAIM_PLATFORM_ADMIN, Boolean.class));

            Set<String> permissions = permissionService.resolvePermissions(userId, workspaceId);
            return new Caller(userId, claims.get(CLAIM_USERNAME, String.class),
                    workspaceId, platformAdmin, permissions);

        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.PLT_UNAUTHENTICATED, "登录已失效,请重新登录");
        }
    }

    private String issueToken(User user, String workspaceId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofHours(properties.getJwt().getTtlHours()));

        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(user.getId())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_WORKSPACE, workspaceId)
                .claim(CLAIM_PLATFORM_ADMIN, Boolean.TRUE.equals(user.getPlatformAdmin()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    static UserView toView(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getPhone(), user.getStatus(),
                Boolean.TRUE.equals(user.getPlatformAdmin()),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
