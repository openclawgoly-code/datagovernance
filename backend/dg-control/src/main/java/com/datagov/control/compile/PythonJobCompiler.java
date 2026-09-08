package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.runtime.service.ArtifactService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 作业(序号 34 的契约第 2 条)。
 *
 * <p>Intelligence 的训练与预标注作业走这条路。它<b>刻意与离线开发的 PYTHON
 * 形态分开</b>成一个独立的 JobType,理由是它们要校验的东西不同:
 * <ul>
 *   <li>离线开发的 Python 作业是用户自己的脚本,平台只关心它能不能跑起来</li>
 *   <li>训练作业要声明<b>输入数据集</b>与<b>输出注册项</b> —— 那两条声明是
 *       契约第 1 条(不得直连业务数据源)与第 3 条(产物注册进 Metadata)
 *       能够被检查的唯一位置</li>
 * </ul>
 *
 * <p>把它并进 {@code BatchDevCompiler} 会让那两条校验变成"某些情况下才做"的
 * 分支,而分支迟早会被绕过。
 *
 * <p>配置形状:
 * <pre>
 * artifactId        Python 包(序号 32 的制品)
 * entryModule       入口模块,如 train.main
 * programArgs       命令行参数
 * inputDatasetIds   输入的注册数据集 ID 列表 —— 契约第 1 条:只能读平台产物
 * outputArtifactId  产出要注册到哪个注册项(可空:纯评测作业没有产物)
 * resources         { cpu, memoryMb, gpu } —— 训练作业要声明资源
 * </pre>
 */
@Component
public class PythonJobCompiler implements JobCompiler {

    /** 单次作业能声明的输入数据集上限。再多说明它该先做一次数据集合并 */
    private static final int MAX_INPUTS = 50;

    private final ArtifactService artifactService;

    public PythonJobCompiler(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Override
    public JobType jobType() {
        return JobType.PYTHON_JOB;
    }


    /**
     * 平台认识的配置键。不在这里的键会被警告 —— 一个拼错的键会让配置静默失效,
     * 而任务照常报告成功。
     */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "artifactId", "entryModule", "programArgs", "env", "resources",
            "inputDatasetIds", "outputArtifactId",
            // 下面四个是「不得直连业务数据源」那条契约明确要拒绝的键。列在这里,
            // 是为了让它们只触发那条说清了来龙去脉的错误,而不是再叠一条泛泛的
            // "平台不认识这个键" —— 后者对已经拿到确切原因的人只是噪音。
            "jdbcUrl", "host", "sourceTable", "connectionString");

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        CompilerSupport.warnUnknownKeys(collector, config, KNOWN_KEYS);
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        String artifactId = str(config, "artifactId");
        if (isBlank(artifactId)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "artifactId",
                    "必须选择一个已上传的 Python 包",
                    "在「基础配置 → 文件管理」里上传后再回来选");
        } else if (!artifactService.existsVisible(workspaceId, artifactId)) {
            collector.error(CompileStage.DEPENDENCY_VALIDATION, "artifactId",
                    "制品不存在或已删除: " + artifactId);
        }

        if (isBlank(str(config, "entryModule"))) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "entryModule",
                    "必须指定入口模块",
                    "例如 train.main —— 平台不去解析包结构猜入口在哪");
        }

        // ── 契约第 1 条:取数 ────────────────────────────────────────────
        // Intelligence 不得直连业务数据源。所以这里<b>只接受注册数据集的 ID</b>,
        // 不接受连接串、不接受表名。一个想直连的作业在这一步就编译不过。
        List<String> inputs = stringList(config, "inputDatasetIds");
        if (inputs.size() > MAX_INPUTS) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "inputDatasetIds",
                    "输入数据集不得超过 %d 个,当前 %d".formatted(MAX_INPUTS, inputs.size()),
                    "更多的话应先做一次数据集合并,让那次合并本身也有版本可追");
        }
        for (String key : new String[]{"jdbcUrl", "host", "sourceTable", "connectionString"}) {
            if (config.containsKey(key)) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, key,
                        "Python 作业不得直连业务数据源(配置里出现了 %s)".formatted(key),
                        "原始数据必须先通过集成任务(序号 11-13)落地并注册成数据集,"
                                + "这里只声明 inputDatasetIds");
            }
        }
        if (inputs.isEmpty()) {
            // 不是错误:纯推理或纯评测的作业确实可以没有输入数据集。
            // 但它值得提醒 —— 绝大多数训练作业忘了填这个字段
            collector.warn(CompileStage.DEPENDENCY_VALIDATION, "inputDatasetIds",
                    "没有声明输入数据集",
                    "训练作业通常要声明 —— 它是「这个模型用了哪些数据」的唯一依据");
        }

        // ── 契约第 3 条:注册 ────────────────────────────────────────────
        String output = str(config, "outputArtifactId");
        if (isBlank(output)) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "outputArtifactId",
                    "没有声明产物注册项",
                    "作业产出的数据集或模型应注册进平台,否则它不在血缘里 —— "
                            + "纯评测作业可以不填");
        }

        Map<String, Object> resources = mapValue(config.get("resources"));
        Integer memory = intValue(resources.get("memoryMb"));
        if (memory != null && memory < 256) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "resources.memoryMb",
                    "内存下限 256 MB,当前 " + memory);
        }
        Integer gpu = intValue(resources.get("gpu"));
        if (gpu != null && gpu > 0 && memory == null) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "resources.memoryMb",
                    "申请了 GPU 但没有声明内存",
                    "训练作业的内存需求通常远高于默认值,不声明容易被 OOM 杀掉");
        }

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(buildPlan(config, inputs, output, resources),
                collector.all());
    }

    private Map<String, Object> buildPlan(Map<String, Object> config, List<String> inputs,
                                          String output, Map<String, Object> resources) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "PYTHON_JOB");
        plan.put("artifactId", str(config, "artifactId"));
        plan.put("entryModule", str(config, "entryModule"));
        plan.put("programArgs", config.getOrDefault("programArgs", ""));
        plan.put("inputDatasetIds", inputs);
        plan.put("outputArtifactId", output == null ? "" : output);
        plan.put("resources", resources);
        plan.put("env", config.getOrDefault("env", Map.of()));
        return plan;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> stringList(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private static Integer intValue(Object value) {
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
}
