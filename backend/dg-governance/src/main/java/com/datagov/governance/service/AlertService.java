package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.AlertLifecycle;
import com.datagov.governance.domain.AlertStatus;
import com.datagov.governance.entity.GovernanceEntities.Alert;
import com.datagov.governance.entity.GovernanceEntities.AlertRule;
import com.datagov.governance.mapper.AlertMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 告警信息(序号 26)与推送。
 *
 * <p>这里实现需求里的「告警频率」——它落在 {@link #shouldSuppress}:同源告警在
 * 抑制窗口内<b>仍然记录,但不推送</b>。
 *
 * <p>"记录但不推送"这个区分是刻意的。做成"窗口内不生成告警"更省事,但那会让
 * 排查时看到的是一条孤零零的告警,而实际上那个任务连续失败了两小时 ——
 * 失败了一次和失败了一百二十次,处置方式完全不同。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertMapper alertMapper;
    private final AlertChannelService channelService;
    private final ObjectMapper objectMapper;

    public AlertService(AlertMapper alertMapper, AlertChannelService channelService,
                        ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.channelService = channelService;
        this.objectMapper = objectMapper;
    }

    /** 告警视图。 */
    public record AlertView(
            String id,
            String ruleId,
            String ruleName,
            AlertStatus status,
            String statusDisplayName,
            String severity,
            String title,
            String content,
            String executionId,
            String jobRefId,
            String jobName,
            Instant triggeredAt,
            Instant notifiedAt,
            /** 被哪一条压住了。顺着它能找到同一轮故障的第一条 */
            String suppressedBy,
            String acknowledgedBy,
            Instant acknowledgedAt,
            Instant resolvedAt,
            String resolveNote,
            String notifyError
    ) {

        static AlertView from(Alert a) {
            return new AlertView(a.getId(), a.getRuleId(), a.getRuleName(), a.getStatus(),
                    a.getStatus() == null ? null : a.getStatus().displayName(),
                    a.getSeverity(), a.getTitle(), a.getContent(), a.getExecutionId(),
                    a.getJobRefId(), a.getJobName(), a.getTriggeredAt(), a.getNotifiedAt(),
                    a.getSuppressedBy(), a.getAcknowledgedBy(), a.getAcknowledgedAt(),
                    a.getResolvedAt(), a.getResolveNote(), a.getNotifyError());
        }
    }

    /** 告警概览。序号 26 的「今日 + 历史」在界面上就是这两个数字加一个列表。 */
    public record AlertSummary(long todayCount, long openCount, long suppressedTodayCount) {
    }

    // ── 触发 ────────────────────────────────────────────────────────────

    /**
     * 触发一条告警。
     *
     * <p>由 {@code AlertEvaluator} 在执行结束事件里调用。<b>不抛异常</b>:
     * 告警推送失败不该让触发它的那次执行也跟着失败 —— 那会把一个下游问题
     * 变成上游故障。
     */
    @Transactional
    public AlertView raise(AlertRule rule, String sourceKey, String title, String content,
                           String severity, String executionId, String jobRefId, String jobName) {
        Instant now = Instant.now();

        Alert alert = new Alert();
        alert.setId(Ids.of("alt"));
        alert.setWorkspaceId(rule.getWorkspaceId());
        alert.setRuleId(rule.getId());
        alert.setRuleName(rule.getName());
        alert.setSourceKey(sourceKey);
        alert.setStatus(AlertStatus.TRIGGERED);
        alert.setExecutionId(executionId);
        alert.setJobRefId(jobRefId);
        alert.setJobName(jobName);
        alert.setTitle(title);
        alert.setContent(content);
        alert.setSeverity(severity == null ? "WARNING" : severity);
        alert.setTriggeredAt(now);
        alert.setCreatedAt(now);
        alertMapper.insert(alert);

        Alert suppressor = findSuppressor(rule, sourceKey, now);
        if (suppressor != null) {
            transition(alert, AlertStatus.SUPPRESSED, "AlertSuppressed");
            alert.setSuppressedBy(suppressor.getId());
            alertMapper.updateById(alert);
            log.debug("告警被抑制 alert={} 压制者={} 窗口={}s",
                    alert.getId(), suppressor.getId(), rule.getSuppressWindowSeconds());
            return AlertView.from(alert);
        }

        notify(alert, rule);
        return AlertView.from(alert);
    }

    /**
     * 同源告警在抑制窗口内是否已经通知过。
     *
     * <p>按 {@code sourceKey} 分组而不是按规则 —— 一个"全部任务失败就告警"的
     * 规则,A 任务的告警不该把 B 任务的失败压住,那是两个不相关的故障。
     */
    private Alert findSuppressor(AlertRule rule, String sourceKey, Instant now) {
        int window = rule.getSuppressWindowSeconds() == null ? 0 : rule.getSuppressWindowSeconds();
        if (window <= 0) {
            return null;
        }
        Instant since = now.minusSeconds(window);
        List<Alert> recent = alertMapper.selectList(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getWorkspaceId, rule.getWorkspaceId())
                .eq(Alert::getSourceKey, sourceKey)
                .eq(Alert::getStatus, AlertStatus.NOTIFIED)
                .ge(Alert::getNotifiedAt, since)
                .orderByDesc(Alert::getNotifiedAt)
                .last("limit 1"));
        return recent.isEmpty() ? null : recent.get(0);
    }

    private void notify(Alert alert, AlertRule rule) {
        List<String> channelIds = readChannelIds(rule);
        if (channelIds.isEmpty()) {
            // 没配渠道不是错误:有些规则就是只想在界面上留一条记录。
            // 直接落 NOTIFIED —— 它确实"已经送达"了它唯一的目的地
            transition(alert, AlertStatus.NOTIFIED, "AlertNotified");
            alert.setNotifiedAt(Instant.now());
            alertMapper.updateById(alert);
            return;
        }

        transition(alert, AlertStatus.NOTIFYING, "NotifyAlert");
        alertMapper.updateById(alert);

        StringBuilder errors = new StringBuilder();
        int succeeded = 0;
        for (String channelId : channelIds) {
            String error = channelService.deliver(channelId, alert.getTitle(), alert.getContent());
            if (error == null) {
                succeeded++;
            } else {
                errors.append(channelId).append(": ").append(error).append("; ");
            }
        }

        Instant now = Instant.now();
        if (succeeded > 0) {
            // 部分成功算成功:至少有人收到了。失败的渠道记在 notifyError 里,
            // 让运维能查出"为什么小王收到了小李没收到"
            transition(alert, AlertStatus.NOTIFIED, "AlertNotified");
            alert.setNotifiedAt(now);
            alert.setNotifyError(errors.isEmpty() ? null : truncate(errors.toString()));
        } else {
            transition(alert, AlertStatus.NOTIFY_FAILED, "AlertNotifyFailed");
            alert.setNotifyError(truncate(errors.toString()));
            log.warn("告警推送全部失败 alert={} error={}", alert.getId(), errors);
        }
        alertMapper.updateById(alert);
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * 分页查询。
     *
     * @param today true 表示只看今天(序号 26 的「今日告警」)
     */
    public PageResult<AlertView> list(long page, long size, AlertStatus status,
                                      String severity, Boolean today) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<Alert> wrapper = new LambdaQueryWrapper<Alert>()
                .eq(Alert::getWorkspaceId, workspaceId)
                .eq(status != null, Alert::getStatus, status)
                .eq(severity != null && !severity.isBlank(), Alert::getSeverity, severity)
                .ge(Boolean.TRUE.equals(today), Alert::getTriggeredAt, todayStart())
                .orderByDesc(Alert::getTriggeredAt);

        Page<Alert> result = alertMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(AlertView::from).toList(),
                result.getTotal(), page, size);
    }

    public AlertView get(String id) {
        return AlertView.from(requireAlert(id));
    }

    public AlertSummary summary() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Instant start = todayStart();
        long today = count(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getWorkspaceId, workspaceId)
                .ge(Alert::getTriggeredAt, start));
        long open = count(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getWorkspaceId, workspaceId)
                .ne(Alert::getStatus, AlertStatus.RESOLVED));
        // 被抑制的数量单独给出来:它告诉值班的人"实际发生的次数远不止你收到的"
        long suppressed = count(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getWorkspaceId, workspaceId)
                .eq(Alert::getStatus, AlertStatus.SUPPRESSED)
                .ge(Alert::getTriggeredAt, start));
        return new AlertSummary(today, open, suppressed);
    }

    // ── 处理 ────────────────────────────────────────────────────────────

    @Transactional
    public AlertView acknowledge(String id) {
        Alert alert = requireAlert(id);
        transition(alert, AlertStatus.ACKNOWLEDGED, "AcknowledgeAlert");
        alert.setAcknowledgedBy(WorkspaceContext.require().userId());
        alert.setAcknowledgedAt(Instant.now());
        alertMapper.updateById(alert);
        return AlertView.from(alert);
    }

    @Transactional
    public AlertView resolve(String id, String note) {
        Alert alert = requireAlert(id);
        transition(alert, AlertStatus.RESOLVED, "ResolveAlert");
        alert.setResolvedAt(Instant.now());
        alert.setResolveNote(note);
        if (alert.getAcknowledgedBy() == null) {
            // 直接关闭的也记一个处理人:半年后回看,"谁关的"和"什么时候关的"
            // 同样重要
            alert.setAcknowledgedBy(WorkspaceContext.require().userId());
        }
        alertMapper.updateById(alert);
        return AlertView.from(alert);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void transition(Alert alert, AlertStatus target, String trigger) {
        AlertLifecycle.MACHINE.checkTransition(alert.getStatus(), target);
        alert.setStatus(target);
    }

    private long count(LambdaQueryWrapper<Alert> wrapper) {
        Long value = alertMapper.selectCount(wrapper);
        return value == null ? 0 : value;
    }

    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private List<String> readChannelIds(AlertRule rule) {
        if (rule.getChannelIdsJson() == null || rule.getChannelIdsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rule.getChannelIdsJson(),
                    new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            log.warn("规则的渠道列表解析失败 rule={}", rule.getId(), e);
            return List.of();
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000) + "…";
    }

    private Alert requireAlert(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Alert alert = alertMapper.selectOne(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getId, id)
                .eq(Alert::getWorkspaceId, workspaceId));
        if (alert == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "告警 " + id);
        }
        return alert;
    }
}
