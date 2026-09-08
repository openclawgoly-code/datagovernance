package com.datagov.app.web;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 审计拦截器(序号 27)。
 *
 * <p><b>在拦截器里做,而不是在每个 Service 里调一行。</b> 后者看起来更精确,
 * 但它的失败模式是致命的:新加一个接口时忘了那一行,而"忘了记审计"这件事
 * 没有任何征兆 —— 直到某次事故排查时发现关键操作没有记录。
 *
 * <p>代价是记录的粒度粗一些:它知道"谁对哪个资源做了什么",但不知道具体改了
 * 哪个字段。那个粒度由各 Service 在需要时自行补充调用 {@link AuditService}。
 *
 * <p>只审计<b>写操作</b>。把 GET 也记下来会让审计表在一周内比执行事实表还大,
 * 而"谁看过什么"是另一个问题(数据权限),不该混在这里。
 */
public class AuditInterceptor implements HandlerInterceptor {

    private final AuditService auditService;

    public AuditInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 路径片段 → 资源类型与归属 Space。
     *
     * <p>顺序有意义:先匹配更长的前缀。用 List 而不是 Map 就是为了保住这个顺序。
     */
    private static final List<Map.Entry<String, String[]>> RESOURCE_MAP = List.of(
            Map.entry("/streaming-jobs", new String[]{"STREAMING_JOB", "CONTROL"}),
            Map.entry("/jobs/catalog", new String[]{"TASK_CATALOG", "CONTROL"}),
            Map.entry("/jobs", new String[]{"JOB", "CONTROL"}),
            Map.entry("/executions", new String[]{"EXECUTION", "RUNTIME"}),
            Map.entry("/executors", new String[]{"EXECUTOR", "RUNTIME"}),
            Map.entry("/artifacts", new String[]{"ARTIFACT", "RUNTIME"}),
            Map.entry("/datasources", new String[]{"DATASOURCE", "METADATA"}),
            Map.entry("/rules", new String[]{"RULE", "METADATA"}),
            Map.entry("/governance/alert-rules", new String[]{"ALERT_RULE", "GOVERNANCE"}),
            Map.entry("/governance/alerts", new String[]{"ALERT", "GOVERNANCE"}),
            Map.entry("/governance/channels", new String[]{"ALERT_CHANNEL", "GOVERNANCE"}),
            Map.entry("/workspaces", new String[]{"WORKSPACE", "PLATFORM"}),
            Map.entry("/users", new String[]{"USER", "PLATFORM"}),
            Map.entry("/roles", new String[]{"ROLE", "PLATFORM"}),
            Map.entry("/credentials", new String[]{"CREDENTIAL", "PLATFORM"}),
            Map.entry("/auth", new String[]{"SESSION", "PLATFORM"}));

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String method = request.getMethod();
        if ("GET".equals(method) || "OPTIONS".equals(method)) {
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return;
        }

        String[] resource = resolveResource(path);
        if (resource == null) {
            return;     // 认不出来的路径不记:宁可漏,不可记一堆无法解释的行
        }

        // 上下文在 AuthInterceptor 的 afterCompletion 里可能已经被清掉,
        // 所以这里对 null 是宽容的 —— 登录失败的请求也要留痕,而那时
        // 本来就没有用户身份
        Caller caller = WorkspaceContext.get();
        boolean succeeded = ex == null && response.getStatus() < 400;

        // 路径里没有 ID 时(创建操作),用 CreatedResourceAdvice 从响应体里
        // 捞出来的那个 —— 否则每条创建审计都只能说"有人建了个任务",
        // 说不出建的是哪个
        String resourceId = resourceIdOf(path);
        if (resourceId == null) {
            Object fromBody = request.getAttribute(CreatedResourceAdvice.ATTR_RESOURCE_ID);
            resourceId = fromBody == null ? null : fromBody.toString();
        }
        Object name = request.getAttribute(CreatedResourceAdvice.ATTR_RESOURCE_NAME);

        AuditEntry entry = new AuditEntry(
                actionOf(method, path),
                resource[0],
                resourceId,
                name == null ? null : name.toString(),
                resource[1],
                succeeded,
                succeeded ? null : String.valueOf(response.getStatus()),
                method + " " + path,
                null);

        auditService.record(entry, clientIp(request),
                caller == null ? null : caller.workspaceId(),
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username());
    }

    private static String[] resolveResource(String path) {
        for (Map.Entry<String, String[]> entry : RESOURCE_MAP) {
            if (path.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 动词。
     *
     * <p>HTTP 方法给出大致分类,再用路径末段修正 —— {@code POST /jobs/{id}/run}
     * 是执行而不是创建,而这个区别在审计里很重要:"谁创建了这个任务"和
     * "谁跑了这个任务"是两个不同的问题。
     */
    private static String actionOf(String method, String path) {
        String last = lastSegment(path);
        if ("run".equals(last) || "start".equals(last) || "stop".equals(last)
                || "test".equals(last) || "cancel".equals(last) || "retry".equals(last)
                || "compile".equals(last) || "publish".equals(last)) {
            return AuditService.EXECUTE;
        }
        if ("login".equals(last)) {
            return AuditService.LOGIN;
        }
        return switch (method) {
            case "POST" -> AuditService.CREATE;
            case "PUT", "PATCH" -> AuditService.UPDATE;
            case "DELETE" -> AuditService.DELETE;
            default -> method;
        };
    }

    /**
     * 从路径里挖出资源 ID。
     *
     * <p>取最后一个带类型前缀的片段(ULID 都是 {@code xxx_01J...} 这个形状)——
     * 这让 {@code /jobs/job_01.../run} 记的是任务 ID 而不是字面量 "run"。
     */
    private static String resourceIdOf(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("[a-z_]{2,10}_[0-9A-HJKMNP-TV-Z]{26}")) {
                return parts[i];
            }
        }
        return null;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? "" : path.substring(slash + 1);
    }

    /**
     * 客户端 IP。
     *
     * <p>优先读 X-Forwarded-For 的第一段 —— 平台通常部署在网关之后,
     * {@code getRemoteAddr()} 拿到的会是网关的地址,那对审计毫无价值。
     * 该头可被伪造,所以生产环境应由网关重写它而不是透传。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
    }
}
