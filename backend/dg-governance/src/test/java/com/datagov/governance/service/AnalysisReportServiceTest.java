package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.ReportConclusion;
import com.datagov.governance.domain.ReportState;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReport;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReportVersion;
import com.datagov.governance.event.GovernanceEvents;
import com.datagov.governance.mapper.AnalysisReportMapper;
import com.datagov.governance.mapper.AnalysisReportVersionMapper;
import com.datagov.governance.service.AnalysisReportService.EvidenceInput;
import com.datagov.governance.service.AnalysisReportService.ModelInput;
import com.datagov.governance.service.AnalysisReportService.PublishRequest;
import com.datagov.governance.service.AnalysisReportService.PublishVersionRequest;
import com.datagov.governance.service.AnalysisReportService.ReportView;
import com.datagov.governance.service.AnalysisReportService.Subject;
import com.datagov.governance.service.AnalysisReportService.VersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("分析报告服务")
class AnalysisReportServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String OTHER_WORKSPACE = "ws_beta";
    private static final String USER = "usr_1";
    private static final String REPORT_ID = "arpt_01J0000000000000000000000";
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");

    private AnalysisReportMapper reportMapper;
    private AnalysisReportVersionMapper versionMapper;
    private RecordingPublisher publisher;
    private AnalysisReportService service;

    /** 让 selectList 能看到 insert 过的版本,避免每个用例手工拼返回列表 */
    private List<AnalysisReportVersion> storedVersions;

    /**
     * LambdaQueryWrapper 需要 MyBatis-Plus 的 TableInfo 缓存才能把方法引用
     * 翻译成列名。单测里没有 Spring/MyBatis 启动过程,必须手动初始化;
     * 否则对 wrapper 断言的用例会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AnalysisReport.class);
        TableInfoHelper.initTableInfo(assistant, AnalysisReportVersion.class);
    }

    @BeforeEach
    void setUp() {
        reportMapper = mock(AnalysisReportMapper.class);
        versionMapper = mock(AnalysisReportVersionMapper.class);
        publisher = new RecordingPublisher();
        storedVersions = new ArrayList<>();

        service = new AnalysisReportService(reportMapper, versionMapper, publisher,
                new ObjectMapper());

        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(reportMapper.insertIfAbsent(any())).thenReturn(1);
        when(versionMapper.insert(any(AnalysisReportVersion.class))).thenAnswer(invocation -> {
            storedVersions.add(invocation.getArgument(0));
            return 1;
        });
        when(versionMapper.selectList(any())).thenAnswer(
                invocation -> List.copyOf(storedVersions));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("发布首版")
    class Publish {

        @Test
        @DisplayName("最小合法发布:PUBLISHED、version=1、digest 为 64 位 hex")
        void minimalPublish() {
            ReportView view = service.publish(minimalRequest());

            assertThat(view.id()).startsWith("arpt_");
            assertThat(view.state()).isEqualTo(ReportState.PUBLISHED);
            assertThat(view.latestVersion()).isEqualTo(1);
            assertThat(view.createdBy()).isEqualTo(USER);
            assertThat(view.correlationId()).isEqualTo("corr_1");
            assertThat(view.latest().contentDigest()).matches("[0-9a-f]{64}");
            assertThat(view.versions()).hasSize(1);
            assertThat(view.versions().get(0).status()).isEqualTo("CURRENT");

            verify(reportMapper).insertIfAbsent(any());
            verify(versionMapper).insert(any(AnalysisReportVersion.class));
            assertThat(publisher.eventsOfType(GovernanceEvents.AnalysisReportPublished.class))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.reportId()).isEqualTo(view.id());
                        assertThat(event.version()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("同 correlationId 重放:返回同一 id,不新增版本,也不发事件")
        void idempotentReplay() {
            when(reportMapper.insertIfAbsent(any())).thenReturn(0);
            AnalysisReport existing = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            when(reportMapper.selectOne(any())).thenReturn(existing);
            storedVersions.add(versionEntity(existing, 1, "原摘要"));

            ReportView view = service.publish(minimalRequest());

            assertThat(view.id()).isEqualTo(existing.getId());
            assertThat(view.latestVersion()).isEqualTo(1);
            verify(versionMapper, never()).insert(any(AnalysisReportVersion.class));
            assertThat(publisher.eventsOfType(GovernanceEvents.AnalysisReportPublished.class))
                    .isEmpty();
        }

        @Test
        @DisplayName("CONFIRMED 但证据为空 —— 400,且不落库")
        void confirmedRequiresEvidence() {
            assertValidation(() -> service.publish(new PublishRequest(
                    new Subject("EXECUTION", "exec_1"), "标题", "摘要", "正文",
                    "CONFIRMED", null, List.of(), model(), null, "corr_x", "L2", NOW)));

            verify(reportMapper, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("INCONCLUSIVE 没有 insufficientReason —— 400")
        void inconclusiveRequiresReason() {
            assertValidation(() -> service.publish(new PublishRequest(
                    new Subject("EXECUTION", "exec_1"), "标题", "摘要", "正文",
                    "INCONCLUSIVE", null, List.of(), model(), null, "corr_x", "L2", NOW)));
        }

        @Test
        @DisplayName("evidence kind 不在白名单 —— 400")
        void invalidEvidenceKind() {
            assertValidation(() -> service.publish(requestWithEvidence(
                    List.of(new EvidenceInput("RAW_FILE", "x", null, null)))));
        }

        @Test
        @DisplayName("SQL ref 格式错 —— 400")
        void invalidSqlRef() {
            assertValidation(() -> service.publish(requestWithEvidence(
                    List.of(new EvidenceInput("SQL", "ds_1", 500, false)))));
        }

        @Test
        @DisplayName("truncated=true + CONFIRMED —— 400")
        void truncatedCannotConfirm() {
            assertValidation(() -> service.publish(requestWithEvidence(
                    List.of(new EvidenceInput("SQL", "ds_1#sha256:" + DIGEST, 500, true)))));
        }

        @Test
        @DisplayName("bodyMd 超过 512 KB —— 400")
        void bodyTooLarge() {
            assertValidation(() -> service.publish(new PublishRequest(
                    new Subject("EXECUTION", "exec_1"), "标题", "摘要",
                    "x".repeat(512 * 1024 + 1), "CONFIRMED", null,
                    List.of(new EvidenceInput("SQL", "ds_1#sha256:" + DIGEST, 500, false)),
                    model(), null, "corr_x", "L2", NOW)));
        }
    }

    @Nested
    @DisplayName("版本")
    class Versions {

        @Test
        @DisplayName("发布新版本:latestVersion=2,旧版按 version < latest 派生为 SUPERSEDED")
        void publishNewVersion() {
            AnalysisReport report = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            when(reportMapper.selectOne(any())).thenReturn(report);
            when(reportMapper.compareAndBumpVersion(REPORT_ID, WORKSPACE, 1, 2)).thenReturn(1);
            storedVersions.add(versionEntity(report, 1, "旧摘要"));

            ReportView view = service.publishVersion(REPORT_ID, versionRequest());

            assertThat(view.latestVersion()).isEqualTo(2);
            assertThat(view.versions()).extracting(VersionView::version).containsExactly(1, 2);
            assertThat(view.versions()).extracting(VersionView::status)
                    .containsExactly("SUPERSEDED", "CURRENT");
            assertThat(view.latest().summary()).isEqualTo("修订后的摘要");
            assertThat(view.latest().supersedesVersion()).isEqualTo(1);

            assertThat(publisher.eventsOfType(GovernanceEvents.AnalysisReportPublished.class))
                    .singleElement()
                    .satisfies(event -> assertThat(event.version()).isEqualTo(2));
        }

        @Test
        @DisplayName("版本号被并发推进时重读并重试一次")
        void retriesOnConcurrentVersion() {
            AnalysisReport first = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            AnalysisReport second = reportEntity("corr_1", 2, ReportState.PUBLISHED);
            when(reportMapper.selectOne(any())).thenReturn(first, second);
            when(reportMapper.compareAndBumpVersion(REPORT_ID, WORKSPACE, 1, 2)).thenReturn(0);
            when(reportMapper.compareAndBumpVersion(REPORT_ID, WORKSPACE, 2, 3)).thenReturn(1);
            storedVersions.add(versionEntity(first, 1, "v1"));
            storedVersions.add(versionEntity(second, 2, "v2"));

            ReportView view = service.publishVersion(REPORT_ID, versionRequest());

            assertThat(view.latestVersion()).isEqualTo(3);
            assertThat(view.versions()).extracting(VersionView::version)
                    .containsExactly(1, 2, 3);
            verify(reportMapper).compareAndBumpVersion(REPORT_ID, WORKSPACE, 1, 2);
            verify(reportMapper).compareAndBumpVersion(REPORT_ID, WORKSPACE, 2, 3);
        }

        @Test
        @DisplayName("对已撤回报告发版本 —— 409")
        void cannotPublishAfterRetract() {
            AnalysisReport report = reportEntity("corr_1", 1, ReportState.RETRACTED);
            when(reportMapper.selectOne(any())).thenReturn(report);

            assertConflict(() -> service.publishVersion(REPORT_ID, versionRequest()),
                    ErrorCode.SYS_ILLEGAL_STATE_TRANSITION);
            verify(versionMapper, never()).insert(any(AnalysisReportVersion.class));
        }
    }

    @Nested
    @DisplayName("撤回")
    class Retract {

        @Test
        @DisplayName("撤回两次:第二次 200,状态仍 RETRACTED,只发一次事件")
        void idempotentRetract() {
            AnalysisReport report = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            when(reportMapper.selectOne(any())).thenReturn(report);
            when(reportMapper.markRetracted(eq(REPORT_ID), eq(WORKSPACE), any(), eq(USER)))
                    .thenReturn(1);
            storedVersions.add(versionEntity(report, 1, "原摘要"));

            ReportView first = service.retract(REPORT_ID);
            ReportView second = service.retract(REPORT_ID);

            assertThat(first.state()).isEqualTo(ReportState.RETRACTED);
            assertThat(second.state()).isEqualTo(ReportState.RETRACTED);
            assertThat(second.retractedBy()).isEqualTo(USER);
            verify(reportMapper, times(1)).markRetracted(eq(REPORT_ID), eq(WORKSPACE),
                    any(), eq(USER));
            assertThat(publisher.eventsOfType(GovernanceEvents.AnalysisReportRetracted.class))
                    .hasSize(1);
        }

        @Test
        @DisplayName("并发撤回:条件更新 0 行,重读后终态一致就算成功")
        void concurrentRetractStillSucceeds() {
            AnalysisReport report = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            AnalysisReport alreadyRetracted = reportEntity("corr_1", 1, ReportState.RETRACTED);
            when(reportMapper.selectOne(any())).thenReturn(report, alreadyRetracted);
            when(reportMapper.markRetracted(eq(REPORT_ID), eq(WORKSPACE), any(), eq(USER)))
                    .thenReturn(0);
            storedVersions.add(versionEntity(report, 1, "原摘要"));

            ReportView view = service.retract(REPORT_ID);

            assertThat(view.state()).isEqualTo(ReportState.RETRACTED);
            assertThat(publisher.eventsOfType(GovernanceEvents.AnalysisReportRetracted.class))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        @DisplayName("跨空间按 id 读取 —— 404 GOV_REPORT_NOT_FOUND,不泄露存在性")
        void crossWorkspaceIsNotFound() {
            WorkspaceContext.set(new Caller(USER, "alice", OTHER_WORKSPACE, false, Set.of()));
            when(reportMapper.selectOne(any())).thenReturn(null);

            assertNotFound(() -> service.get(REPORT_ID));
        }

        @Test
        @DisplayName("列表默认排除撤回")
        @SuppressWarnings("unchecked")
        void listExcludesRetractedByDefault() {
            AnalysisReport report = reportEntity("corr_1", 1, ReportState.PUBLISHED);
            storedVersions.add(versionEntity(report, 1, "当前摘要"));
            Page<AnalysisReport> page = new Page<>(1, 20);
            page.setRecords(List.of(report));
            page.setTotal(1);

            var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
            when(reportMapper.selectPage(any(), captor.capture())).thenReturn(page);

            PageResult<ReportView> result = service.list(1, 20, null, null,
                    null, null, false);

            assertThat(result.records()).hasSize(1);
            assertThat(result.records().get(0).latest().summary()).isEqualTo("当前摘要");
            LambdaQueryWrapper<AnalysisReport> wrapper =
                    (LambdaQueryWrapper<AnalysisReport>) captor.getValue();
            assertThat(wrapper.getSqlSegment()).contains("state");
            assertThat(wrapper.getParamNameValuePairs().values())
                    .contains(ReportState.RETRACTED);
        }

        @Test
        @DisplayName("includeRetracted=true 时不再加 state 过滤")
        @SuppressWarnings("unchecked")
        void listCanIncludeRetracted() {
            Page<AnalysisReport> page = new Page<>(1, 20);
            page.setRecords(List.of());
            page.setTotal(0);

            var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
            when(reportMapper.selectPage(any(), captor.capture())).thenReturn(page);

            service.list(1, 20, null, null, null, null, true);

            LambdaQueryWrapper<AnalysisReport> wrapper =
                    (LambdaQueryWrapper<AnalysisReport>) captor.getValue();
            assertThat(wrapper.getSqlSegment()).doesNotContain("state");
        }

        @Test
        @DisplayName("详情含全部版本,列表只带当前版本")
        void detailCarriesAllVersions() {
            AnalysisReport report = reportEntity("corr_1", 2, ReportState.PUBLISHED);
            when(reportMapper.selectOne(any())).thenReturn(report);
            storedVersions.add(versionEntity(report, 1, "v1"));
            storedVersions.add(versionEntity(report, 2, "v2"));

            ReportView view = service.get(REPORT_ID);

            assertThat(view.versions()).hasSize(2);
            assertThat(view.latest().version()).isEqualTo(2);
            assertThat(view.versions().get(0).status()).isEqualTo("SUPERSEDED");
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static PublishRequest minimalRequest() {
        return new PublishRequest(
                new Subject("EXECUTION", "exec_1"),
                "订单同步失败根因",
                "源表新增非空列导致写入失败",
                "## 结论\n\n补默认值后恢复",
                "CONFIRMED",
                null,
                List.of(new EvidenceInput("SQL", "ds_1#sha256:" + DIGEST, 500, false)),
                model(),
                "da_1",
                "corr_1",
                "L2",
                NOW);
    }

    private static PublishRequest requestWithEvidence(List<EvidenceInput> evidence) {
        return new PublishRequest(
                new Subject("EXECUTION", "exec_1"), "标题", "摘要", "正文",
                "CONFIRMED", null, evidence, model(), null, "corr_x", "L2", NOW);
    }

    private static PublishVersionRequest versionRequest() {
        return new PublishVersionRequest(
                "修订后的摘要",
                "## 修订\n\n补上了第二次查询",
                "CONFIRMED",
                null,
                List.of(new EvidenceInput("SQL", "ds_1#sha256:" + DIGEST, 500, false)),
                model(),
                "da_2",
                "L2",
                NOW);
    }

    private static ModelInput model() {
        return new ModelInput("deepseek", "DeepSeek-V4.1-Flash", "2026-10", "EXTERNAL");
    }

    private static AnalysisReport reportEntity(String correlationId, int latestVersion,
                                               ReportState state) {
        AnalysisReport report = new AnalysisReport();
        report.setId(REPORT_ID);
        report.setWorkspaceId(WORKSPACE);
        report.setSubjectType(com.datagov.governance.domain.ReportSubjectType.EXECUTION);
        report.setSubjectId("exec_1");
        report.setTitle("订单同步失败根因");
        report.setState(state);
        report.setLatestVersion(latestVersion);
        report.setCorrelationId(correlationId);
        report.setCreatedBy(USER);
        report.setCreatedAt(NOW);
        if (state == ReportState.RETRACTED) {
            report.setRetractedAt(NOW);
            report.setRetractedBy(USER);
        }
        return report;
    }

    private static AnalysisReportVersion versionEntity(AnalysisReport report, int version,
                                                       String summary) {
        AnalysisReportVersion entity = new AnalysisReportVersion();
        entity.setId("arptv_" + version);
        entity.setReportId(report.getId());
        entity.setWorkspaceId(report.getWorkspaceId());
        entity.setVersion(version);
        entity.setSupersedesVersion(version <= 1 ? null : version - 1);
        entity.setSummary(summary);
        entity.setBodyMd("正文 " + version);
        entity.setConclusion(ReportConclusion.CONFIRMED);
        entity.setEvidenceJson("""
                [{"kind":"SQL","ref":"ds_1#sha256:%s","maxRows":500,"truncated":false}]
                """.formatted(DIGEST).trim());
        entity.setModelJson("""
                {"provider":"deepseek","name":"DeepSeek-V4.1-Flash","version":"2026-10","endpointKind":"EXTERNAL"}
                """.trim());
        entity.setSessionRef("da_1");
        entity.setMaxSensitivity("L2");
        entity.setContentDigest("b".repeat(64));
        entity.setGeneratedAt(NOW);
        entity.setCreatedBy(USER);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(ErrorCode.SYS_VALIDATION_FAILED));
    }

    private static void assertConflict(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(expected));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(ErrorCode.GOV_REPORT_NOT_FOUND));
    }

    /** 记录发布过的事件,比 mock 更容易做"发了什么/没发什么"的断言。 */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        <T> List<T> eventsOfType(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
