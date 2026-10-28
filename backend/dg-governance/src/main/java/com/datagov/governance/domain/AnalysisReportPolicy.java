package com.datagov.governance.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 分析报告的发布规则。
 *
 * <p>这里只校验<b>结构事实</b>:证据条数、引用格式、截断与结论的关系、长度上限。
 * 正文里的数字是否真的来自那条 SQL,平台无法判定 —— 把"不许出现原始行"
 * 寄托在服务端正则上必然失守。真正的闸门在输入侧(docs/analysis-report.md §8),
 * 这里不假装能做那件事。
 *
 * <p>纯函数:不读上下文、不碰数据库,因此规则可以单独测,也可以在
 * 未来被别的入口(例如批量导入)复用。
 */
public final class AnalysisReportPolicy {

    public static final int MAX_EVIDENCE = 50;
    public static final int MAX_TITLE = 200;
    public static final int MAX_SUMMARY = 500;
    public static final int MAX_BODY_BYTES = 512 * 1024;
    public static final int MAX_SUBJECT_ID = 64;
    public static final int MAX_SESSION_REF = 128;
    public static final int MAX_CORRELATION_ID = 80;
    public static final int MAX_INSUFFICIENT_REASON = 500;

    private static final Set<String> SENSITIVITIES = Set.of("L1", "L2", "L3", "L4", "UNKNOWN");
    private static final Set<String> ENDPOINT_KINDS = Set.of("SELF_HOSTED", "EXTERNAL");
    private static final Pattern SQL_REF =
            Pattern.compile("^[A-Za-z0-9_-]+#sha256:[0-9a-f]{64}$");

    /**
     * 只用于摘要的规范化序列化。
     *
     * <p>刻意不注入 Spring 的 ObjectMapper:摘要必须与运行配置无关 ——
     * 哪天有人打开 pretty-print 或时间戳序列化,历史 digest 不该跟着变。
     * 传入的对象全部是 Map/List/String/数字/布尔,不涉及时间类型。
     */
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private AnalysisReportPolicy() {
    }

    /** 证据引用。 */
    public record Evidence(EvidenceKind kind, String ref, Integer maxRows, Boolean truncated) {
    }

    /** 产出结论的模型。字段名与契约一致,便于直接序列化进 model_json。 */
    public record ModelInfo(String provider, String name, String version, String endpointKind) {
    }

    /** 已校验、已规范化的报告内容。 */
    public record Content(
            ReportSubjectType subjectType,
            String subjectId,
            String title,
            String summary,
            String bodyMd,
            ReportConclusion conclusion,
            String insufficientReason,
            List<Evidence> evidence,
            ModelInfo model,
            String sessionRef,
            String maxSensitivity,
            Instant generatedAt
    ) {
    }

    /**
     * 校验并规范化报告内容。
     *
     * @return 可直接落库的内容:title/summary 已 trim,subject NONE 的 id 已清空,
     *         maxSensitivity 缺省为 UNKNOWN,generatedAt 缺省为当前时间
     * @throws BizException 400 SYS_VALIDATION_FAILED
     */
    public static Content validate(Content raw) {
        if (raw == null) {
            throw invalid("报告内容不能为空");
        }

        String subjectId = validateSubject(raw.subjectType(), raw.subjectId());
        String title = requireText(raw.title(), "title", MAX_TITLE);
        String summary = requireText(raw.summary(), "summary", MAX_SUMMARY);
        String bodyMd = raw.bodyMd();
        if (bodyMd == null || bodyMd.isBlank()) {
            throw invalid("bodyMd 不能为空", "正文必须自包含,不允许「看附件才知道结论」");
        }
        int bodyBytes = bodyMd.getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes > MAX_BODY_BYTES) {
            throw invalid("bodyMd 超过 512 KB 上限(实际 %d 字节)".formatted(bodyBytes));
        }

        ReportConclusion conclusion = raw.conclusion();
        if (conclusion == null) {
            throw invalid("conclusion 不能为空", "取值: CONFIRMED / INCONCLUSIVE");
        }
        String insufficientReason = blankToNull(raw.insufficientReason());
        if (insufficientReason != null && insufficientReason.length() > MAX_INSUFFICIENT_REASON) {
            throw invalid("insufficientReason 不能超过 %d 字".formatted(MAX_INSUFFICIENT_REASON));
        }
        if (conclusion == ReportConclusion.INCONCLUSIVE && insufficientReason == null) {
            throw invalid("结论为 INCONCLUSIVE 时必须说明证据不足的原因",
                    "「证据不足」是一等终态,不是可以省略说明的失败");
        }

        List<Evidence> evidence = validateEvidence(raw.evidence(), conclusion);
        if (evidence.isEmpty() && conclusion != ReportConclusion.INCONCLUSIVE) {
            throw invalid("证据不能为空",
                    "CONFIRMED 结论至少需要一条证据;若确实无法确认,请用 INCONCLUSIVE 并说明原因");
        }

        ModelInfo model = validateModel(raw.model());
        String sessionRef = blankToNull(raw.sessionRef());
        if (sessionRef != null && sessionRef.length() > MAX_SESSION_REF) {
            throw invalid("sessionRef 不能超过 %d 字".formatted(MAX_SESSION_REF));
        }

        String maxSensitivity = raw.maxSensitivity() == null || raw.maxSensitivity().isBlank()
                ? "UNKNOWN"
                : raw.maxSensitivity().trim().toUpperCase(Locale.ROOT);
        if (!SENSITIVITIES.contains(maxSensitivity)) {
            throw invalid("maxSensitivity 只能是 L1 / L2 / L3 / L4 / UNKNOWN",
                    "写入方声明,服务端只校验取值域");
        }

        Instant generatedAt = raw.generatedAt() == null ? Instant.now() : raw.generatedAt();

        return new Content(raw.subjectType(), subjectId, title, summary, bodyMd, conclusion,
                insufficientReason, List.copyOf(evidence), model, sessionRef, maxSensitivity,
                generatedAt);
    }

    /** 幂等键。它是 workspace 内唯一的,因此长度必须与列定义一致。 */
    public static String requireCorrelationId(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("correlationId 不能为空", "它是幂等键:同一个值重放不会产生新报告");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_CORRELATION_ID) {
            throw invalid("correlationId 不能超过 %d 字".formatted(MAX_CORRELATION_ID));
        }
        return trimmed;
    }

    /**
     * 内容摘要。
     *
     * <p>固定字段顺序的 {@link LinkedHashMap} → Jackson → SHA-256。
     * 不能用 {@code Map.of}:它的迭代顺序不承诺稳定,同一个内容两次计算
     * 可能得到不同 digest,而这个字段的全部意义就是"同一内容必然同一摘要"。
     */
    public static String digest(Content content) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("subjectType", content.subjectType().name());
        canonical.put("subjectId", content.subjectId());
        canonical.put("title", content.title());
        canonical.put("summary", content.summary());
        canonical.put("bodyMd", content.bodyMd());
        canonical.put("conclusion", content.conclusion().name());
        canonical.put("insufficientReason", content.insufficientReason());
        canonical.put("evidence", content.evidence().stream().map(e -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", e.kind().name());
            item.put("ref", e.ref());
            item.put("maxRows", e.maxRows());
            item.put("truncated", e.truncated());
            return item;
        }).toList());
        canonical.put("model", modelMap(content.model()));
        canonical.put("sessionRef", content.sessionRef());
        canonical.put("maxSensitivity", content.maxSensitivity());
        canonical.put("generatedAt", content.generatedAt().toString());

        try {
            byte[] json = CANONICAL.writeValueAsBytes(canonical);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 是 JVM 必备算法;走到这里说明运行环境坏了,不该伪装成 400
            throw new IllegalStateException("分析报告摘要计算失败", e);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static String validateSubject(ReportSubjectType type, String id) {
        if (type == null) {
            throw invalid("subject.type 不能为空",
                    "取值: EXECUTION / ALERT / JOB_DEF / DATASET / NONE");
        }
        String subjectId = blankToNull(id);
        if (type == ReportSubjectType.NONE) {
            if (subjectId != null) {
                throw invalid("subject.type=NONE 时不应指定 subject.id",
                        "NONE 表示纯探索,不指向具体对象");
            }
            return null;
        }
        if (subjectId == null) {
            throw invalid("%s 类型的报告必须指定 subject.id".formatted(type));
        }
        if (subjectId.length() > MAX_SUBJECT_ID) {
            throw invalid("subject.id 不能超过 %d 字".formatted(MAX_SUBJECT_ID));
        }
        return subjectId;
    }

    private static List<Evidence> validateEvidence(List<Evidence> evidence, ReportConclusion conclusion) {
        List<Evidence> input = evidence == null ? List.of() : evidence;
        if (input.size() > MAX_EVIDENCE) {
            throw invalid("证据不能超过 %d 条".formatted(MAX_EVIDENCE));
        }

        List<Evidence> result = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            Evidence item = input.get(i);
            String field = "evidence[" + i + "]";
            if (item == null) {
                throw invalid(field + " 不能为空");
            }
            if (item.kind() == null) {
                throw invalid(field + ".kind 不能为空",
                        "取值: SQL / EXECUTION / JOB_DEF / CATALOG / EVENT / AUDIT");
            }
            String ref = requireText(item.ref(), field + ".ref", 2048);
            if (item.kind() == EvidenceKind.SQL) {
                if (!SQL_REF.matcher(ref).matches()) {
                    throw invalid(field + ".ref 格式错误",
                            "SQL 证据的 ref 应为 <datasourceId>#sha256:<64 位小写十六进制>");
                }
                if (item.maxRows() == null) {
                    throw invalid(field + ".maxRows 不能为空", "SQL 证据必须说明扫描了多少行");
                }
                if (item.maxRows() <= 0) {
                    throw invalid(field + ".maxRows 必须大于 0");
                }
                if (item.truncated() == null) {
                    throw invalid(field + ".truncated 不能为空", "SQL 证据必须声明结果是否被截断");
                }
            }
            if (Boolean.TRUE.equals(item.truncated()) && conclusion == ReportConclusion.CONFIRMED) {
                throw invalid("截断的查询不得支撑 CONFIRMED 结论",
                        "请补全查询,或把结论改为 INCONCLUSIVE 并说明缺口");
            }
            result.add(new Evidence(item.kind(), ref, item.maxRows(), item.truncated()));
        }
        return result;
    }

    private static ModelInfo validateModel(ModelInfo model) {
        if (model == null) {
            throw invalid("model 不能为空", "需要说明产出这条结论的模型");
        }
        String provider = requireText(model.provider(), "model.provider", 100);
        String name = requireText(model.name(), "model.name", 200);
        String version = blankToNull(model.version());
        if (version != null && version.length() > 64) {
            throw invalid("model.version 不能超过 64 字");
        }
        String endpointKind = blankToNull(model.endpointKind());
        if (endpointKind != null) {
            endpointKind = endpointKind.toUpperCase(Locale.ROOT);
            if (!ENDPOINT_KINDS.contains(endpointKind)) {
                throw invalid("model.endpointKind 只能是 SELF_HOSTED / EXTERNAL");
            }
        }
        return new ModelInfo(provider, name, version, endpointKind);
    }

    private static Map<String, Object> modelMap(ModelInfo model) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", model.provider());
        map.put("name", model.name());
        map.put("version", model.version());
        map.put("endpointKind", model.endpointKind());
        return map;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalid("%s 不能超过 %d 字".formatted(field, maxLength));
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
    }

    private static BizException invalid(String message, String detail) {
        return new BizException(ErrorCode.SYS_VALIDATION_FAILED, message, detail);
    }
}
