package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.AnalysisReportPolicy;
import com.datagov.governance.domain.EvidenceKind;
import com.datagov.governance.domain.ReportConclusion;
import com.datagov.governance.domain.ReportState;
import com.datagov.governance.domain.ReportSubjectType;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReport;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReportVersion;
import com.datagov.governance.event.GovernanceEvents;
import com.datagov.governance.mapper.AnalysisReportMapper;
import com.datagov.governance.mapper.AnalysisReportVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分析报告(P6)。
 *
 * <p>三个写用例,共同守住四条不变量:
 * <ul>
 *   <li><b>不可变</b> —— 没有修改内容的方法;修订 = 新版本</li>
 *   <li><b>幂等</b> —— 首版靠 {@code (workspace_id, correlation_id)} 唯一索引,
 *       重放返回同一份报告;撤回重复调用返回当前视图</li>
 *   <li><b>版本不漂移</b> —— CURRENT / SUPERSEDED 由 {@code latest_version}
 *       派生,不落列;版本号用条件更新 + 一次重试</li>
 *   <li><b>空间隔离</b> —— 所有查询强制 {@code workspace_id};跨空间按 id
 *       读取返回 404,不泄露存在性</li>
 * </ul>
 *
 * <p>事件在写库之后、事务之内发布,与 {@code ExecutionService} 一致。
 * {@code AFTER_COMMIT} 是后续统一改造的事,不在这一期单独破例。
 */
@Service
public class AnalysisReportService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReportService.class);

    private final AnalysisReportMapper reportMapper;
    private final AnalysisReportVersionMapper versionMapper;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public AnalysisReportService(AnalysisReportMapper reportMapper,
                                 AnalysisReportVersionMapper versionMapper,
                                 ApplicationEventPublisher events,
                                 ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.versionMapper = versionMapper;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    // ── 请求 DTO ────────────────────────────────────────────────────────

    public record Subject(String type, String id) {
    }

    public record EvidenceInput(String kind, String ref, Integer maxRows, Boolean truncated) {
    }

    public record ModelInput(String provider, String name, String version, String endpointKind) {
    }

    /**
     * 发布首版。
     *
     * <p>{@code correlationId} 是 workspace 内的幂等键:同一个值重放返回既有报告,
     * 不新建、也不发第二次事件。
     */
    public record PublishRequest(
            Subject subject,
            String title,
            String summary,
            String bodyMd,
            String conclusion,
            String insufficientReason,
            List<EvidenceInput> evidence,
            ModelInput model,
            String sessionRef,
            String correlationId,
            String maxSensitivity,
            Instant generatedAt
    ) {
    }

    /** 发布新版本。subject/title 属于聚合根,不允许在修订里改。 */
    public record PublishVersionRequest(
            String summary,
            String bodyMd,
            String conclusion,
            String insufficientReason,
            List<EvidenceInput> evidence,
            ModelInput model,
            String sessionRef,
            String maxSensitivity,
            Instant generatedAt
    ) {
    }

    // ── 响应 DTO ────────────────────────────────────────────────────────

    /**
     * 列表页的当前版本摘要。
     *
     * <p>刻意<b>不带 bodyMd / evidence / model</b>:列表一页 20 条,每条正文
     * 上限 512 KB,把它们一起拉回来会让一次翻页变成十几 MB。列表只需要
     * "哪一版、结论是什么、摘要说了什么";正文在详情里。
     */
    public record LatestView(
            String id,
            int version,
            Integer supersedesVersion,
            /** CURRENT / SUPERSEDED —— 由 latestVersion 派生,不是一列状态 */
            String status,
            String summary,
            ReportConclusion conclusion,
            String insufficientReason,
            String maxSensitivity,
            String contentDigest,
            Instant generatedAt,
            Instant createdAt
    ) {
    }

    public record VersionView(
            String id,
            int version,
            Integer supersedesVersion,
            /** CURRENT / SUPERSEDED —— 由 latestVersion 派生,不是一列状态 */
            String status,
            String summary,
            String bodyMd,
            ReportConclusion conclusion,
            String insufficientReason,
            List<AnalysisReportPolicy.Evidence> evidence,
            AnalysisReportPolicy.ModelInfo model,
            String sessionRef,
            String maxSensitivity,
            String contentDigest,
            Instant generatedAt,
            String createdBy,
            Instant createdAt
    ) {
    }

    /**
     * 报告视图。
     *
     * @param latest   列表页只需要这一项(摘要与结论),不必把全部正文拉回来
     * @param versions 详情才有值;列表恒为空
     */
    public record ReportView(
            String id,
            ReportSubjectType subjectType,
            String subjectId,
            String title,
            ReportState state,
            int latestVersion,
            String correlationId,
            String createdBy,
            Instant createdAt,
            Instant retractedAt,
            String retractedBy,
            LatestView latest,
            List<VersionView> versions
    ) {
    }

    // ── 发布 ────────────────────────────────────────────────────────────

    @Transactional
    public ReportView publish(PublishRequest request) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        String correlationId = AnalysisReportPolicy.requireCorrelationId(request.correlationId());
        AnalysisReportPolicy.Content content = contentOf(request);
        Instant now = Instant.now();

        AnalysisReport report = new AnalysisReport();
        report.setId(Ids.of("arpt"));
        report.setWorkspaceId(workspaceId);
        report.setSubjectType(content.subjectType());
        report.setSubjectId(content.subjectId());
        report.setTitle(content.title());
        report.setState(ReportState.PUBLISHED);
        report.setLatestVersion(1);
        report.setCorrelationId(correlationId);
        report.setCreatedBy(operator);
        report.setCreatedAt(now);

        if (reportMapper.insertIfAbsent(report) == 0) {
            // 幂等重放:不新建、不发事件,直接把既有报告原样返回。
            // 注意这里不比对内容 —— 契约规定 correlationId 相同即同一次提交;
            // 若允许"同键不同内容",那才是真正的歧义。
            AnalysisReport existing = findByCorrelation(workspaceId, correlationId);
            if (existing == null) {
                throw new BizException(ErrorCode.SYS_CONFLICT,
                        "同一 correlationId 的报告正在写入,请重试");
            }
            return detail(existing);
        }

        AnalysisReportVersion version = newVersion(report, content, 1, null, operator, now);
        versionMapper.insert(version);
        events.publishEvent(new GovernanceEvents.AnalysisReportPublished(
                workspaceId, report.getId(), 1, now));
        log.info("分析报告已发布 workspace={} id={} version=1 correlation={}",
                workspaceId, report.getId(), correlationId);
        return detail(report);
    }

    /**
     * 发布新版本。
     *
     * <p>版本号用条件更新抢占,失败重读后重试一次。两次都抢不到说明有并发写者,
     * 返回 409 让调用方重试 —— 静默覆盖会让某个结论凭空消失。
     */
    @Transactional
    public ReportView publishVersion(String reportId, PublishVersionRequest request) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        AnalysisReport report = requireReport(reportId);
        if (report.getState() == ReportState.RETRACTED) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "报告已撤回,不能再发布新版本");
        }
        // 内容只校验一次:重试是因为版本号被并发推进,不是因为内容有问题。
        // 若每轮重算,generatedAt 缺省时摘要会随重试时刻变化,而 digest 的意义
        // 恰恰是"同一份内容永远同一摘要"。
        AnalysisReportPolicy.Content content = contentOf(report, request);

        for (int attempt = 0; attempt < 2; attempt++) {
            int expected = report.getLatestVersion() == null ? 0 : report.getLatestVersion();
            int next = expected + 1;
            if (reportMapper.compareAndBumpVersion(reportId, workspaceId, expected, next) == 1) {
                Instant now = Instant.now();
                AnalysisReportVersion version = newVersion(report, content, next,
                        expected == 0 ? null : expected, operator, now);
                versionMapper.insert(version);
                report.setLatestVersion(next);
                events.publishEvent(new GovernanceEvents.AnalysisReportPublished(
                        workspaceId, reportId, next, now));
                log.info("分析报告新版本 workspace={} id={} version={}",
                        workspaceId, reportId, next);
                return detail(report);
            }
            log.debug("报告版本并发冲突,重读后重试 report={} expected={}", reportId, expected);
            report = requireReport(reportId);
            if (report.getState() == ReportState.RETRACTED) {
                throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                        "报告已撤回,不能再发布新版本");
            }
        }
        throw new BizException(ErrorCode.SYS_CONFLICT, "报告版本并发更新冲突,请重试");
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public ReportView get(String id) {
        return detail(requireReport(id));
    }

    /**
     * 分页查询。
     *
     * @param from / to        按报告创建时间过滤
     * @param includeRetracted 默认 false:撤回的结论不该出现在默认视野里,
     *                         但必须可查 —— 它是事实,不是被删掉的东西
     */
    public PageResult<ReportView> list(long page, long size, String subjectType, String subjectId,
                                       Instant from, Instant to, Boolean includeRetracted) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        ReportSubjectType type = subjectType == null || subjectType.isBlank()
                ? null
                : parseEnum(ReportSubjectType.class, subjectType, "subjectType");
        String subjectFilter = subjectId == null || subjectId.isBlank() ? null : subjectId.trim();

        LambdaQueryWrapper<AnalysisReport> wrapper = new LambdaQueryWrapper<AnalysisReport>()
                .eq(AnalysisReport::getWorkspaceId, workspaceId)
                .eq(type != null, AnalysisReport::getSubjectType, type)
                .eq(subjectFilter != null, AnalysisReport::getSubjectId, subjectFilter)
                .ge(from != null, AnalysisReport::getCreatedAt, from)
                .le(to != null, AnalysisReport::getCreatedAt, to)
                .ne(!Boolean.TRUE.equals(includeRetracted),
                        AnalysisReport::getState, ReportState.RETRACTED)
                .orderByDesc(AnalysisReport::getCreatedAt);

        Page<AnalysisReport> result = reportMapper.selectPage(Page.of(page, size), wrapper);
        Map<String, LatestView> latest = latestVersions(workspaceId, result.getRecords());
        List<ReportView> views = result.getRecords().stream()
                .map(r -> toView(r, List.of(), latest.get(r.getId())))
                .toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    // ── 撤回 ────────────────────────────────────────────────────────────

    /**
     * 撤回。幂等:已撤回再撤回返回当前视图,不报错 —— 重试是客户端常态。
     */
    @Transactional
    public ReportView retract(String reportId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        AnalysisReport report = requireReport(reportId);
        if (report.getState() == ReportState.RETRACTED) {
            return detail(report);
        }

        Instant now = Instant.now();
        int updated = reportMapper.markRetracted(reportId, workspaceId, now, operator);
        if (updated == 0) {
            // 另一条并发请求刚刚撤回了它。重读一次:只要终态一致就是成功
            AnalysisReport current = requireReport(reportId);
            if (current.getState() != ReportState.RETRACTED) {
                throw new BizException(ErrorCode.SYS_CONFLICT, "报告状态更新冲突,请重试");
            }
            return detail(current);
        }

        report.setState(ReportState.RETRACTED);
        report.setRetractedAt(now);
        report.setRetractedBy(operator);
        events.publishEvent(new GovernanceEvents.AnalysisReportRetracted(
                workspaceId, reportId, now));
        log.info("分析报告已撤回 workspace={} id={} by={}", workspaceId, reportId, operator);
        return detail(report);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private AnalysisReportPolicy.Content contentOf(PublishRequest request) {
        ReportSubjectType subjectType = request.subject() == null
                ? null
                : parseEnum(ReportSubjectType.class, request.subject().type(), "subject.type");
        return AnalysisReportPolicy.validate(new AnalysisReportPolicy.Content(
                subjectType,
                request.subject() == null ? null : request.subject().id(),
                request.title(),
                request.summary(),
                request.bodyMd(),
                parseEnum(ReportConclusion.class, request.conclusion(), "conclusion"),
                request.insufficientReason(),
                evidence(request.evidence()),
                model(request.model()),
                request.sessionRef(),
                request.maxSensitivity(),
                request.generatedAt()));
    }

    private AnalysisReportPolicy.Content contentOf(AnalysisReport report,
                                                    PublishVersionRequest request) {
        return AnalysisReportPolicy.validate(new AnalysisReportPolicy.Content(
                report.getSubjectType(),
                report.getSubjectId(),
                report.getTitle(),
                request.summary(),
                request.bodyMd(),
                parseEnum(ReportConclusion.class, request.conclusion(), "conclusion"),
                request.insufficientReason(),
                evidence(request.evidence()),
                model(request.model()),
                request.sessionRef(),
                request.maxSensitivity(),
                request.generatedAt()));
    }

    private static List<AnalysisReportPolicy.Evidence> evidence(List<EvidenceInput> input) {
        if (input == null) {
            return List.of();
        }
        List<AnalysisReportPolicy.Evidence> result = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            EvidenceInput item = input.get(i);
            if (item == null) {
                throw invalid("evidence[" + i + "] 不能为空");
            }
            EvidenceKind kind = parseEnum(EvidenceKind.class, item.kind(),
                    "evidence[" + i + "].kind");
            result.add(new AnalysisReportPolicy.Evidence(
                    kind, item.ref(), item.maxRows(), item.truncated()));
        }
        return result;
    }

    private static AnalysisReportPolicy.ModelInfo model(ModelInput input) {
        if (input == null) {
            return null;
        }
        return new AnalysisReportPolicy.ModelInfo(
                input.provider(), input.name(), input.version(), input.endpointKind());
    }

    private AnalysisReportVersion newVersion(AnalysisReport report,
                                             AnalysisReportPolicy.Content content,
                                             int version, Integer supersedes,
                                             String operator, Instant now) {
        AnalysisReportVersion entity = new AnalysisReportVersion();
        entity.setId(Ids.of("arptv"));
        entity.setReportId(report.getId());
        entity.setWorkspaceId(report.getWorkspaceId());
        entity.setVersion(version);
        entity.setSupersedesVersion(supersedes);
        entity.setSummary(content.summary());
        entity.setBodyMd(content.bodyMd());
        entity.setConclusion(content.conclusion());
        entity.setInsufficientReason(content.insufficientReason());
        entity.setEvidenceJson(writeJson(content.evidence()));
        entity.setModelJson(writeJson(content.model()));
        entity.setSessionRef(content.sessionRef());
        entity.setMaxSensitivity(content.maxSensitivity());
        entity.setContentDigest(AnalysisReportPolicy.digest(content));
        entity.setGeneratedAt(content.generatedAt());
        entity.setCreatedBy(operator);
        entity.setCreatedAt(now);
        return entity;
    }

    private ReportView detail(AnalysisReport report) {
        List<AnalysisReportVersion> rows = versionMapper.selectList(
                new LambdaQueryWrapper<AnalysisReportVersion>()
                        .eq(AnalysisReportVersion::getWorkspaceId, report.getWorkspaceId())
                        .eq(AnalysisReportVersion::getReportId, report.getId())
                        .orderByAsc(AnalysisReportVersion::getVersion));
        List<VersionView> versions = rows.stream()
                .map(row -> toVersionView(report, row))
                .toList();
        LatestView latest = rows.stream()
                .filter(row -> Objects.equals(row.getVersion(), report.getLatestVersion()))
                .findFirst()
                .map(row -> toLatestView(report, row))
                .orElse(null);
        return toView(report, versions, latest);
    }

    /**
     * 列表页的当前版本。
     *
     * <p>批量查而不是每条报告一次:<b>N+1</b> 在默认 20 条时还不致命,
     * 但翻到 100 条时会变成 101 次查询,而这段时间里连接池是被占住的。
     * 只取 {@code version IN (各报告 latest_version)} 的组合,再按对精确过滤 ——
     * 版本号是每个报告各自递减的,不能用全局最大值代替。
     */
    private Map<String, LatestView> latestVersions(String workspaceId,
                                                   List<AnalysisReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> latestByReport = new HashMap<>();
        Set<Integer> latestNumbers = new HashSet<>();
        for (AnalysisReport report : reports) {
            if (report.getLatestVersion() != null) {
                latestByReport.put(report.getId(), report.getLatestVersion());
                latestNumbers.add(report.getLatestVersion());
            }
        }
        if (latestNumbers.isEmpty()) {
            return Map.of();
        }

        List<AnalysisReportVersion> rows = versionMapper.selectList(
                new LambdaQueryWrapper<AnalysisReportVersion>()
                        .eq(AnalysisReportVersion::getWorkspaceId, workspaceId)
                        .in(AnalysisReportVersion::getReportId, latestByReport.keySet())
                        .in(AnalysisReportVersion::getVersion, latestNumbers));

        Map<String, AnalysisReport> reportsById = new HashMap<>();
        for (AnalysisReport report : reports) {
            reportsById.put(report.getId(), report);
        }
        Map<String, LatestView> result = new HashMap<>();
        for (AnalysisReportVersion row : rows) {
            AnalysisReport report = reportsById.get(row.getReportId());
            if (report != null
                    && Objects.equals(latestByReport.get(row.getReportId()), row.getVersion())) {
                result.put(report.getId(), toLatestView(report, row));
            }
        }
        return result;
    }

    private LatestView toLatestView(AnalysisReport report, AnalysisReportVersion version) {
        return new LatestView(
                version.getId(),
                version.getVersion() == null ? 0 : version.getVersion(),
                version.getSupersedesVersion(),
                Objects.equals(version.getVersion(), report.getLatestVersion())
                        ? "CURRENT" : "SUPERSEDED",
                version.getSummary(),
                version.getConclusion(),
                version.getInsufficientReason(),
                version.getMaxSensitivity(),
                version.getContentDigest(),
                version.getGeneratedAt(),
                version.getCreatedAt());
    }

    private VersionView toVersionView(AnalysisReport report, AnalysisReportVersion version) {
        return new VersionView(
                version.getId(),
                version.getVersion() == null ? 0 : version.getVersion(),
                version.getSupersedesVersion(),
                Objects.equals(version.getVersion(), report.getLatestVersion())
                        ? "CURRENT" : "SUPERSEDED",
                version.getSummary(),
                version.getBodyMd(),
                version.getConclusion(),
                version.getInsufficientReason(),
                readEvidence(version),
                readModel(version),
                version.getSessionRef(),
                version.getMaxSensitivity(),
                version.getContentDigest(),
                version.getGeneratedAt(),
                version.getCreatedBy(),
                version.getCreatedAt());
    }

    private ReportView toView(AnalysisReport report, List<VersionView> versions,
                              LatestView latest) {
        return new ReportView(
                report.getId(),
                report.getSubjectType(),
                report.getSubjectId(),
                report.getTitle(),
                report.getState(),
                report.getLatestVersion() == null ? 0 : report.getLatestVersion(),
                report.getCorrelationId(),
                report.getCreatedBy(),
                report.getCreatedAt(),
                report.getRetractedAt(),
                report.getRetractedBy(),
                latest,
                versions);
    }

    private List<AnalysisReportPolicy.Evidence> readEvidence(AnalysisReportVersion version) {
        try {
            return objectMapper.readValue(version.getEvidenceJson(),
                    new TypeReference<List<AnalysisReportPolicy.Evidence>>() {
                    });
        } catch (Exception e) {
            // 库里存的是写入时自己序列化的 JSON;读不出来说明数据被外部改坏。
            // 不返回空数组冒充"没有证据" —— 那会让一条错误结论显得证据齐全。
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "分析报告证据解析失败", "reportVersion=" + version.getId(), e);
        }
    }

    private AnalysisReportPolicy.ModelInfo readModel(AnalysisReportVersion version) {
        try {
            return objectMapper.readValue(version.getModelJson(),
                    AnalysisReportPolicy.ModelInfo.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "分析报告模型信息解析失败", "reportVersion=" + version.getId(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "分析报告内容序列化失败", null, e);
        }
    }

    private AnalysisReport requireReport(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        AnalysisReport report = reportMapper.selectOne(new LambdaQueryWrapper<AnalysisReport>()
                .eq(AnalysisReport::getId, id)
                .eq(AnalysisReport::getWorkspaceId, workspaceId));
        if (report == null) {
            // 跨空间与不存在给同一个 404:不泄露"这个 id 在别的空间里存在"
            throw BizException.notFound(ErrorCode.GOV_REPORT_NOT_FOUND, id);
        }
        return report;
    }

    private AnalysisReport findByCorrelation(String workspaceId, String correlationId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<AnalysisReport>()
                .eq(AnalysisReport::getWorkspaceId, workspaceId)
                .eq(AnalysisReport::getCorrelationId, correlationId));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("未知的 " + field + ": " + value);
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
    }
}
