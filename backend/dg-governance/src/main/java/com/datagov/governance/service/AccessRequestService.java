package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.AccessRequestEntities.AccessGrant;
import com.datagov.governance.entity.AccessRequestEntities.AccessRequest;
import com.datagov.governance.mapper.AccessGrantMapper;
import com.datagov.governance.mapper.AccessRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 敏感数据临时访问申请（P2）。
 *
 * <p>授权粒度是 workspace 内的“敏感类别 + 最高级别”，不绑定单个资产。
 * 申请/审批是治理事实；grant 只负责“从现在到 expiresAt，这类级别的敏感列
 * 不再脱敏”。过期用读取时惰性判断，不依赖定时任务。
 */
@Service
public class AccessRequestService {

    private static final Set<String> CATEGORIES =
            Set.of("PII", "FINANCIAL", "MEDICAL", "CREDENTIAL");
    private static final Set<String> LEVELS = Set.of("L1", "L2", "L3", "L4");
    private static final int MAX_DURATION_HOURS = 168;

    private final AccessRequestMapper requestMapper;
    private final AccessGrantMapper grantMapper;

    public AccessRequestService(AccessRequestMapper requestMapper,
                                AccessGrantMapper grantMapper) {
        this.requestMapper = requestMapper;
        this.grantMapper = grantMapper;
    }

    // ── DTO ─────────────────────────────────────────────────────────────

    public record CreateRequest(String category, String maxLevel, String reason,
                                Integer durationHours) {
    }

    public record Decision(String note) {
    }

    public record GrantView(String id, String requestId, String username, String category,
                            String maxLevel, Instant expiresAt, Instant revokedAt,
                            String revokedBy, Instant createdAt, String status,
                            boolean active) {
        static GrantView from(AccessGrant grant) {
            Instant now = Instant.now();
            boolean revoked = grant.getRevokedAt() != null;
            boolean expired = grant.getExpiresAt() != null
                    && !grant.getExpiresAt().isAfter(now);
            String status = revoked ? "REVOKED" : expired ? "EXPIRED" : "ACTIVE";
            return new GrantView(grant.getId(), grant.getRequestId(), grant.getUsername(),
                    grant.getCategory(), grant.getMaxLevel(), grant.getExpiresAt(),
                    grant.getRevokedAt(), grant.getRevokedBy(), grant.getCreatedAt(),
                    status, !revoked && !expired);
        }
    }

    public record RequestView(String id, String requester, String category, String maxLevel,
                              String reason, Integer durationHours, String status,
                              String approver, Instant decidedAt, String decisionNote,
                              Instant createdAt, Instant updatedAt, GrantView grant) {
    }

    /** 查询网关只关心“哪些类别/级别被放行”，不需要申请人信息。 */
    public record GrantCoverage(String id, String category, String maxLevel,
                                Instant expiresAt) {
    }

    // ── 申请 ────────────────────────────────────────────────────────────

    @Transactional
    public RequestView create(CreateRequest request) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (request == null) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_INVALID, "申请内容不能为空");
        }
        String category = normalize(request.category(), CATEGORIES, "敏感类别");
        String level = normalize(request.maxLevel(), LEVELS, "最高级别");
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 10) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_INVALID,
                    "申请原因至少 10 字");
        }
        if (reason.length() > 500) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_INVALID,
                    "申请原因不能超过 500 字");
        }
        Integer hours = request.durationHours();
        if (hours == null || hours < 1 || hours > MAX_DURATION_HOURS) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_INVALID,
                    "时长必须在 1–" + MAX_DURATION_HOURS + " 小时之间");
        }

        Instant now = Instant.now();
        AccessRequest entity = new AccessRequest();
        entity.setId(Ids.of("areq"));
        entity.setWorkspaceId(workspaceId);
        entity.setRequester(caller.username());
        entity.setCategory(category);
        entity.setMaxLevel(level);
        entity.setReason(reason);
        entity.setDurationHours(hours);
        entity.setStatus("PENDING");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        requestMapper.insert(entity);
        return toView(entity, null);
    }

    /**
     * scope=mine 返回本人申请；scope=pending 返回待审批，且调用方必须有
     * approve 权限 —— 只有一个端点时，权限不能只靠 URL 前缀判断。
     */
    public List<RequestView> list(String scope, long page, long size) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String normalized = scope == null || scope.isBlank() ? "mine" : scope.trim();
        LambdaQueryWrapper<AccessRequest> query = new LambdaQueryWrapper<AccessRequest>()
                .eq(AccessRequest::getWorkspaceId, workspaceId)
                .orderByDesc(AccessRequest::getCreatedAt);
        if ("pending".equals(normalized)) {
            if (!caller.hasPermission("metadata:access:approve")) {
                throw new BizException(ErrorCode.PLT_FORBIDDEN,
                        "缺少权限: metadata:access:approve");
            }
            query.eq(AccessRequest::getStatus, "PENDING");
        } else if ("mine".equals(normalized)) {
            query.eq(AccessRequest::getRequester, caller.username());
        } else {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "scope 只能是 mine 或 pending");
        }
        int safeSize = (int) Math.min(Math.max(size, 1), 200);
        int safePage = (int) Math.max(page, 1);
        query.last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        List<AccessRequest> requests = requestMapper.selectList(query);
        Map<String, AccessGrant> grants = grantsByRequest(workspaceId,
                requests.stream().map(AccessRequest::getId).toList());
        return requests.stream()
                .map(request -> toView(request, grants.get(request.getId())))
                .toList();
    }

    @Transactional
    public RequestView approve(String id, Decision decision) {
        Caller caller = requireApprover();
        AccessRequest request = requireRequest(caller.workspaceId(), id);
        if (caller.username().equals(request.getRequester())) {
            throw new BizException(ErrorCode.MTD_ACCESS_SELF_APPROVAL,
                    "不能审批自己的访问申请");
        }
        requirePending(request);
        Instant now = Instant.now();
        request.setStatus("APPROVED");
        request.setApprover(caller.username());
        request.setDecidedAt(now);
        request.setDecisionNote(decision == null ? null : blankToNull(decision.note()));
        request.setUpdatedAt(now);
        requestMapper.updateById(request);

        AccessGrant grant = new AccessGrant();
        grant.setId(Ids.of("agrant"));
        grant.setWorkspaceId(request.getWorkspaceId());
        grant.setRequestId(request.getId());
        grant.setUsername(request.getRequester());
        grant.setCategory(request.getCategory());
        grant.setMaxLevel(request.getMaxLevel());
        grant.setExpiresAt(now.plusSeconds(request.getDurationHours() * 3600L));
        grant.setCreatedAt(now);
        grantMapper.insert(grant);
        return toView(request, grant);
    }

    @Transactional
    public RequestView reject(String id, Decision decision) {
        Caller caller = requireApprover();
        AccessRequest request = requireRequest(caller.workspaceId(), id);
        requirePending(request);
        request.setStatus("REJECTED");
        request.setApprover(caller.username());
        request.setDecidedAt(Instant.now());
        request.setDecisionNote(decision == null ? null : blankToNull(decision.note()));
        request.setUpdatedAt(Instant.now());
        requestMapper.updateById(request);
        return toView(request, null);
    }

    @Transactional
    public RequestView cancel(String id) {
        Caller caller = WorkspaceContext.require();
        AccessRequest request = requireRequest(caller.workspaceId(), id);
        if (!caller.username().equals(request.getRequester())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN,
                    "只能取消自己的访问申请");
        }
        requirePending(request);
        request.setStatus("CANCELLED");
        request.setUpdatedAt(Instant.now());
        requestMapper.updateById(request);
        return toView(request, null);
    }

    // ── Grant ───────────────────────────────────────────────────────────

    public List<GrantView> listGrants() {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<AccessGrant> query = new LambdaQueryWrapper<AccessGrant>()
                .eq(AccessGrant::getWorkspaceId, workspaceId)
                .orderByDesc(AccessGrant::getCreatedAt);
        if (!caller.hasPermission("metadata:access:approve")) {
            query.eq(AccessGrant::getUsername, caller.username());
        }
        return grantMapper.selectList(query).stream().map(GrantView::from).toList();
    }

    @Transactional
    public GrantView revoke(String grantId) {
        Caller caller = requireApprover();
        AccessGrant grant = grantMapper.selectById(grantId);
        if (grant == null || !caller.workspaceId().equals(grant.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_ACCESS_GRANT_NOT_FOUND, grantId);
        }
        if (grant.getRevokedAt() == null) {
            grant.setRevokedAt(Instant.now());
            grant.setRevokedBy(caller.userId());
            grantMapper.updateById(grant);
        }
        return GrantView.from(grant);
    }

    /**
     * Query Gateway 使用：当前用户当前有效的 grant。
     *
     * <p>只做惰性过期，不改数据库；过期 grant 保留为历史。
     */
    public List<GrantCoverage> activeGrants(String workspaceId, String username) {
        if (workspaceId == null || username == null) {
            return List.of();
        }
        return grantMapper.selectList(new LambdaQueryWrapper<AccessGrant>()
                        .eq(AccessGrant::getWorkspaceId, workspaceId)
                        .eq(AccessGrant::getUsername, username)
                        .isNull(AccessGrant::getRevokedAt)
                        .gt(AccessGrant::getExpiresAt, Instant.now())
                        .orderByAsc(AccessGrant::getExpiresAt)).stream()
                .filter(grant -> grant.getRevokedAt() == null
                        && grant.getExpiresAt() != null
                        && grant.getExpiresAt().isAfter(Instant.now()))
                .map(grant -> new GrantCoverage(grant.getId(), grant.getCategory(),
                        grant.getMaxLevel(), grant.getExpiresAt()))
                .toList();
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private Caller requireApprover() {
        Caller caller = WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        if (!caller.hasPermission("metadata:access:approve")) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN,
                    "缺少权限: metadata:access:approve");
        }
        return caller;
    }

    private AccessRequest requireRequest(String workspaceId, String id) {
        AccessRequest request = requestMapper.selectById(id);
        if (request == null || !workspaceId.equals(request.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_ACCESS_REQUEST_NOT_FOUND, id);
        }
        return request;
    }

    private static void requirePending(AccessRequest request) {
        if (!"PENDING".equals(request.getStatus())) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_STATE,
                    "申请当前状态为 %s，只能处理 PENDING".formatted(request.getStatus()));
        }
    }

    private Map<String, AccessGrant> grantsByRequest(String workspaceId, List<String> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        Map<String, AccessGrant> grants = new LinkedHashMap<>();
        for (AccessGrant grant : grantMapper.selectList(new LambdaQueryWrapper<AccessGrant>()
                .eq(AccessGrant::getWorkspaceId, workspaceId)
                .in(AccessGrant::getRequestId, requestIds))) {
            grants.put(grant.getRequestId(), grant);
        }
        return grants;
    }

    private static RequestView toView(AccessRequest request, AccessGrant grant) {
        return new RequestView(request.getId(), request.getRequester(), request.getCategory(),
                request.getMaxLevel(), request.getReason(), request.getDurationHours(),
                request.getStatus(), request.getApprover(), request.getDecidedAt(),
                request.getDecisionNote(), request.getCreatedAt(), request.getUpdatedAt(),
                grant == null ? null : GrantView.from(grant));
    }

    private static String normalize(String value, Set<String> allowed, String field) {
        String normalized = value == null ? null : value.trim().toUpperCase();
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BizException(ErrorCode.MTD_ACCESS_REQUEST_INVALID,
                    field + "只能是 " + allowed);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
