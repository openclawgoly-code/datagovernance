package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.AuditRecord;
import com.datagov.governance.mapper.AuditRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 审计日志(序号 27)。
 *
 * <p><b>不可变、只追加。</b> 这个类没有 update,也没有 delete —— 一条能被修改
 * 的审计记录不是审计记录。需要"撤销"某条记录时,正确的做法是再追加一条说明,
 * 而不是把原来那条改掉。
 *
 * <p>需求明确要求「跨数据集成与数据开发聚合」——这是架构约束 R4 的第三条证据。
 * 因为所有 Space 的操作都写进同一张 {@code gv_audit_record},"上周谁动过这个
 * 数据源"这个问题才有一个能回答它的地方。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final int MAX_DETAIL = 4000;

    private final AuditRecordMapper auditMapper;

    public AuditService(AuditRecordMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /** 常见的操作动词。做成常量而不是枚举:各 Space 可能有自己的动词 */
    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String EXECUTE = "EXECUTE";
    public static final String LOGIN = "LOGIN";
    public static final String EXPORT = "EXPORT";

    public record AuditView(
            String id,
            String userId,
            String username,
            String clientIp,
            String action,
            String resourceType,
            String resourceId,
            String resourceName,
            String ownerSpace,
            boolean succeeded,
            String errorCode,
            String requestSummary,
            String detail,
            Instant occurredAt
    ) {

        static AuditView from(AuditRecord r) {
            return new AuditView(r.getId(), r.getUserId(), r.getUsername(), r.getClientIp(),
                    r.getAction(), r.getResourceType(), r.getResourceId(), r.getResourceName(),
                    r.getOwnerSpace(), Boolean.TRUE.equals(r.getSucceeded()), r.getErrorCode(),
                    r.getRequestSummary(), r.getDetail(), r.getOccurredAt());
        }
    }

    /** 一次待记录的操作。参数多,所以用建造式的记录而不是十个位置参数。 */
    public record AuditEntry(
            String action,
            String resourceType,
            String resourceId,
            String resourceName,
            String ownerSpace,
            boolean succeeded,
            String errorCode,
            String requestSummary,
            String detail
    ) {

        public static AuditEntry of(String action, String resourceType, String resourceId) {
            return new AuditEntry(action, resourceType, resourceId, null, null, true, null,
                    null, null);
        }

        public AuditEntry withName(String name) {
            return new AuditEntry(action, resourceType, resourceId, name, ownerSpace,
                    succeeded, errorCode, requestSummary, detail);
        }

        public AuditEntry withSpace(String space) {
            return new AuditEntry(action, resourceType, resourceId, resourceName, space,
                    succeeded, errorCode, requestSummary, detail);
        }

        public AuditEntry failed(String code) {
            return new AuditEntry(action, resourceType, resourceId, resourceName, ownerSpace,
                    false, code, requestSummary, detail);
        }

        public AuditEntry withRequest(String summary) {
            return new AuditEntry(action, resourceType, resourceId, resourceName, ownerSpace,
                    succeeded, errorCode, summary, detail);
        }

        public AuditEntry withDetail(String value) {
            return new AuditEntry(action, resourceType, resourceId, resourceName, ownerSpace,
                    succeeded, errorCode, requestSummary, value);
        }
    }

    /**
     * 记一条。
     *
     * <p>跑在<b>独立事务</b>里({@code REQUIRES_NEW}):审计写入失败不该让被审计
     * 的那个操作回滚。反过来也一样 —— 一个失败并回滚的操作,它的审计记录要留下来,
     * 那正是"谁尝试过做什么"这个问题的答案。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry) {
        try {
            var caller = WorkspaceContext.get();
            AuditRecord record = new AuditRecord();
            record.setId(Ids.of("aud"));
            record.setWorkspaceId(caller == null ? null : caller.workspaceId());
            record.setUserId(caller == null ? null : caller.userId());
            record.setUsername(caller == null ? null : caller.username());
            // 这个重载拿不到请求,所以没有客户端 IP —— Web 层那个重载才有
            record.setAction(entry.action());
            record.setResourceType(entry.resourceType());
            record.setResourceId(entry.resourceId());
            record.setResourceName(truncate(entry.resourceName(), 256));
            record.setOwnerSpace(entry.ownerSpace());
            record.setSucceeded(entry.succeeded());
            record.setErrorCode(entry.errorCode());
            record.setRequestSummary(truncate(entry.requestSummary(), 512));
            record.setDetail(truncate(entry.detail(), MAX_DETAIL));
            record.setOccurredAt(Instant.now());
            auditMapper.insert(record);
        } catch (RuntimeException e) {
            // 审计失败绝不能让业务失败。但它必须在日志里留下痕迹 ——
            // 一个静默失效的审计系统比没有审计更危险
            log.error("审计记录写入失败 action={} resource={}/{}",
                    entry.action(), entry.resourceType(), entry.resourceId(), e);
        }
    }

    /** 带客户端 IP 的版本。由 Web 层拦截器调用 —— 只有那里拿得到请求。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry, String clientIp, String workspaceId,
                       String userId, String username) {
        try {
            AuditRecord record = new AuditRecord();
            record.setId(Ids.of("aud"));
            record.setWorkspaceId(workspaceId);
            record.setUserId(userId);
            record.setUsername(username);
            record.setClientIp(clientIp);
            record.setAction(entry.action());
            record.setResourceType(entry.resourceType());
            record.setResourceId(entry.resourceId());
            record.setResourceName(truncate(entry.resourceName(), 256));
            record.setOwnerSpace(entry.ownerSpace());
            record.setSucceeded(entry.succeeded());
            record.setErrorCode(entry.errorCode());
            record.setRequestSummary(truncate(entry.requestSummary(), 512));
            record.setDetail(truncate(entry.detail(), MAX_DETAIL));
            record.setOccurredAt(Instant.now());
            auditMapper.insert(record);
        } catch (RuntimeException e) {
            log.error("审计记录写入失败 action={} resource={}/{}",
                    entry.action(), entry.resourceType(), entry.resourceId(), e);
        }
    }

    /**
     * 检索(序号 27)。
     *
     * <p>五个过滤条件对应五个"我想查什么":谁干的、干了什么、对什么干的、
     * 什么时候、成功没有。
     */
    public PageResult<AuditView> search(long page, long size, String userId, String action,
                                        String resourceType, String resourceId,
                                        Boolean succeeded, Instant from, Instant to) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<AuditRecord> wrapper = new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getWorkspaceId, workspaceId)
                .eq(userId != null && !userId.isBlank(), AuditRecord::getUserId, userId)
                .eq(action != null && !action.isBlank(), AuditRecord::getAction, action)
                .eq(resourceType != null && !resourceType.isBlank(),
                        AuditRecord::getResourceType, resourceType)
                .eq(resourceId != null && !resourceId.isBlank(),
                        AuditRecord::getResourceId, resourceId)
                .eq(succeeded != null, AuditRecord::getSucceeded, succeeded)
                .ge(from != null, AuditRecord::getOccurredAt, from)
                .le(to != null, AuditRecord::getOccurredAt, to)
                .orderByDesc(AuditRecord::getOccurredAt);

        Page<AuditRecord> result = auditMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(AuditView::from).toList(),
                result.getTotal(), page, size);
    }

    /** 可选的动词与资源类型,给 UI 的下拉用 —— 前端不硬编码这些字符串。 */
    public List<String> actions() {
        return Arrays.asList(CREATE, UPDATE, DELETE, EXECUTE, LOGIN, EXPORT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
