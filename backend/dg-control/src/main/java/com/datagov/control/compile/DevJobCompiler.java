package com.datagov.control.compile;

import com.datagov.control.domain.JobType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实时开发(序号 18)与离线开发(序号 20)的共同编译逻辑。
 *
 * <p>这两者<b>编译期几乎一样</b>:都是"一段用户写的作业(SQL 或 JAR)+ 一组
 * 引擎参数",编译能做的是检查制品在不在、参数自洽。它们的区别在<b>运行态</b>
 * ——一个常驻一个周期,那是状态机的事(R5),不是编译器的事。
 *
 * <p>所以这里共享一个基类,而工作流与离线同步各自独立。共享的判据是
 * "校验逻辑是否真的相同",不是"名字看起来像不像"。
 */
abstract class DevJobCompiler implements JobCompiler {

    /** 作业形态:写 SQL 还是传 JAR */
    private static final List<String> SOURCE_KINDS = List.of("SQL", "JAR", "PYTHON");

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        CompilerSupport.warnUnknownKeys(collector, config, knownKeys());

        String sourceKind = str(config, "sourceKind");
        if (sourceKind == null || sourceKind.isBlank()) {
            sourceKind = "SQL";
        } else if (!SOURCE_KINDS.contains(sourceKind)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "sourceKind",
                    "不支持的作业形态: " + sourceKind,
                    "可选:" + String.join(" / ", SOURCE_KINDS));
        }

        if ("SQL".equals(sourceKind)) {
            String sql = str(config, "sql");
            if (sql == null || sql.isBlank()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "sql", "没有填写作业 SQL");
            }
        } else {
            // JAR / PYTHON 都要指向一个已上传的制品(序号 32)
            String artifactId = str(config, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "artifactId",
                        "%s 作业必须选择一个已上传的制品".formatted(sourceKind),
                        "在「文件管理」里上传后再回来选");
            } else if (!artifactExists(context, artifactId)) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, "artifactId",
                        "制品不存在或已删除: " + artifactId);
            }
            if ("JAR".equals(sourceKind) && isBlank(str(config, "entryClass"))) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "entryClass",
                        "JAR 作业必须指定入口类",
                        "平台不去反编译 JAR 猜 main 方法在哪");
            }
        }

        Integer parallelism = intValue(config.get("parallelism"));
        if (parallelism != null && (parallelism < 1 || parallelism > 512)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "parallelism",
                    "并行度须在 1 到 512 之间,当前 " + parallelism);
        }

        validateSpecifics(context, config, collector);

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(
                buildPlan(context, config, sourceKind, parallelism), collector.all());
    }

    /** 各自特有的校验:流任务查 checkpoint,批任务查 Cron 语义之外的东西。 */
    /**
     * 所有开发类作业共有的配置键。不在已知集合里的键会被警告 —— 一个拼错的键
     * 会让配置静默失效,而任务照常报告成功。
     */
    private static final Set<String> BASE_KNOWN_KEYS = Set.of(
            "sourceKind", "sql", "artifactId", "entryClass", "parallelism", "engineConfig");

    /** 子类自己那几个键。默认没有。 */
    protected Set<String> extraKnownKeys() {
        return Set.of();
    }

    private Set<String> knownKeys() {
        Set<String> extra = extraKnownKeys();
        if (extra.isEmpty()) {
            return BASE_KNOWN_KEYS;
        }
        Set<String> all = new HashSet<>(BASE_KNOWN_KEYS);
        all.addAll(extra);
        return all;
    }

    protected abstract void validateSpecifics(CompileContext context, Map<String, Object> config,
                                              CompileResult.Collector collector);

    /** 计划里的 kind,决定 Runtime 交给哪个 runner。 */
    protected abstract String planKind();

    /**
     * 制品是否存在。
     *
     * <p>做成可覆盖的钩子而不是直接注入 mapper:Control 只需要知道"在不在",
     * 而制品仓库归 Runtime 管(序号 32)。这个方法由子类用注入的查询器实现。
     */
    protected abstract boolean artifactExists(CompileContext context, String artifactId);

    protected Map<String, Object> buildPlan(CompileContext context, Map<String, Object> config,
                                            String sourceKind, Integer parallelism) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", planKind());
        plan.put("sourceKind", sourceKind);
        putIfPresent(plan, "sql", str(config, "sql"));
        putIfPresent(plan, "artifactId", str(config, "artifactId"));
        putIfPresent(plan, "entryClass", str(config, "entryClass"));
        putIfPresent(plan, "programArgs", str(config, "programArgs"));
        plan.put("parallelism", parallelism == null ? 1 : parallelism);
        plan.put("engineConfig", config.getOrDefault("engineConfig", Map.of()));
        enrichPlan(plan, config);
        return plan;
    }

    /** 子类往计划里补自己的字段。 */
    protected void enrichPlan(Map<String, Object> plan, Map<String, Object> config) {
    }

    /**
     * 只在有值时放进计划。
     *
     * <p>{@code CompileResult} 用 {@code Map.copyOf} 存计划,而它<b>拒绝 null 值</b>——
     * 放一个 null 会让整个编译以「编译器内部错误」失败,而用户看到的是一句
     * 毫无信息的 null。缺失的键本来就该缺失,不该占一个空位。
     */
    protected static void putIfPresent(Map<String, Object> plan, String key, Object value) {
        if (value != null) {
            plan.put(key, value);
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    protected static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : value.toString();
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 供子类声明自己是哪种 JobType。 */
    @Override
    public abstract JobType jobType();
}
