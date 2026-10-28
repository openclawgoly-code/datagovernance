package com.datagov.governance.domain;

/**
 * 分析报告聚合根的状态。
 *
 * <p>没有 DRAFT:草稿属于调用方(例如 DeepAnalyze 的 sessions 表),
 * 平台只收已发布的结论。没有 DELETE:报告是事实,撤回用状态表达。
 *
 * <p>版本级的 CURRENT / SUPERSEDED <b>不在这里</b> —— 它由
 * {@code latest_version} 派生,见 {@code AnalysisReportService}。
 * 双写两份状态必然漂移,而"哪一版是当前版"只有一个正确答案。
 */
public enum ReportState {

    PUBLISHED("已发布"),

    /** 撤回后仍可查;默认列表过滤,显式 includeRetracted 才出现 */
    RETRACTED("已撤回");

    private final String displayName;

    ReportState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
