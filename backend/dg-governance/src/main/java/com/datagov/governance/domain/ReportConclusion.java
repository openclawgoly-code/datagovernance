package com.datagov.governance.domain;

/**
 * 分析结论。
 *
 * <p>只有两个取值,这是刻意的:平台不替分析 Agent 发明"部分成立"之类的档位。
 * 一个结论要么被证据支持,要么承认证据不足 —— 后者是一等终态,
 * 不是失败,也不该被藏起来。
 */
public enum ReportConclusion {

    CONFIRMED("已确认"),

    /** 证据不足。必须给出 insufficientReason,并由 DB CHECK 兜底 */
    INCONCLUSIVE("证据不足");

    private final String displayName;

    ReportConclusion(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
