package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.compile.CompileResult;
import com.datagov.control.domain.CronSchedule;
import com.datagov.control.domain.JobDefinitionLifecycle;
import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import com.datagov.control.dto.JobDefinitionDtos.CompileResponse;
import com.datagov.control.dto.JobDefinitionDtos.JobDefinitionView;
import com.datagov.control.dto.JobDefinitionDtos.SchedulePreview;
import com.datagov.control.dto.JobDefinitionDtos.UpsertRequest;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.entity.ControlEntities.JobDefinitionVersion;
import com.datagov.control.mapper.JobDefinitionMapper;
import com.datagov.control.mapper.JobDefinitionVersionMapper;
import com.datagov.metadata.service.RuleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 任务定义的注册与生命周期 —— 序号 9、11-14、18、20、22 共用。
 *
 * <p>所有状态迁移经 {@link JobDefinitionLifecycle} 校验。所有查询按
 * {@code WorkspaceContext.requireWorkspaceId()} 过滤,与 Metadata 同一套规矩。
 */
@Service
public class JobDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(JobDefinitionService.class);

    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };

    private final JobDefinitionMapper jobMapper;
    private final JobDefinitionVersionMapper versionMapper;
    private final CompilerRegistry compilers;
    private final RuleService ruleService;
    private final ObjectMapper objectMapper;

    public JobDefinitionService(JobDefinitionMapper jobMapper,
                                JobDefinitionVersionMapper versionMapper,
                                CompilerRegistry compilers,
                                RuleService ruleService,
                                ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.compilers = compilers;
        this.ruleService = ruleService;
        this.objectMapper = objectMapper;
    }

    // ── 定义 CRUD ───────────────────────────────────────────────────────

    @Transactional
    public JobDefinitionView create(UpsertRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        requireNameAvailable(workspaceId, request.name(), null);

        Instant now = Instant.now();
        JobDefinition definition = new JobDefinition();
        definition.setId(Ids.of("job"));
        definition.setWorkspaceId(workspaceId);
        definition.setStatus(JobDefinitionStatus.DRAFT);
        definition.setVersion(1);
        definition.setCreatedAt(now);
        definition.setCreatedBy(operator);
        definition.setUpdatedAt(now);
        definition.setUpdatedBy(operator);
        definition.setDeleted(false);
        applyRequest(definition, request);

        jobMapper.insert(definition);
        ruleService.adjustReferences(workspaceId, referencedRuleIds(request.config()), +1);
        recordVersion(definition, "CREATED", "新建任务定义", operator);

        log.info("任务定义已创建 workspace={} id={} type={} name={}",
                workspaceId, definition.getId(), definition.getJobType(), definition.getName());
        return toView(definition);
    }

    /**
     * 修改定义。
     *
     * <p>任何修改都<b>把状态打回 DRAFT 并作废物理计划</b>。这看着严格,但替代方案
     * 更糟:让一个改过的定义留在 SCHEDULING 里,意味着下一次触发会用一份从未编译
     * 校验过的配置去跑 —— 而那次触发多半发生在没人盯着的深夜。
     *
     * <p>类型不可改,与数据源同理:换类型等于换了一个东西,config 的结构、编译链、
     * 运行语义全变,而版本历史里会出现"同一个 ID 前后是两种任务"的荒谬记录。
     */
    @Transactional
    public JobDefinitionView update(String id, UpsertRequest request) {
        JobDefinition definition = requireInWorkspace(id);
        String operator = WorkspaceContext.require().userId();

        if (definition.getStatus().isTerminal()) {
            throw new BizException(ErrorCode.CTL_JOB_NOT_RUNNABLE, "已归档的任务定义不可修改");
        }
        if (request.jobType() != definition.getJobType()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "任务类型不可变更,请新建任务。当前 %s,请求 %s"
                            .formatted(definition.getJobType(), request.jobType()));
        }
        requireNameAvailable(definition.getWorkspaceId(), request.name(), id);

        // 引用计数按差量维护:先减旧的再加新的。少了这一步,规则的删除保护
        // 就是个永远不触发的摆设 —— 而摆设比没有更糟,它给人虚假的安全感。
        ruleService.adjustReferences(definition.getWorkspaceId(),
                referencedRuleIds(readConfig(definition)), -1);
        ruleService.adjustReferences(definition.getWorkspaceId(),
                referencedRuleIds(request.config()), +1);

        applyRequest(definition, request);
        definition.setVersion(definition.getVersion() + 1);
        definition.setUpdatedAt(Instant.now());
        definition.setUpdatedBy(operator);

        if (definition.getStatus() != JobDefinitionStatus.DRAFT) {
            transitionTo(definition, JobDefinitionStatus.DRAFT, "UpdateDefinition");
        }
        // 计划过期:planDefVersion 不再等于 version。不清空它是有意的 ——
        // 万一新版本编译不过,旧计划还能告诉运维"上一次能跑的是什么样"。
        jobMapper.updateById(definition);
        recordVersion(definition, "UPDATED", "修改定义,状态重置为草稿", operator);

        return toView(definition);
    }

    @Transactional
    public void delete(String id) {
        JobDefinition definition = requireInWorkspace(id);
        if (definition.getStatus() == JobDefinitionStatus.SCHEDULING) {
            throw new BizException(ErrorCode.CTL_JOB_NOT_RUNNABLE,
                    "调度中的任务不可删除,请先暂停或下线");
        }
        ruleService.adjustReferences(definition.getWorkspaceId(),
                referencedRuleIds(readConfig(definition)), -1);
        jobMapper.deleteById(id);
        log.info("任务定义已删除 workspace={} id={}", definition.getWorkspaceId(), id);
    }

    // ── 编译与发布 ──────────────────────────────────────────────────────

    /**
     * CompileDefinition —— 编译并落库结果。
     *
     * <p>编译<b>不抛异常</b>,失败也返回 200 带诊断列表:失败是编译的正常结果,
     * 而 UI 需要拿到完整诊断才能把错误标在对应的字段上。用 4xx 表达"你的定义有
     * 十七个字段类型不兼容",客户端只会拿到一个笼统的错误提示。
     */
    @Transactional
    public CompileResponse compile(String id) {
        JobDefinition definition = requireInWorkspace(id);
        CompileResult result = compilers.compile(definition, readConfig(definition));

        Instant now = Instant.now();
        definition.setLastCompiledAt(now);
        definition.setLastCompileSucceeded(result.succeeded());
        definition.setLastCompileMessage(result.summary());
        definition.setLastCompileDiagnosticsJson(writeJson(result.diagnostics()));

        if (result.succeeded()) {
            definition.setPhysicalPlanJson(writeJson(result.physicalPlan()));
            definition.setPlanDefVersion(definition.getVersion());
            if (definition.getStatus() == JobDefinitionStatus.DRAFT) {
                transitionTo(definition, JobDefinitionStatus.VALIDATED, "CompileSucceeded");
            }
        }
        definition.setUpdatedAt(now);
        jobMapper.updateById(definition);

        log.info("编译完成 id={} 结果={} {}", id, result.succeeded(), result.summary());
        return CompileResponse.from(result);
    }

    /**
     * PublishDefinition。
     *
     * <p>发布前强制重新编译,而不是信任上一次的 VALIDATED:两次之间源表可能已经
     * 改了字段。发布是这条链上最后一次能拦住问题的地方,省掉这一次编译换来的是
     * 一个在生产上失败的任务。
     */
    @Transactional
    public JobDefinitionView publish(String id) {
        JobDefinition definition = requireInWorkspace(id);
        CompileResponse compileResponse = compile(id);
        if (!compileResponse.succeeded()) {
            throw new BizException(ErrorCode.CTL_COMPILE_FAILED,
                    "编译未通过,无法发布: " + compileResponse.summary());
        }

        definition = requireInWorkspace(id);      // compile 已改动,重新取
        transitionTo(definition, JobDefinitionStatus.PUBLISHED, "PublishDefinition");
        definition.setUpdatedAt(Instant.now());
        definition.setUpdatedBy(WorkspaceContext.require().userId());
        jobMapper.updateById(definition);
        recordVersion(definition, "PUBLISHED", "发布任务定义",
                WorkspaceContext.require().userId());

        log.info("任务定义已发布 id={} version={}", id, definition.getVersion());
        return toView(definition);
    }

    @Transactional
    public JobDefinitionView offline(String id) {
        JobDefinition definition = requireInWorkspace(id);
        transitionTo(definition, JobDefinitionStatus.OFFLINE, "OfflineDefinition");
        clearSchedule(definition);
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
        recordVersion(definition, "STATUS_CHANGED", "下线任务",
                WorkspaceContext.require().userId());
        return toView(definition);
    }

    @Transactional
    public JobDefinitionView archive(String id) {
        JobDefinition definition = requireInWorkspace(id);
        transitionTo(definition, JobDefinitionStatus.ARCHIVED, "ArchiveDefinition");
        clearSchedule(definition);
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
        recordVersion(definition, "STATUS_CHANGED", "归档任务",
                WorkspaceContext.require().userId());
        return toView(definition);
    }

    // ── 调度绑定(功能 16)──────────────────────────────────────────────

    @Transactional
    public JobDefinitionView bindSchedule(String id, String cronExpression,
                                          String timezone, String misfirePolicy) {
        JobDefinition definition = requireInWorkspace(id);

        if (!definition.getJobType().isSchedulable()) {
            throw new BizException(ErrorCode.CTL_JOB_NOT_SCHEDULABLE,
                    "%s 不支持周期调度".formatted(definition.getJobType().displayName()),
                    definition.getJobType().isLongRunning()
                            ? "实时任务是常驻的,启动后一直运行,不需要周期触发"
                            : "整库迁移是一次性任务,请使用「立即执行」");
        }
        if (!definition.getStatus().isSchedulable()) {
            throw new BizException(ErrorCode.CTL_JOB_NOT_RUNNABLE,
                    "任务当前为「%s」,请先发布后再绑定调度"
                            .formatted(definition.getStatus().displayName()));
        }

        CronSchedule schedule = CronSchedule.parse(cronExpression, timezone, misfirePolicy);
        definition.setCronExpression(schedule.expression());
        definition.setCronTimezone(schedule.timezone().getId());
        definition.setMisfirePolicy(schedule.misfirePolicy().name());
        definition.setNextFireAt(schedule.nextFireAfter(Instant.now()));

        if (definition.getStatus() == JobDefinitionStatus.PUBLISHED) {
            transitionTo(definition, JobDefinitionStatus.SCHEDULING, "BindSchedule");
        }
        definition.setUpdatedAt(Instant.now());
        definition.setUpdatedBy(WorkspaceContext.require().userId());
        jobMapper.updateById(definition);

        log.info("已绑定调度 id={} cron={} tz={} 下次触发={}",
                id, schedule.expression(), schedule.timezone(), definition.getNextFireAt());
        return toView(definition);
    }

    @Transactional
    public JobDefinitionView pauseSchedule(String id) {
        JobDefinition definition = requireInWorkspace(id);
        transitionTo(definition, JobDefinitionStatus.PAUSED, "PauseSchedule");
        // 清掉下次触发时刻:留着它会让界面显示一个永远不会到来的时间
        definition.setNextFireAt(null);
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
        return toView(definition);
    }

    @Transactional
    public JobDefinitionView resumeSchedule(String id) {
        JobDefinition definition = requireInWorkspace(id);
        transitionTo(definition, JobDefinitionStatus.SCHEDULING, "ResumeSchedule");
        // 从现在往后算,不补跑暂停期间错过的那些 —— 暂停两天的日任务恢复时
        // 一口气补跑两次,几乎不会是运维想要的
        definition.setNextFireAt(cronOf(definition).nextFireAfter(Instant.now()));
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
        log.info("调度已恢复 id={} 下次触发={}", id, definition.getNextFireAt());
        return toView(definition);
    }

    @Transactional
    public JobDefinitionView unbindSchedule(String id) {
        JobDefinition definition = requireInWorkspace(id);
        transitionTo(definition, JobDefinitionStatus.PUBLISHED, "UnbindSchedule");
        clearSchedule(definition);
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
        return toView(definition);
    }

    /** 调度预览 —— 绑定前先让用户看清楚自己写的 Cron 到底是什么意思。 */
    public SchedulePreview previewSchedule(String cronExpression, String timezone,
                                           String misfirePolicy) {
        CronSchedule schedule = CronSchedule.parse(cronExpression, timezone, misfirePolicy);
        return new SchedulePreview(schedule.expression(), schedule.timezone().getId(),
                schedule.misfirePolicy().name(),
                schedule.nextFireTimes(Instant.now(), 5));
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public PageResult<JobDefinitionView> list(long page, long size, JobType jobType,
                                              JobDefinitionStatus status, String keyword) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<JobDefinition> wrapper = new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .eq(jobType != null, JobDefinition::getJobType, jobType)
                .eq(status != null, JobDefinition::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), JobDefinition::getName, keyword)
                .orderByDesc(JobDefinition::getUpdatedAt);

        Page<JobDefinition> result = jobMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), page, size);
    }

    public JobDefinitionView get(String id) {
        return toView(requireInWorkspace(id));
    }

    /** 完整诊断列表。列表页只显示 summary,详情页才需要逐条。 */
    public List<CompileResult.Diagnostic> diagnostics(String id) {
        JobDefinition definition = requireInWorkspace(id);
        if (definition.getLastCompileDiagnosticsJson() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(definition.getLastCompileDiagnosticsJson(),
                    new TypeReference<List<CompileResult.Diagnostic>>() {
                    });
        } catch (Exception e) {
            log.warn("诊断列表解析失败 id={}", id, e);
            return List.of();
        }
    }

    // ── 供 ScheduleTrigger / JobExecutionService 使用的内部接口 ──────────

    /** 到点该触发的定义。按下次触发时刻升序,先到先跑。 */
    public List<JobDefinition> findDueForFire(Instant now, int limit) {
        return jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getStatus, JobDefinitionStatus.SCHEDULING)
                .isNotNull(JobDefinition::getNextFireAt)
                .le(JobDefinition::getNextFireAt, now)
                .orderByAsc(JobDefinition::getNextFireAt)
                .last("limit " + limit));
    }

    /** 触发后推进 nextFireAt。与触发本身分开,便于触发失败时不推进。 */
    @Transactional
    public void advanceNextFire(JobDefinition definition, Instant firedAt) {
        definition.setLastFireAt(firedAt);
        definition.setNextFireAt(cronOf(definition).nextFireAfter(firedAt));
        definition.setUpdatedAt(Instant.now());
        jobMapper.updateById(definition);
    }

    /** 不带租户校验 —— 只给调度器用,它跑在没有 WorkspaceContext 的线程上。 */
    public JobDefinition loadInternal(String id) {
        JobDefinition definition = jobMapper.selectById(id);
        if (definition == null) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, id);
        }
        return definition;
    }

    /**
     * 从配置里提取被引用的规则 ID(功能 17)。
     *
     * <p>形状是 {@code fieldRules: { 字段名: [ruleId...] }}。这里刻意<b>不</b>按
     * jobType 分支:目前只有离线同步用规则,但把提取逻辑写成通用的,新增
     * 用规则的任务类型时不必回来改引用计数 —— 而漏改引用计数的后果是
     * 一条还在被引用的规则被删掉。
     */
    @SuppressWarnings("unchecked")
    private List<String> referencedRuleIds(Map<String, Object> config) {
        Object raw = config == null ? null : config.get("fieldRules");
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        for (Object value : ((Map<Object, Object>) map).values()) {
            if (value instanceof List<?> list) {
                list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                        .filter(s -> !s.isBlank()).forEach(ids::add);
            }
        }
        return ids;
    }

    public Map<String, Object> readConfig(JobDefinition definition) {
        if (definition.getConfigJson() == null || definition.getConfigJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(definition.getConfigJson(),
                    CONFIG_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "任务配置不是合法的 JSON 对象", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readPhysicalPlan(JobDefinition definition) {
        if (definition.getPhysicalPlanJson() == null || definition.getPhysicalPlanJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(definition.getPhysicalPlanJson(), Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "物理计划解析失败", e.getMessage(), e);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void applyRequest(JobDefinition definition, UpsertRequest request) {
        definition.setName(request.name().trim());
        definition.setJobType(request.jobType());
        definition.setDescription(request.description());
        definition.setConfigJson(writeJson(request.config()));
        definition.setTimeoutMs(request.timeoutMs());
        definition.setRetryMaxAttempts(request.retryMaxAttempts());
        definition.setRetryBackoffSeconds(request.retryBackoffSeconds());

        // 调度参数走 bindSchedule,不在这里改 —— 一次改名请求顺带改掉调度,
        // 是那种事后没人说得清"谁改的"的变更
        if (definition.getCronExpression() == null && request.cronExpression() != null
                && !request.cronExpression().isBlank()) {
            CronSchedule schedule = CronSchedule.parse(request.cronExpression(),
                    request.cronTimezone(), request.misfirePolicy());
            definition.setCronExpression(schedule.expression());
            definition.setCronTimezone(schedule.timezone().getId());
            definition.setMisfirePolicy(schedule.misfirePolicy().name());
        }
    }

    private void clearSchedule(JobDefinition definition) {
        definition.setCronExpression(null);
        definition.setCronTimezone(null);
        definition.setMisfirePolicy(null);
        definition.setNextFireAt(null);
    }

    private CronSchedule cronOf(JobDefinition definition) {
        return CronSchedule.parse(definition.getCronExpression(),
                definition.getCronTimezone(), definition.getMisfirePolicy());
    }

    private void transitionTo(JobDefinition definition, JobDefinitionStatus target, String trigger) {
        JobDefinitionLifecycle.MACHINE.checkTransition(definition.getStatus(), target);
        log.debug("任务定义状态迁移 {} {} -> {} ({})",
                definition.getId(), definition.getStatus(), target, trigger);
        definition.setStatus(target);
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .eq(JobDefinition::getName, name.trim())
                .ne(excludeId != null, JobDefinition::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.CTL_JOB_NAME_DUPLICATED, name);
        }
    }

    private JobDefinition requireInWorkspace(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        JobDefinition definition = jobMapper.selectOne(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getId, id)
                .eq(JobDefinition::getWorkspaceId, workspaceId));
        if (definition == null) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, id);
        }
        return definition;
    }

    private void recordVersion(JobDefinition definition, String changeType,
                               String summary, String operator) {
        JobDefinitionVersion version = new JobDefinitionVersion();
        version.setId(Ids.of("jdv"));
        version.setJobDefinitionId(definition.getId());
        version.setWorkspaceId(definition.getWorkspaceId());
        version.setVersion(definition.getVersion());
        version.setChangeType(changeType);
        version.setChangeSummary(summary);
        version.setChangedAt(Instant.now());
        version.setChangedBy(operator);
        version.setSnapshotJson(writeJson(toView(definition)));
        versionMapper.insert(version);
    }

    private JobDefinitionView toView(JobDefinition definition) {
        return JobDefinitionView.from(definition, readConfig(definition));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化失败", e.getMessage(), e);
        }
    }
}
