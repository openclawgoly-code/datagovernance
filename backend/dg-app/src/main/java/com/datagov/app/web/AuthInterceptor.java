package com.datagov.app.web;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.config.SecurityProperties;
import com.datagov.platform.service.AuthService;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 认证 + 空间作用域 + 权限判定。
 *
 * <p>三件事放在一个拦截器里,是因为它们必须按固定顺序发生且共享同一份解析结果:
 * <ol>
 *   <li><b>认证</b>:解析 Bearer 令牌,拿到用户身份</li>
 *   <li><b>确定空间</b>:请求头 {@code X-Workspace-Id} 优先于令牌里的空间,
 *       但<b>必须校验成员资格与空间状态</b> —— 校验才是真正的防线,签名只是纵深防御</li>
 *   <li><b>权限</b>:按处理方法上的 {@link RequirePermission} 判定</li>
 * </ol>
 *
 * <p>为什么允许请求头覆盖令牌里的空间:用户可能同时开几个标签页看不同空间。
 * 若强制以令牌为准,切换空间就得刷新所有标签页。安全性不受影响 ——
 * 每个请求都会走一遍 {@link WorkspaceService#requireAccess},包括请求头与
 * 令牌一致的那些。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";

    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final SecurityProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthInterceptor(AuthService authService,
                           WorkspaceService workspaceService,
                           PermissionService permissionService,
                           SecurityProperties properties) {
        this.authService = authService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检不带令牌,放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (isPermitted(request.getRequestURI())) {
            return true;
        }

        Caller caller = authenticate(request);
        caller = applyWorkspaceHeader(caller, request.getHeader(WORKSPACE_HEADER));
        WorkspaceContext.set(caller);

        checkPermission(handler, caller);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理:线程池会复用线程,残留的身份会被下一个请求看到
        WorkspaceContext.clear();
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private boolean isPermitted(String uri) {
        return properties.getPermitPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    private Caller authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BizException(ErrorCode.PLT_UNAUTHENTICATED, "请先登录");
        }
        return authService.parse(header.substring(BEARER_PREFIX.length()).trim());
    }

    /**
     * 确定本次请求的空间,并校验访问权。
     *
     * <p>请求头指定的空间覆盖令牌里的空间;两者一致时也照样校验,<b>不做短路</b>。
     * 早先这里在"头与令牌相同"时直接返回,省下一次查询,但那让令牌成了实际上的
     * 授权凭证:成员资格被撤销、或空间被停用之后,持有旧令牌的人还能继续访问,
     * 直到令牌 8 小时后过期。租户边界必须是<b>实时</b>的,一次带索引的查询换这个
     * 是划算的。
     *
     * <p>覆盖空间时权限必须<b>重新解析</b> —— 同一个用户在不同空间下的权限不同,
     * 沿用令牌里的权限集会让用户带着 A 空间的权限去操作 B 空间。
     */
    private Caller applyWorkspaceHeader(Caller caller, String headerWorkspaceId) {
        boolean overriding = headerWorkspaceId != null && !headerWorkspaceId.isBlank()
                && !headerWorkspaceId.equals(caller.workspaceId());
        String effectiveWorkspaceId = overriding ? headerWorkspaceId : caller.workspaceId();

        // 令牌里也可能没有空间(用户有多个可访问空间时后端不替他选)。
        // 那种请求由各接口自己的 requireWorkspaceId() 拒绝,这里没有可校验的对象。
        if (effectiveWorkspaceId == null || effectiveWorkspaceId.isBlank()) {
            return caller;
        }

        workspaceService.requireAccess(caller.userId(), effectiveWorkspaceId);
        if (!overriding) {
            return caller;
        }
        Set<String> permissions = permissionService.resolvePermissions(
                caller.userId(), effectiveWorkspaceId);
        return caller.withWorkspace(effectiveWorkspaceId, permissions);
    }

    private void checkPermission(Object handler, Caller caller) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        RequirePermission required = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            return;
        }
        if (!caller.hasPermission(required.value())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN,
                    "缺少权限: " + required.value());
        }
    }
}
