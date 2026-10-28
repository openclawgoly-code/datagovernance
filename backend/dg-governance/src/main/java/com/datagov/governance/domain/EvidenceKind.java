package com.datagov.governance.domain;

/**
 * 证据引用的种类。
 *
 * <p>这里列的是<b>可以被寻址</b>的事实来源,不是"任何字符串都可以当证据"。
 * 服务端只能校验结构(kind 白名单、ref 格式),证据是否真的支持结论
 * 无法由平台判定 —— 那条边界在输入侧,见 docs/analysis-report.md §8。
 */
public enum EvidenceKind {

    /** 查询及其结果摘要;ref 形如 {@code <datasourceId>#sha256:<digest>} */
    SQL,
    EXECUTION,
    JOB_DEF,
    CATALOG,
    EVENT,
    AUDIT
}
