package com.datagov.control.compile;

import com.datagov.control.entity.ControlEntities.JobDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 各编译器共用的校验片段。
 *
 * <p>是<b>静态工具</b>而不是抽象基类。基类会诱导后来者往里加"只有某两种类型
 * 才用得上"的方法,而那正是这些编译器分开写的理由。工具方法用不上就不调,
 * 没有任何耦合。
 */
public final class CompilerSupport {

    private CompilerSupport() {
    }

    /**
     * 校验一个必填的数据源引用。
     *
     * <p>两件事一起做:存在且 AVAILABLE。分开报错是有意义的 —— 「数据源不存在」
     * 和「数据源连不上」要用户做的事完全不同,一个去改配置,一个去找网管。
     *
     * @return 数据源可用则 true
     */
    public static boolean requireAvailableDataSource(
            CompileResult.Collector collector, JobCompiler.MetadataLookup metadata,
            String workspaceId, String dataSourceId, String location, String label) {

        if (dataSourceId == null || dataSourceId.isBlank()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                    "%s未指定".formatted(label));
            return false;
        }
        if (!metadata.isDataSourceAvailable(workspaceId, dataSourceId)) {
            collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                    "%s「%s」当前不可用".formatted(label,
                            metadata.dataSourceName(workspaceId, dataSourceId)),
                    "请先在数据源管理里通过连通性测试");
            return false;
        }
        return true;
    }

    /**
     * 校验表结构可读,并返回字段表。
     *
     * @return 字段名 → CanonicalType;拿不到快照时空 Map(已记诊断)
     */
    public static Map<String, String> requireTableColumns(
            CompileResult.Collector collector, JobCompiler.MetadataLookup metadata,
            String workspaceId, String dataSourceId,
            String database, String schema, String table, String location, String label) {

        if (table == null || table.isBlank()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                    "%s未指定".formatted(label));
            return Map.of();
        }
        Map<String, String> columns =
                metadata.tableColumns(workspaceId, dataSourceId, database, schema, table);
        if (columns.isEmpty()) {
            collector.error(CompileStage.SCHEMA_VALIDATION, location,
                    "读不到%s「%s」的结构".formatted(label, table),
                    "请先在数据源管理里浏览一次该表的结构,平台会缓存它的字段列表");
        }
        return columns;
    }

    /**
     * 校验一组字段映射。
     *
     * <p>三类问题分级处理:
     * <ul>
     *   <li>源字段不存在 → ERROR。跑起来一定失败</li>
     *   <li>目标字段不存在 → ERROR。同上</li>
     *   <li>类型不兼容但可转换 → WARNING。用户可能就是要这么干,但该知道会丢什么</li>
     * </ul>
     *
     * @param mappings 源字段名 → 目标字段名
     */
    public static void validateFieldMappings(
            CompileResult.Collector collector,
            Map<String, String> sourceColumns, Map<String, String> targetColumns,
            Map<String, String> mappings, String locationPrefix) {

        if (mappings.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, locationPrefix,
                    "没有配置任何字段映射",
                    "至少映射一个字段,否则这次同步不会搬运任何数据");
            return;
        }

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String source = mapping.getKey();
            String target = mapping.getValue();
            String location = locationPrefix + "." + source;

            // 源表结构读不到时(sourceColumns 为空)不再逐字段报错 ——
            // 二十个字段会得到二十条同因异果的诊断,把真正的问题淹没
            if (!sourceColumns.isEmpty() && !sourceColumns.containsKey(source)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, location,
                        "源表没有字段「%s」".formatted(source));
                continue;
            }
            if (target == null || target.isBlank()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                        "字段「%s」没有指定目标字段".formatted(source));
                continue;
            }
            if (!targetColumns.isEmpty() && !targetColumns.containsKey(target)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, location,
                        "目标表没有字段「%s」".formatted(target));
                continue;
            }

            String sourceType = sourceColumns.get(source);
            String targetType = targetColumns.get(target);
            if (sourceType == null || targetType == null || sourceType.equals(targetType)) {
                continue;
            }
            TypeCompatibility compatibility = TypeCompatibility.between(sourceType, targetType);
            if (compatibility == TypeCompatibility.INCOMPATIBLE) {
                collector.error(CompileStage.TYPE_MAPPING, location,
                        "%s(%s)无法写入 %s(%s)".formatted(source, sourceType, target, targetType),
                        "请调整目标表字段类型,或在映射中加入类型转换");
            } else if (compatibility == TypeCompatibility.LOSSY) {
                collector.warn(CompileStage.TYPE_MAPPING, location,
                        "%s(%s)写入 %s(%s)可能丢失精度或被截断"
                                .formatted(source, sourceType, target, targetType),
                        "确认这是预期行为,否则请放宽目标字段类型");
            }
        }
    }

    /** 把映射整理成物理计划里的有序列表,顺序稳定便于比对两次编译的产物。 */
    public static Map<String, Object> mappingPlan(Map<String, String> mappings) {
        return new LinkedHashMap<>(mappings);
    }

    /** 定义所属空间 —— 编译器要传给 MetadataLookup 的作用域。 */
    public static String workspaceOf(JobDefinition definition) {
        return definition.getWorkspaceId();
    }

    /**
     * 报出配置里编译器<b>不认识</b>的键。
     *
     * <p>没有这条检查时,一个拼错的键会被静默忽略:编译通过、执行成功、
     * 而那个配置从来没有生效过。这个失败模式在多数字段上只是"没生效",
     * 但在<b>脱敏</b>上是一次数据泄露 —— 用户配了脱敏规则,任务报告成功,
     * 明文却原样落进了目标库,而没有任何一处告诉过他。
     *
     * <p>给 WARNING 而不是 ERROR:配置里多带一个平台不认识的键(比如前端
     * 塞进来的界面状态)本身不该让任务无法编译。但它必须被看见 ——
     * 诊断对话框会把警告一并显示出来。
     */
    public static void warnUnknownKeys(CompileResult.Collector collector,
                                       Map<String, Object> config,
                                       Set<String> knownKeys) {
        List<String> unknown = config.keySet().stream()
                .filter(key -> !knownKeys.contains(key))
                .sorted()
                .toList();
        if (unknown.isEmpty()) {
            return;
        }
        collector.warn(CompileStage.STRUCTURAL_VALIDATION, "config",
                "配置里有 %d 个平台不认识的键:%s".formatted(unknown.size(),
                        String.join(", ", unknown)),
                "它们不会生效。若本意是配置某个功能,请核对键名 —— "
                        + "一个拼错的键会让配置静默失效,而任务照常报告成功");
    }
}
