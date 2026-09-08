package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.governance.domain.AlertStatus;
import com.datagov.governance.service.AlertChannelService;
import com.datagov.governance.service.AlertChannelService.ChannelView;
import com.datagov.governance.service.AlertRuleService;
import com.datagov.governance.service.AlertRuleService.RuleView;
import com.datagov.governance.service.AlertRuleService.TriggerType;
import com.datagov.governance.service.AlertRuleService.UpsertRequest;
import com.datagov.governance.service.AlertService;
import com.datagov.governance.service.AlertService.AlertSummary;
import com.datagov.governance.service.AlertService.AlertView;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditView;
import com.datagov.governance.service.MonitorService;
import com.datagov.governance.service.MonitorService.MonitorDashboard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Governance Space 的对外接口 —— 监控(24)、告警规则(25)、告警信息(26)、
 * 审计日志(27)、告警渠道(33)。
 *
 * <p>五个功能一个控制器,因为它们共享同一个前缀与同一套权限模型;各自的
 * 业务逻辑仍然分在五个 Service 里。控制器只是路由,不该按功能数量拆。
 */
@RestController
@RequestMapping("/api/v1/governance")
@Tag(name = "治理", description = "任务监控、告警规则与信息、审计日志、告警渠道")
public class GovernanceController {

    private final MonitorService monitorService;
    private final AlertRuleService ruleService;
    private final AlertService alertService;
    private final AlertChannelService channelService;
    private final AuditService auditService;

    public GovernanceController(MonitorService monitorService, AlertRuleService ruleService,
                                AlertService alertService, AlertChannelService channelService,
                                AuditService auditService) {
        this.monitorService = monitorService;
        this.ruleService = ruleService;
        this.alertService = alertService;
        this.channelService = channelService;
        this.auditService = auditService;
    }

    // ── 任务监控(序号 24)──────────────────────────────────────────────

    /**
     * 监控面板。
     *
     * <p>五个口径一次返回:执行总数 / 失败数 / 今日新增抽取 / 总计抽取 / 任务时延。
     * 拆成五个接口会让面板发五次请求,而它们本来就是同一次聚合算出来的。
     */
    @GetMapping("/monitor")
    @RequirePermission("governance:monitor:read")
    @Operation(summary = "任务监控面板",
            description = "跨数据集成与数据开发聚合 —— 因为执行事实只有一张表(架构约束 R4)")
    public ApiResponse<MonitorDashboard> monitor(
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(monitorService.dashboard(days));
    }

    // ── 告警规则(序号 25)──────────────────────────────────────────────

    /** 触发方式元数据。UI 的下拉与"要不要显示阈值输入框"都读它。 */
    @GetMapping("/alert-rules/trigger-types")
    @Operation(summary = "支持的触发方式")
    public ApiResponse<List<TriggerInfo>> triggerTypes() {
        return ApiResponse.ok(Arrays.stream(TriggerType.values())
                .map(t -> new TriggerInfo(t.name(), t.displayName(), t.needsThreshold()))
                .toList());
    }

    public record TriggerInfo(String type, String displayName, boolean needsThreshold) {
    }

    @GetMapping("/alert-rules")
    @RequirePermission("governance:rule:read")
    @Operation(summary = "告警规则列表")
    public ApiResponse<List<RuleView>> listRules() {
        return ApiResponse.ok(ruleService.list());
    }

    @GetMapping("/alert-rules/{id}")
    @RequirePermission("governance:rule:read")
    @Operation(summary = "查询单条告警规则")
    public ApiResponse<RuleView> getRule(@PathVariable String id) {
        return ApiResponse.ok(ruleService.get(id));
    }

    @PostMapping("/alert-rules")
    @RequirePermission("governance:rule:manage")
    @Operation(summary = "新建告警规则",
            description = "「告警频率」= suppressWindowSeconds:窗口内的同源告警仍记录但不推送")
    public ApiResponse<RuleView> createRule(@RequestBody UpsertRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @PutMapping("/alert-rules/{id}")
    @RequirePermission("governance:rule:manage")
    @Operation(summary = "编辑告警规则")
    public ApiResponse<RuleView> updateRule(@PathVariable String id,
                                            @RequestBody UpsertRequest request) {
        return ApiResponse.ok(ruleService.update(id, request));
    }

    @PostMapping("/alert-rules/{id}/enable")
    @RequirePermission("governance:rule:manage")
    @Operation(summary = "启用规则")
    public ApiResponse<RuleView> enableRule(@PathVariable String id) {
        return ApiResponse.ok(ruleService.setEnabled(id, true));
    }

    @PostMapping("/alert-rules/{id}/disable")
    @RequirePermission("governance:rule:manage")
    @Operation(summary = "停用规则", description = "停用只是不再触发,已有的告警不受影响")
    public ApiResponse<RuleView> disableRule(@PathVariable String id) {
        return ApiResponse.ok(ruleService.setEnabled(id, false));
    }

    @DeleteMapping("/alert-rules/{id}")
    @RequirePermission("governance:rule:manage")
    @Operation(summary = "删除告警规则")
    public ApiResponse<Void> deleteRule(@PathVariable String id) {
        ruleService.delete(id);
        return ApiResponse.ok();
    }

    // ── 告警信息(序号 26)──────────────────────────────────────────────

    @GetMapping("/alerts/summary")
    @RequirePermission("governance:alert:read")
    @Operation(summary = "告警概览",
            description = "今日数 / 未关闭数 / 今日被抑制数。第三个数字告诉值班的人「实际发生次数远不止你收到的」")
    public ApiResponse<AlertSummary> alertSummary() {
        return ApiResponse.ok(alertService.summary());
    }

    @GetMapping("/alerts")
    @RequirePermission("governance:alert:read")
    @Operation(summary = "告警列表", description = "today=true 即序号 26 的「今日告警」")
    public ApiResponse<PageResult<AlertView>> listAlerts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean today) {
        return ApiResponse.ok(alertService.list(page, size, status, severity, today));
    }

    @GetMapping("/alerts/{id}")
    @RequirePermission("governance:alert:read")
    @Operation(summary = "告警详情")
    public ApiResponse<AlertView> getAlert(@PathVariable String id) {
        return ApiResponse.ok(alertService.get(id));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @RequirePermission("governance:alert:handle")
    @Operation(summary = "认领告警")
    public ApiResponse<AlertView> acknowledgeAlert(@PathVariable String id) {
        return ApiResponse.ok(alertService.acknowledge(id));
    }

    public record ResolveRequest(String note) {
    }

    @PostMapping("/alerts/{id}/resolve")
    @RequirePermission("governance:alert:handle")
    @Operation(summary = "关闭告警")
    public ApiResponse<AlertView> resolveAlert(@PathVariable String id,
                                               @RequestBody(required = false) ResolveRequest request) {
        return ApiResponse.ok(alertService.resolve(id, request == null ? null : request.note()));
    }

    // ── 告警渠道(序号 33)──────────────────────────────────────────────

    public record ChannelRequest(String name, String type, Map<String, Object> config) {
    }

    @GetMapping("/channels")
    @RequirePermission("governance:channel:read")
    @Operation(summary = "告警渠道列表")
    public ApiResponse<List<ChannelView>> listChannels() {
        return ApiResponse.ok(channelService.list());
    }

    @PostMapping("/channels")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "新建告警渠道")
    public ApiResponse<ChannelView> createChannel(@RequestBody ChannelRequest request) {
        return ApiResponse.ok(
                channelService.create(request.name(), request.type(), request.config()));
    }

    @PutMapping("/channels/{id}")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "编辑告警渠道", description = "改过配置后上一次的测试结果作废")
    public ApiResponse<ChannelView> updateChannel(@PathVariable String id,
                                                  @RequestBody ChannelRequest request) {
        return ApiResponse.ok(channelService.update(id, request.name(), request.config()));
    }

    @PostMapping("/channels/{id}/test")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "连通性测试",
            description = "真的发一条测试消息出去,而不是检查配置格式 —— 后者能过而前者失败的情况太常见")
    public ApiResponse<AlertChannelService.TestResult> testChannel(@PathVariable String id) {
        return ApiResponse.ok(channelService.test(id));
    }

    @PostMapping("/channels/{id}/enable")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "启用渠道")
    public ApiResponse<ChannelView> enableChannel(@PathVariable String id) {
        return ApiResponse.ok(channelService.setEnabled(id, true));
    }

    @PostMapping("/channels/{id}/disable")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "停用渠道")
    public ApiResponse<ChannelView> disableChannel(@PathVariable String id) {
        return ApiResponse.ok(channelService.setEnabled(id, false));
    }

    @DeleteMapping("/channels/{id}")
    @RequirePermission("governance:channel:manage")
    @Operation(summary = "删除告警渠道")
    public ApiResponse<Void> deleteChannel(@PathVariable String id) {
        channelService.delete(id);
        return ApiResponse.ok();
    }

    // ── 审计日志(序号 27)──────────────────────────────────────────────

    @GetMapping("/audit/actions")
    @Operation(summary = "可选的操作类型")
    public ApiResponse<List<String>> auditActions() {
        return ApiResponse.ok(auditService.actions());
    }

    @GetMapping("/audit")
    @RequirePermission("governance:audit:read")
    @Operation(summary = "检索审计日志",
            description = "审计记录不可变、只追加 —— 没有编辑与删除接口")
    public ApiResponse<PageResult<AuditView>> searchAudit(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) Boolean succeeded,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.ok(auditService.search(page, size, userId, action, resourceType,
                resourceId, succeeded, from, to));
    }
}
