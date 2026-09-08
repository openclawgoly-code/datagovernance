package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.AlertRule;
import com.datagov.governance.mapper.AlertRuleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 告警规则(序号 25)。
 *
 * <p>需求给的四个维度全部落在这里:范围、触发方式、通知渠道、告警频率。
 * 前三个是配置,第四个 —— 「告警频率」—— 是 {@code suppressWindowSeconds},
 * 它的语义见 {@link AlertService} 的类注释。
 */
@Service
public class AlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);

    /**
     * 触发方式。
     *
     * <p>五种都直接对应执行事实上的一个可判定条件 —— 没有"数据量异常"之类
     * 需要基线的东西。那类规则要先有历史基线,而基线本身就是一个功能。
     */
    public enum TriggerType {
        EXECUTION_FAILED("执行失败"),
        EXECUTION_TIMEOUT("执行超时"),
        EXECUTION_SLOW("执行耗时超过阈值"),
        EXECUTION_EMPTY("执行成功但没有写入任何数据"),
        DISPATCH_REJECTED("下发被拒(没有可用执行器)");

        private final String displayName;

        TriggerType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** 需要阈值的触发方式。UI 据此决定要不要显示阈值输入框 */
        public boolean needsThreshold() {
            return this == EXECUTION_SLOW;
        }
    }

    private static final Set<String> SCOPES = Set.of("ALL", "SPECIFIC");

    private final AlertRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public AlertRuleService(AlertRuleMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    /** 规则视图。 */
    public record RuleView(
            String id,
            String name,
            String description,
            String triggerType,
            String triggerDisplayName,
            String scope,
            List<String> targetJobIds,
            Long thresholdMs,
            List<String> channelIds,
            int suppressWindowSeconds,
            String status,
            boolean enabled,
            Instant createdAt,
            String createdBy,
            Instant updatedAt
    ) {
    }

    public record UpsertRequest(
            String name,
            String description,
            String triggerType,
            String scope,
            List<String> targetJobIds,
            Long thresholdMs,
            List<String> channelIds,
            Integer suppressWindowSeconds
    ) {
    }

    public List<RuleView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                        .eq(AlertRule::getWorkspaceId, workspaceId)
                        .orderByDesc(AlertRule::getCreatedAt)).stream()
                .map(this::toView)
                .toList();
    }

    public RuleView get(String id) {
        return toView(requireRule(id));
    }

    /** 启用中的、盯着这种触发方式的规则。评估器每次执行结束都会问它。 */
    List<AlertRule> activeRules(String workspaceId, TriggerType triggerType) {
        return ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getWorkspaceId, workspaceId)
                .eq(AlertRule::getTriggerType, triggerType.name())
                .eq(AlertRule::getStatus, "ENABLED"));
    }

    @Transactional
    public RuleView create(UpsertRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        TriggerType trigger = validate(request, workspaceId, null);

        Instant now = Instant.now();
        AlertRule rule = new AlertRule();
        rule.setId(Ids.of("arl"));
        rule.setWorkspaceId(workspaceId);
        applyRequest(rule, request, trigger);
        rule.setStatus("ENABLED");
        rule.setCreatedAt(now);
        rule.setCreatedBy(operator);
        rule.setUpdatedAt(now);
        rule.setUpdatedBy(operator);
        rule.setDeleted(false);
        ruleMapper.insert(rule);

        log.info("告警规则已创建 workspace={} id={} trigger={}", workspaceId, rule.getId(), trigger);
        return toView(rule);
    }

    @Transactional
    public RuleView update(String id, UpsertRequest request) {
        AlertRule rule = requireRule(id);
        TriggerType trigger = validate(request, rule.getWorkspaceId(), id);
        applyRequest(rule, request, trigger);
        rule.setUpdatedAt(Instant.now());
        rule.setUpdatedBy(WorkspaceContext.require().userId());
        ruleMapper.updateById(rule);
        return toView(rule);
    }

    @Transactional
    public RuleView setEnabled(String id, boolean enabled) {
        AlertRule rule = requireRule(id);
        rule.setStatus(enabled ? "ENABLED" : "DISABLED");
        rule.setUpdatedAt(Instant.now());
        rule.setUpdatedBy(WorkspaceContext.require().userId());
        ruleMapper.updateById(rule);
        // 停用一条规则是要留痕的操作:半年后有人问"这个告警为什么没发",
        // 答案往往是有人把规则关了
        log.info("告警规则 {} 已{}", id, enabled ? "启用" : "停用");
        return toView(rule);
    }

    @Transactional
    public void delete(String id) {
        requireRule(id);
        ruleMapper.deleteById(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private TriggerType validate(UpsertRequest request, String workspaceId, String excludeId) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "规则名称不能为空");
        }
        TriggerType trigger;
        try {
            trigger = TriggerType.valueOf(request.triggerType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "未知的触发方式: " + request.triggerType());
        }
        String scope = request.scope() == null ? "ALL" : request.scope();
        if (!SCOPES.contains(scope)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "范围只能是 ALL(全部任务)或 SPECIFIC(指定任务)");
        }
        if ("SPECIFIC".equals(scope)
                && (request.targetJobIds() == null || request.targetJobIds().isEmpty())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "范围选了「指定任务」,但没有选择任何任务");
        }
        if (trigger.needsThreshold()
                && (request.thresholdMs() == null || request.thresholdMs() <= 0)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "「%s」需要设置阈值".formatted(trigger.displayName()));
        }
        Integer window = request.suppressWindowSeconds();
        if (window != null && window < 0) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "告警频率(抑制窗口)不能是负数", "0 表示不抑制,每次触发都推送");
        }
        requireNameAvailable(workspaceId, request.name(), excludeId);
        return trigger;
    }

    private void applyRequest(AlertRule rule, UpsertRequest request, TriggerType trigger) {
        rule.setName(request.name().trim());
        rule.setDescription(request.description());
        rule.setTriggerType(trigger.name());
        rule.setScope(request.scope() == null ? "ALL" : request.scope());
        rule.setTargetJobIdsJson(writeJson(request.targetJobIds()));
        rule.setThresholdMs(request.thresholdMs());
        rule.setChannelIdsJson(writeJson(request.channelIds()));
        // 默认 600 秒:一个每分钟失败一次的任务,十分钟推送一次是能忍受的下限
        rule.setSuppressWindowSeconds(
                request.suppressWindowSeconds() == null ? 600 : request.suppressWindowSeconds());
    }

    private RuleView toView(AlertRule rule) {
        TriggerType trigger = null;
        try {
            trigger = TriggerType.valueOf(rule.getTriggerType());
        } catch (IllegalArgumentException e) {
            // 触发方式是后加的枚举值,老数据可能对不上 —— 显示原值即可
        }
        return new RuleView(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getTriggerType(),
                trigger == null ? rule.getTriggerType() : trigger.displayName(),
                rule.getScope(), readList(rule.getTargetJobIdsJson()), rule.getThresholdMs(),
                readList(rule.getChannelIdsJson()),
                rule.getSuppressWindowSeconds() == null ? 0 : rule.getSuppressWindowSeconds(),
                rule.getStatus(), "ENABLED".equals(rule.getStatus()),
                rule.getCreatedAt(), rule.getCreatedBy(), rule.getUpdatedAt());
    }

    List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "规则序列化失败");
        }
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = ruleMapper.selectCount(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getWorkspaceId, workspaceId)
                .eq(AlertRule::getName, name.trim())
                .ne(excludeId != null, AlertRule::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "规则名称已存在: " + name);
        }
    }

    private AlertRule requireRule(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        AlertRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getId, id)
                .eq(AlertRule::getWorkspaceId, workspaceId));
        if (rule == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "告警规则 " + id);
        }
        return rule;
    }
}
