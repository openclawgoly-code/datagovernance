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
 * writeMode:     APPEND | OVERWRITE(UPSERT 见 UNIMPLEMENTED_WRITE_MODES)
 * whereClause:   增量同步的过滤条件,可选
 * batchSize:     写入批次
 * </pre>
 */
@Component
public class OfflineSyncCompiler implements JobCompiler {

    /** 这个编译器认识的配置键。多出来的会被 warnUnknownKeys 报出来 */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "sourceDataSourceId", "sourceDatabase", "sourceSchema", "sourceTable",
            "targetDataSourceId", "targetDatabase", "targetSchema", "targetTable",
            "fieldMappings", "fieldRules", "whereClause", "writeMode", "batchSize");

    /** 写入批次的默认值与上限。批次过大时一次失败要回滚的数据量也大。 */
    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int MAX_BATCH_SIZE = 50_000;

    private static final List<String> WRITE_MODES = List.of("APPEND", "OVERWRITE");

    /**
     * 界面上曾经给过、执行侧其实没实现的写入模式。
     *
     * <p>单独列出来而不是从 {@link #WRITE_MODES} 里一删了事,是为了能给一句说得清的
     * 错。混在一起的话用户看到的是"写入模式无效: UPSERT",他会以为自己拼错了,
     * 而真相是平台没实现。
     *
     * <p><b>UPSERT 的实情</b>:{@code TableCopier.buildInsert} 生成的是一条普通
     * INSERT,没有 ON CONFLICT / ON DUPLICATE KEY / MERGE。编译期校验主键、执行期
     * 不用它 —— 跑出来其实是 APPEND,插入重复行而不是按主键更新。这比报错坏得多:
     * 用户配的是"按主键更新",拿到的是一张越跑越大的表,而且没有任何提示。
     *
     * <p>要实现它,除了逐方言的 SQL(PostgreSQL 的 ON CONFLICT、MySQL/Doris 的
     * ON DUPLICATE KEY、Oracle/SQLServer/达梦的 MERGE),编译期还要把这两条校验
     * 加回来:主键字段必须存在于目标表;主键必须出现在字段映射的目标端(否则插入
     * 时它是 NULL,永远匹配不上)。
     */
    private static final List<String> UNIMPLEMENTED_WRITE_MODES = List.of("UPSERT");

    @Override
    public JobType jobType() {
        return JobType.OFFLINE_SYNC;
    }

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();

        // 拼错的键会被静默忽略 —— 在脱敏字段上那就是一次数据泄露。
        // 见 CompilerSupport.warnUnknownKeys 的说明
        CompilerSupport.warnUnknownKeys(collector, config, KNOWN_KEYS);
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

        String writeMode = validateWriteMode(collector, config);
        int batchSize = validateBatchSize(collector, config);
        validateFieldRules(collector, config, mappings);

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(
                buildPlan(config, mappings, writeMode, batchSize), collector.all());
    }

    /**
     * 校验写入模式。
     *
     * <p>把"没实现"和"填错了"分开报:两者对用户的下一步动作完全不同 —— 前者要
     * 换一种模式,后者要改拼写。
     */
    private String validateWriteMode(CompileResult.Collector collector, Map<String, Object> config) {
        String writeMode = str(config, "writeMode");
        if (writeMode == null || writeMode.isBlank()) {
            writeMode = "APPEND";
        }
        if (UNIMPLEMENTED_WRITE_MODES.contains(writeMode)) {
            // 宁可在这里报错,也不能让它默默跑成 APPEND。见
            // UNIMPLEMENTED_WRITE_MODES 的说明:配的是"按主键更新",跑出来是
            // "插入重复行",而且没有任何提示 —— 这比编译失败坏得多。
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "本版本尚未实现 UPSERT 写入模式",
                    "执行侧生成的是普通 INSERT,跑起来其实是 APPEND,会插入重复行而不是"
                            + "按主键更新。请改用 OVERWRITE(每次整表覆盖),或用 APPEND "
                            + "配合目标表上的唯一约束由数据库去挡重复");
            return writeMode;
        }
        if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "写入模式无效: " + writeMode, "可选值: " + String.join(" / ", WRITE_MODES));
            return writeMode;
        }

        if ("OVERWRITE".equals(writeMode)) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "OVERWRITE 会在写入前清空目标表",
                    "确认目标表没有其它来源的数据,否则它们会一并被删除");
        }
        return writeMode;
    }

    /**
     * 校验规则引用(功能 17)。
     *
     * <p>只校验"规则挂在一个存在的映射字段上",不校验规则本身是否存在 ——
     * 那要向 Metadata 查询,而 MetadataLookup 刻意做得很窄(只有三个方法)。
     * 规则不存在会在执行期被 OfflineSyncRunner 拦住并明确报错,而删除保护
     * 本来就该让这种情况不发生。
     */
    @SuppressWarnings("unchecked")
    private void validateFieldRules(CompileResult.Collector collector, Map<String, Object> config,
                                    Map<String, String> mappings) {
        Object raw = config.get("fieldRules");
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (!mappings.containsKey(field)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "fieldRules." + field,
                        "字段「%s」配了规则,但它不在字段映射里".formatted(field),
                        "规则只对被同步的字段有意义 —— 要么把它加进映射,要么删掉规则");
            }
        }
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
        // 不再写 primaryKeys:离线同步的计划里没有任何消费者会读它(UPSERT 一停,
        // 它就是纯粹的死数据),而计划里躺着一个没人用的字段,会让下一个读代码的人
        // 以为 UPSERT 是通的。实现 UPSERT 时连同 stringList 一起加回来。
        target.put("batchSize", batchSize);
        plan.put("target", target);

        plan.put("fieldMappings", CompilerSupport.mappingPlan(mappings));
        // 规则以 ID 引用带进计划,不内嵌规则内容(架构风险 R6)。改一条规则,
        // 所有引用它的任务下次执行自动跟上,不必重新编译几十个任务。
        plan.put("fieldRules", config.getOrDefault("fieldRules", Map.of()));
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
}
