package com.datagov.control.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编译结果。
 *
 * <p><b>它携带错误定位,而不只是一句"编译失败"。</b> 这一点是 Contract 里
 * {@code CompileResult # 含错误定位,供 UI 精确报错} 明确要求的,理由很实际:
 * 一个二十个字段映射的同步任务编译失败,只说"字段类型不兼容"等于什么都没说 ——
 * 用户要逐个字段去猜是哪一个。
 *
 * <p>因此每条 {@link Diagnostic} 都带 {@code location},指向定义里出问题的那一处
 * (字段名、节点 ID、表名)。
 */
public record CompileResult(
        boolean succeeded,
        List<Diagnostic> diagnostics,
        /** 编译产物。失败时为空 —— 半成品的物理计划比没有更危险 */
        Map<String, Object> physicalPlan
) {

    public CompileResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        physicalPlan = physicalPlan == null ? Map.of() : Map.copyOf(physicalPlan);
    }

    public static CompileResult success(Map<String, Object> plan, List<Diagnostic> warnings) {
        return new CompileResult(true, warnings, plan);
    }

    public static CompileResult failure(List<Diagnostic> diagnostics) {
        return new CompileResult(false, diagnostics, Map.of());
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).toList();
    }

    /** 一行摘要,写进定义的 lastCompileMessage 供列表页显示 */
    public String summary() {
        if (succeeded) {
            return warnings().isEmpty()
                    ? "编译通过"
                    : "编译通过,%d 条提醒".formatted(warnings().size());
        }
        List<Diagnostic> errors = errors();
        return errors.isEmpty()
                ? "编译失败"
                : "%s(共 %d 个错误)".formatted(errors.get(0).message(), errors.size());
    }

    public enum Severity {
        /** 阻断发布 */
        ERROR,
        /**
         * 不阻断,但值得看一眼。
         *
         * <p>典型是有损的类型映射:DECIMAL(20,4) 写进 DOUBLE 能跑,但会丢精度。
         * 拦下来太严(用户可能就是要这么干),不提醒又会在半年后变成一起数据事故。
         */
        WARNING
    }

    /**
     * 一条诊断。
     *
     * @param stage    出自编译链的哪一步,便于定位是哪类校验没过
     * @param location 定义里出问题的位置:字段名 / 节点 ID / 表名。<b>不要留空</b>
     * @param message  给用户看的话,不是给开发看的
     * @param hint     怎么改。能给就给 —— 报错而不说怎么办等于把问题丢回去
     */
    public record Diagnostic(Severity severity, CompileStage stage,
                             String location, String message, String hint) {

        public static Diagnostic error(CompileStage stage, String location, String message) {
            return new Diagnostic(Severity.ERROR, stage, location, message, null);
        }

        public static Diagnostic error(CompileStage stage, String location,
                                       String message, String hint) {
            return new Diagnostic(Severity.ERROR, stage, location, message, hint);
        }

        public static Diagnostic warning(CompileStage stage, String location,
                                         String message, String hint) {
            return new Diagnostic(Severity.WARNING, stage, location, message, hint);
        }
    }

    /** 便于分阶段累积诊断的收集器。 */
    public static final class Collector {
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        public Collector add(Diagnostic diagnostic) {
            diagnostics.add(diagnostic);
            return this;
        }

        public Collector error(CompileStage stage, String location, String message) {
            return add(Diagnostic.error(stage, location, message));
        }

        public Collector error(CompileStage stage, String location, String message, String hint) {
            return add(Diagnostic.error(stage, location, message, hint));
        }

        public Collector warn(CompileStage stage, String location, String message, String hint) {
            return add(Diagnostic.warning(stage, location, message, hint));
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
        }

        public List<Diagnostic> all() {
            return List.copyOf(diagnostics);
        }
    }
}
