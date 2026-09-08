package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线同步(功能 11)的编译器。
 *
 * <p>把一份用户填的同步配置编译成物理计划,并在编译期回答三个问题:
 * <ol>
 *   <li>源和目标数据源都连得上吗</li>
 *   <li>源表和目标表的字段都在吗</li>
 *   <li>每一对字段映射的类型能不能对接、会不会丢东西</li>
 * </ol>
 *
 * <p>把这三件事放在编译期而不是执行期,是这整个 Space 存在的理由:一个字段名
 * 拼错的同步任务,应该在保存时就报出"源表没有字段 order_no",而不是在凌晨三点
 * 的调度里失败,第二天早上才被人发现。
 *
 * <p>配置形状(config 里的键):
 * <pre>
 * sourceDataSourceId, sourceDatabase, sourceSchema, sourceTable
 * targetDataSourceId, targetDatabase, targetSchema, targetTable
 * fieldMappings: { 源字段: 目标字段 }
 * writeMode:     APPEND | OVERWRITE | UPSERT
 * whereClause:   增量同步的过滤条件,可选
 * batchSize:     写入批次
 * </pre>
 */
@Component
public class OfflineSyncCompiler implements JobCompiler {

    /** 写入批次的默认值与上限。批次过大时一次失败要回滚的数据量也大。 */
    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int MAX_BATCH_SIZE = 50_000;

    private static final List<String> WRITE_MODES = List.of("APPEND", "OVERWRITE", "UPSERT");

    @Override
    public JobType jobType() {
        return JobType.OFFLINE_SYNC;
    }

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        String sourceDs = str(config, "sourceDataSourceId");
        String targetDs = str(config, "targetDataSourceId");

        boolean sourceOk = CompilerSupport.requireAvailableDataSource(
                collector, context.metadata(), workspaceId, sourceDs, "sourceDataSourceId", "源数据源");
        boolean targetOk = CompilerSupport.requireAvailableDataSource(
                collector, context.metadata(), workspaceId, targetDs, "targetDataSourceId", "目标数据源");

        // 数据源不可用时就不必再去查表结构了 —— 查不到是必然的,
        // 再报一遍"读不到表结构"只是把真正的原因埋得更深
        Map<String, String> sourceColumns = Map.of();
        Map<String, String> targetColumns = Map.of();
        if (sourceOk) {
            sourceColumns = CompilerSupport.requireTableColumns(collector, context.metadata(),
                    workspaceId, sourceDs, str(config, "sourceDatabase"),
                    str(config, "sourceSchema"), str(config, "sourceTable"),
                    "sourceTable", "源表");
        }
        if (targetOk) {
            targetColumns = CompilerSupport.requireTableColumns(collector, context.metadata(),
                    workspaceId, targetDs, str(config, "targetDatabase"),
                    str(config, "targetSchema"), str(config, "targetTable"),
                    "targetTable", "目标表");
        }

        Map<String, String> mappings = mappings(config);
        CompilerSupport.validateFieldMappings(collector, sourceColumns, targetColumns,
                mappings, "fieldMappings");

        String writeMode = validateWriteMode(collector, config, mappings, targetColumns);
        int batchSize = validateBatchSize(collector, config);

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(
                buildPlan(config, mappings, writeMode, batchSize), collector.all());
    }

    /**
     * 校验写入模式。
     *
     * <p>UPSERT 需要主键 —— 没有主键的"按主键更新"是一句自相矛盾的话,而目标端
     * 对它的反应通常是全表扫描后逐行比对,慢到像是挂住了。
     */
    private String validateWriteMode(CompileResult.Collector collector, Map<String, Object> config,
                                     Map<String, String> mappings, Map<String, String> targetColumns) {
        String writeMode = str(config, "writeMode");
        if (writeMode == null || writeMode.isBlank()) {
            writeMode = "APPEND";
        }
        if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "写入模式无效: " + writeMode, "可选值: " + String.join(" / ", WRITE_MODES));
            return writeMode;
        }

        if ("UPSERT".equals(writeMode)) {
            List<String> keys = stringList(config, "primaryKeys");
            if (keys.isEmpty()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "primaryKeys",
                        "UPSERT 模式必须指定主键字段",
                        "改用 APPEND,或补上用于判断记录是否已存在的字段");
            } else if (!targetColumns.isEmpty()) {
                for (String key : keys) {
                    if (!targetColumns.containsKey(key)) {
                        collector.error(CompileStage.SCHEMA_VALIDATION, "primaryKeys." + key,
                                "目标表没有主键字段「%s」".formatted(key));
                    } else if (!mappings.containsValue(key)) {
                        // 主键不在映射里,插入时它会是 NULL,UPSERT 永远匹配不上
                        collector.error(CompileStage.SCHEMA_VALIDATION, "primaryKeys." + key,
                                "主键字段「%s」没有出现在字段映射的目标端".formatted(key),
                                "UPSERT 靠主键判断记录是否存在,它必须有值可写");
                    }
                }
            }
        }

        if ("OVERWRITE".equals(writeMode)) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "OVERWRITE 会在写入前清空目标表",
                    "确认目标表没有其它来源的数据,否则它们会一并被删除");
        }
        return writeMode;
    }

    private int validateBatchSize(CompileResult.Collector collector, Map<String, Object> config) {
        Object raw = config.get("batchSize");
        if (raw == null) {
            return DEFAULT_BATCH_SIZE;
        }
        int batchSize;
        try {
            batchSize = Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "batchSize",
                    "写入批次不是数字: " + raw);
            return DEFAULT_BATCH_SIZE;
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            collector.error(CompileStage.PLAN_OPTIMIZATION, "batchSize",
                    "写入批次必须在 1-%d 之间,当前 %d".formatted(MAX_BATCH_SIZE, batchSize),
                    "批次过大时一次失败要回滚的数据量也大,而收益早已边际递减");
            return DEFAULT_BATCH_SIZE;
        }
        return batchSize;
    }

    /**
     * 生成物理计划。
     *
     * <p>计划里<b>没有连接串也没有凭据</b>,只有数据源 ID。凭据的解析发生在执行期
     * 由 Runtime 通过 Platform 的一次性句柄拿到 —— 把它编译进计划,等于让一份
     * 明文口令躺在 {@code ctl_job_definition.physical_plan_json} 里。
     */
    private Map<String, Object> buildPlan(Map<String, Object> config, Map<String, String> mappings,
                                          String writeMode, int batchSize) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "OFFLINE_SYNC");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("dataSourceId", str(config, "sourceDataSourceId"));
        source.put("database", str(config, "sourceDatabase"));
        source.put("schema", str(config, "sourceSchema"));
        source.put("table", str(config, "sourceTable"));
        source.put("whereClause", str(config, "whereClause"));
        source.put("columns", List.copyOf(mappings.keySet()));
        plan.put("source", source);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("dataSourceId", str(config, "targetDataSourceId"));
        target.put("database", str(config, "targetDatabase"));
        target.put("schema", str(config, "targetSchema"));
        target.put("table", str(config, "targetTable"));
        target.put("writeMode", writeMode);
        target.put("primaryKeys", stringList(config, "primaryKeys"));
        target.put("batchSize", batchSize);
        plan.put("target", target);

        plan.put("fieldMappings", CompilerSupport.mappingPlan(mappings));
        return plan;
    }

    private static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : value.toString();
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
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
