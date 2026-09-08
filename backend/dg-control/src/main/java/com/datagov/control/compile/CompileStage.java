package com.datagov.control.compile;

/**
 * 编译链的各步,顺序即执行顺序(Contract 的 {@code compile_chain})。
 *
 * <p>Contract 的 note 里有一句值得反复读:「需求清单里没有任何条目直接对应编译、
 * 依赖解析、条件求值、资源选择。它们隐藏在『支持 Cron 表达式的周期调度策略配置』
 * 这类描述背后,是最容易被低估工作量的部分。」把编译链显式建模成一个枚举,
 * 是为了让这份工作量看得见 —— 每一步都是一个可以单独测试、单独报错的对象。
 *
 * <p><b>顺序不能随意调。</b> 依赖校验必须在 Schema 校验之后:数据源都不可用时,
 * 报"源表 orders 不存在"是误导 —— 表可能好好的,只是连不上去看。
 */
public enum CompileStage {

    /** 定义本身完整吗:必填项、格式、取值范围 */
    STRUCTURAL_VALIDATION("结构校验"),

    /** 依赖的东西都在吗:数据源存在且 AVAILABLE、引用的规则存在、JAR 已上传 */
    DEPENDENCY_VALIDATION("依赖校验"),

    /** 源表/目标表的字段存在吗、类型兼容吗(序号 9/11 的字段映射) */
    SCHEMA_VALIDATION("Schema 校验"),

    /** 异构类型映射是否可行、是否有损(序号 9/11) */
    TYPE_MAPPING("类型映射"),

    /** DAG 校验:环、可达性、孤立节点(序号 22) */
    DAG_VALIDATION("DAG 校验"),

    /** 调度配置:Cron 表达式、时区、并发策略 */
    SCHEDULE_VALIDATION("调度校验"),

    /** 并行度、分片、写入批次 */
    PLAN_OPTIMIZATION("计划优化"),

    /** 生成最终的物理计划 */
    PHYSICAL_PLAN("物理计划生成");

    private final String displayName;

    CompileStage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
