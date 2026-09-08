package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件解析入库(功能 12)的编译器。
 *
 * <p>与离线同步的区别在<b>源</b>:源不是一张表而是一批文件,所以编译期能校验的
 * 东西少了一半 —— 文件的列结构要等真的读一行才知道。这里能做的是:确认文件
 * 数据源可用、目标表结构读得到、解析格式的参数自洽。
 *
 * <p>「用户配的列名对不对」只能在执行期发现。这不是偷懒:CSV 没有元数据,
 * 平台唯一能做的诚实回答是"跑一次看看"。所以这个编译器给的诊断少而准,
 * 不去编造一些其实验证不了的检查。
 *
 * <p>配置形状:
 * <pre>
 * sourceDataSourceId  FTP / SFTP 数据源
 * path                文件或目录路径
 * filePattern         文件名通配,如 *.csv;path 是目录时用它筛选
 * format              CSV | JSON
 * charset             默认 UTF-8
 * delimiter           CSV 分隔符,默认 ,
 * hasHeader           CSV 首行是否表头,默认 true
 * jsonPath            JSON 数组所在的路径,如 data.items;留空表示顶层就是数组
 * columns             [列名...] —— 无表头时必填,有表头时用于筛选与排序
 * targetDataSourceId / targetDatabase / targetSchema / targetTable
 * fieldMappings       文件列名 → 目标字段
 * writeMode / batchSize
 * </pre>
 */
@Component
public class FileParseCompiler implements JobCompiler {

    private static final List<String> FORMATS = List.of("CSV", "JSON");
    private static final List<String> WRITE_MODES = List.of("APPEND", "OVERWRITE");

    /**
     * 平台认识的配置键。不在这里的键会被警告 —— 一个拼错的键会让配置静默失效,
     * 而任务照常报告成功。
     *
     * <p>这条防线在文件解析上比在别处更要紧:走这条路进来的多半是外部交换文件,
     * 而 {@code fieldRules} 拼错一个字母,明文就直接落进目标库了。
     */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "sourceDataSourceId", "path", "filePattern", "format", "charset",
            "delimiter", "hasHeader", "jsonPath", "columns",
            "targetDataSourceId", "targetDatabase", "targetSchema", "targetTable",
            "fieldMappings", "fieldRules", "writeMode", "batchSize");

    @Override
    public JobType jobType() {
        return JobType.FILE_PARSE;
    }

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        CompilerSupport.warnUnknownKeys(collector, config, KNOWN_KEYS);
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        CompilerSupport.requireAvailableDataSource(collector, context.metadata(), workspaceId,
                str(config, "sourceDataSourceId"), "sourceDataSourceId", "文件数据源");

        if (!isPresent(str(config, "path"))) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "path", "未指定文件路径");
        }

        String format = str(config, "format");
        if (format == null || format.isBlank()) {
            format = "CSV";
        } else if (!FORMATS.contains(format)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "format",
                    "不支持的文件格式: " + format, "可选值: " + String.join(" / ", FORMATS));
        }

        List<String> columns = stringList(config, "columns");
        boolean hasHeader = boolValue(config.get("hasHeader"), true);
        if ("CSV".equals(format) && !hasHeader && columns.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "columns",
                    "CSV 无表头时必须指定列名",
                    "否则平台无从知道第几列是什么 —— 位置对不上会把数据写错列");
        }

        String targetDs = str(config, "targetDataSourceId");
        boolean targetOk = CompilerSupport.requireAvailableDataSource(collector, context.metadata(),
                workspaceId, targetDs, "targetDataSourceId", "目标数据源");

        Map<String, String> targetColumns = Map.of();
        if (targetOk) {
            targetColumns = CompilerSupport.requireTableColumns(collector, context.metadata(),
                    workspaceId, targetDs, str(config, "targetDatabase"),
                    str(config, "targetSchema"), str(config, "targetTable"),
                    "targetTable", "目标表");
        }

        Map<String, String> mappings = mappings(config);
        if (mappings.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "fieldMappings",
                    "没有配置任何字段映射",
                    "至少映射一个字段,否则这次解析不会写入任何数据");
        }
        // 源侧只能校验"映射的源列在 columns 里"(如果配了 columns);
        // 目标侧能校验字段是否存在。类型无从校验 —— 文件里的值都是字符串。
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String location = "fieldMappings." + mapping.getKey();
            if (!columns.isEmpty() && !columns.contains(mapping.getKey())) {
                collector.error(CompileStage.SCHEMA_VALIDATION, location,
                        "文件里没有声明列「%s」".formatted(mapping.getKey()));
            }
            if (!targetColumns.isEmpty() && !targetColumns.containsKey(mapping.getValue())) {
                collector.error(CompileStage.SCHEMA_VALIDATION, location,
                        "目标表没有字段「%s」".formatted(mapping.getValue()));
            }
        }

        String writeMode = str(config, "writeMode");
        if (writeMode == null || writeMode.isBlank()) {
            writeMode = "APPEND";
        } else if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "写入模式无效: " + writeMode, "可选值: " + String.join(" / ", WRITE_MODES));
        }

        // 文件解析的值全是字符串,写进数值/时间列要靠目标端隐式转换。
        // 这是可以工作的,但值得提醒一次 —— 一条格式不对的记录会让整批失败。
        if (!targetColumns.isEmpty()) {
            Map<String, String> resolvedTargetColumns = targetColumns;
            List<String> nonText = mappings.values().stream()
                    .filter(resolvedTargetColumns::containsKey)
                    .filter(target -> {
                        String type = resolvedTargetColumns.get(target);
                        return type != null && !type.equals("VARCHAR") && !type.equals("TEXT")
                                && !type.equals("CHAR");
                    })
                    .toList();
            if (!nonText.isEmpty()) {
                collector.warn(CompileStage.TYPE_MAPPING, "fieldMappings",
                        "%d 个目标字段不是文本类型,写入依赖目标端的隐式转换"
                                .formatted(nonText.size()),
                        "文件里的值都是字符串;一条格式不对的记录会让整批失败,"
                                + "必要时用清洗规则先规整格式");
            }
        }

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(
                buildPlan(config, format, columns, hasHeader, mappings, writeMode), collector.all());
    }

    private Map<String, Object> buildPlan(Map<String, Object> config, String format,
                                          List<String> columns, boolean hasHeader,
                                          Map<String, String> mappings, String writeMode) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "FILE_PARSE");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("dataSourceId", str(config, "sourceDataSourceId"));
        source.put("path", str(config, "path"));
        source.put("filePattern", str(config, "filePattern"));
        source.put("format", format);
        source.put("charset", config.getOrDefault("charset", "UTF-8"));
        source.put("delimiter", config.getOrDefault("delimiter", ","));
        source.put("hasHeader", hasHeader);
        source.put("jsonPath", str(config, "jsonPath"));
        source.put("columns", columns);
        plan.put("source", source);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("dataSourceId", str(config, "targetDataSourceId"));
        target.put("database", str(config, "targetDatabase"));
        target.put("schema", str(config, "targetSchema"));
        target.put("table", str(config, "targetTable"));
        target.put("writeMode", writeMode);
        target.put("batchSize", config.getOrDefault("batchSize", 1000));
        plan.put("target", target);

        plan.put("fieldMappings", CompilerSupport.mappingPlan(mappings));
        plan.put("fieldRules", config.getOrDefault("fieldRules", Map.of()));
        return plan;
    }

    private static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean boolValue(Object raw, boolean fallback) {
        return raw == null ? fallback : Boolean.parseBoolean(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mappings(Map<String, Object> config) {
        Object raw = config.get("fieldMappings");
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) ->
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return result;
    }

    private static List<String> stringList(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf)
                .filter(s -> !s.isBlank()).toList();
    }
}
