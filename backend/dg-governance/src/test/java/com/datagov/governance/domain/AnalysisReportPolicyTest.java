package com.datagov.governance.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("分析报告发布规则")
class AnalysisReportPolicyTest {

    private static final String DIGEST = "a".repeat(64);
    private static final Instant GENERATED_AT = Instant.parse("2026-10-26T09:00:00Z");

    @Nested
    @DisplayName("最小合法发布")
    class Valid {

        @Test
        @DisplayName("CONFIRMED + 一条 SQL 证据即可发布")
        void minimalConfirmedReport() {
            AnalysisReportPolicy.Content content = AnalysisReportPolicy.validate(validContent());

            assertThat(content.subjectType()).isEqualTo(ReportSubjectType.EXECUTION);
            assertThat(content.title()).isEqualTo("订单同步失败根因");
            assertThat(content.maxSensitivity()).isEqualTo("L2");
            assertThat(content.evidence()).hasSize(1);
        }

        @Test
        @DisplayName("INCONCLUSIVE 允许没有证据,但必须说明原因")
        void inconclusiveWithoutEvidence() {
            AnalysisReportPolicy.Content content = AnalysisReportPolicy.validate(
                    content(ReportConclusion.INCONCLUSIVE, "查询被截断,无法确认根因", List.of()));

            assertThat(content.conclusion()).isEqualTo(ReportConclusion.INCONCLUSIVE);
            assertThat(content.insufficientReason()).contains("无法确认");
        }

        @Test
        @DisplayName("NONE 表示纯探索,不带 subject.id")
        void pureExploration() {
            AnalysisReportPolicy.Content content = AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.NONE, null, "探索",
                            "摘要", "正文", ReportConclusion.INCONCLUSIVE, "没有可用证据",
                            List.of(), model(), null, null, GENERATED_AT));

            assertThat(content.subjectType()).isEqualTo(ReportSubjectType.NONE);
            assertThat(content.subjectId()).isNull();
            // 未声明敏感级别时不能留空,否则 DB 的 CHECK 会把它变成 500
            assertThat(content.maxSensitivity()).isEqualTo("UNKNOWN");
        }
    }

    @Nested
    @DisplayName("证据")
    class EvidenceRules {

        @Test
        @DisplayName("CONFIRMED 但证据为空 —— 400")
        void confirmedRequiresEvidence() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, List.of())));
        }

        @Test
        @DisplayName("INCONCLUSIVE 但没有原因 —— 400")
        void inconclusiveRequiresReason() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.INCONCLUSIVE, null, List.of())));
        }

        @Test
        @DisplayName("证据超过 50 条 —— 400")
        void evidenceLimit() {
            List<AnalysisReportPolicy.Evidence> many = new ArrayList<>();
            for (int i = 0; i < AnalysisReportPolicy.MAX_EVIDENCE + 1; i++) {
                many.add(sqlEvidence());
            }
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, many)));
        }

        @Test
        @DisplayName("SQL ref 必须形如 <datasourceId>#sha256:<64 位小写十六进制>")
        void sqlRefMustCarryDigest() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, List.of(
                            new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                                    "ds_1#sha256:XYZ", 500, false)))));
        }

        @Test
        @DisplayName("SQL 证据必须声明 maxRows 与 truncated")
        void sqlMustDeclareRowsAndTruncation() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, List.of(
                            new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                                    "ds_1#sha256:" + DIGEST, null, false)))));
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, List.of(
                            new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                                    "ds_1#sha256:" + DIGEST, 500, null)))));
        }

        @Test
        @DisplayName("截断的查询不得支撑 CONFIRMED —— 400")
        void truncatedQueryCannotConfirm() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    content(ReportConclusion.CONFIRMED, null, List.of(
                            new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                                    "ds_1#sha256:" + DIGEST, 500, true)))));
        }

        @Test
        @DisplayName("截断可以支撑 INCONCLUSIVE")
        void truncatedQueryCanBeInconclusive() {
            AnalysisReportPolicy.Content content = AnalysisReportPolicy.validate(
                    content(ReportConclusion.INCONCLUSIVE, "结果被截断", List.of(
                            new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                                    "ds_1#sha256:" + DIGEST, 500, true))));

            assertThat(content.evidence().get(0).truncated()).isTrue();
        }
    }

    @Nested
    @DisplayName("长度与取值域")
    class Bounds {

        @Test
        @DisplayName("bodyMd 超过 512 KB —— 400")
        void bodyTooLarge() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "标题", "摘要", "x".repeat(AnalysisReportPolicy.MAX_BODY_BYTES + 1),
                            ReportConclusion.CONFIRMED, null, List.of(sqlEvidence()),
                            model(), null, "L1", GENERATED_AT)));
        }

        @Test
        @DisplayName("title 200 / summary 500 是硬上限")
        void titleAndSummaryLimits() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "标".repeat(201), "摘要", "正文", ReportConclusion.CONFIRMED, null,
                            List.of(sqlEvidence()), model(), null, "L1", GENERATED_AT)));
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "标题", "摘".repeat(501), "正文", ReportConclusion.CONFIRMED, null,
                            List.of(sqlEvidence()), model(), null, "L1", GENERATED_AT)));
        }

        @Test
        @DisplayName("maxSensitivity 只能是 L1..L4 / UNKNOWN")
        void sensitivityDomain() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "标题", "摘要", "正文", ReportConclusion.CONFIRMED, null,
                            List.of(sqlEvidence()), model(), null, "L9", GENERATED_AT)));
        }

        @Test
        @DisplayName("NONE 带上 subject.id 会被拒,避免把探索硬挂到对象上")
        void noneSubjectRejectsId() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.NONE, "exec_1",
                            "标题", "摘要", "正文", ReportConclusion.INCONCLUSIVE, "没有证据",
                            List.of(), model(), null, "L1", GENERATED_AT)));
        }

        @Test
        @DisplayName("model 不能为空 —— 不知道是谁得出的结论,报告就不可复现")
        void modelRequired() {
            assertValidation(() -> AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "标题", "摘要", "正文", ReportConclusion.CONFIRMED, null,
                            List.of(sqlEvidence()), null, null, "L1", GENERATED_AT)));
        }
    }

    @Nested
    @DisplayName("content_digest")
    class Digest {

        @Test
        @DisplayName("同一内容两次计算相等,且是 64 位 hex")
        void stable() {
            String first = AnalysisReportPolicy.digest(
                    AnalysisReportPolicy.validate(validContent()));
            String second = AnalysisReportPolicy.digest(
                    AnalysisReportPolicy.validate(validContent()));

            assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("改一个字段 digest 就变")
        void changesWithContent() {
            String base = AnalysisReportPolicy.digest(
                    AnalysisReportPolicy.validate(validContent()));

            AnalysisReportPolicy.Content changed = AnalysisReportPolicy.validate(
                    new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                            "订单同步失败根因", "改过的摘要", "正文", ReportConclusion.CONFIRMED,
                            null, List.of(sqlEvidence()), model(), "da_1", "L2", GENERATED_AT));

            assertThat(AnalysisReportPolicy.digest(changed)).isNotEqualTo(base);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static AnalysisReportPolicy.Content validContent() {
        return content(ReportConclusion.CONFIRMED, null, List.of(sqlEvidence()));
    }

    private static AnalysisReportPolicy.Content content(
            ReportConclusion conclusion, String reason,
            List<AnalysisReportPolicy.Evidence> evidence) {
        return new AnalysisReportPolicy.Content(ReportSubjectType.EXECUTION, "exec_1",
                "订单同步失败根因", "源表新增非空列导致写入失败", "## 结论\n\n补默认值后恢复",
                conclusion, reason, evidence, model(), "da_1", "L2", GENERATED_AT);
    }

    private static AnalysisReportPolicy.Evidence sqlEvidence() {
        return new AnalysisReportPolicy.Evidence(EvidenceKind.SQL,
                "ds_1#sha256:" + DIGEST, 500, false);
    }

    private static AnalysisReportPolicy.ModelInfo model() {
        return new AnalysisReportPolicy.ModelInfo(
                "deepseek", "DeepSeek-V4.1-Flash", "2026-10", "EXTERNAL");
    }

    private static void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(ErrorCode.SYS_VALIDATION_FAILED));
    }
}
