package com.datagov.governance.domain;

/**
 * 报告解释的对象类型。
 *
 * <p>{@link #NONE} 不是"没填",而是"纯探索":有些分析是从一个开放问题开始的,
 * 不对应任何一条既有执行或告警。把这种报告强行挂到一个对象上,
 * 会让"这个任务为什么失败"的查询混进一堆不相关的结论。
 */
public enum ReportSubjectType {

    EXECUTION,
    ALERT,
    JOB_DEF,
    DATASET,

    /** 纯探索,不指向具体对象 */
    NONE
}
