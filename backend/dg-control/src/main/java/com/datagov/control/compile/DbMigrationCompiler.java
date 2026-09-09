package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 整库迁移(功能 9)的编译器。
 *
 * <p>与离线同步的关键区别:<b>目标表还不存在</b>。同步是把数据搬进一张既有的表,
 * 迁移是把一个库的结构和数据整体复制过去 —— 所以编译期要做的不是"校验字段映射",
 * 而是"确认每一张源表都能在目标端建出来"。
 *
 * <p>建表语句的生成与执行属于 Data Space(功能 9 的备注:「命名规则归 Metadata,
 * 方言归 Data」),这里只做两件事:
 * <ol>
 *   <li>确认源和目标数据源都可用</li>
 *   <li>把源表清单与命名规则编译进物理计划</li>
 * </ol>
 * 具体的 DDL 由执行期向 Data 要 —— 编译期就生成的话,用户在「预览并修改建表语句」
 * 里改过的内容会被下一次编译覆盖掉。
 *
 * <p>配置形状:
 * <pre>
 * sourceDataSourceId, sourceDatabase, sourceSchema
 * targetDataSourceId, targetDatabase, targetSchema
 * tables:        [表名...] —— 空表示迁移整个库
 * tablePrefix:   目标表名前缀,可选
 * tableSuffix:   目标表名后缀,可选
 * lowercaseNames: 目标表名与列名转小写,默认 false —— 只转一半的话,PostgreSQL 里
 *                 一个建成 "Name" 的列每次查询都得带引号,等于没转
 * createTable:   是否自动建表,默认 true
 * writeMode:     APPEND | OVERWRITE
 * batchSize
 * ddlOverrides:  { 源表名: 用户改过的建表语句 } —— 功能 9 的「预览并修改」
 * </pre>
 */
@Component
public class DbMigrationCompiler implements JobCompiler {

    /** 一次迁移的表数上限。超过它更该拆成几个任务,否则一次失败要重跑几小时。 */
    static final int MAX_TABLES = 500;

    private static final List<String> WRITE_MODES = List.of("APPEND", "OVERWRITE");

    @Override
    public JobType jobType() {
        return JobType.DB_MIGRATION;
    }


    /**
     * 平台认识的配置键。不在这里的键会被警告 —— 一个拼错的键会让配置静默失效,
     * 而任务照常报告成功。
     */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "sourceDataSourceId", "sourceDatabase", "sourceSchema",
            "targetDataSourceId", "targetDatabase", "targetSchema",
            "tables", "tablePrefix", "tableSuffix", "lowercaseNames",
            "createTable", "ddlOverrides", "writeMode", "batchSize");

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        CompilerSupport.warnUnknownKeys(collector, config, KNOWN_KEYS);
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        String sourceDs = str(config, "sourceDataSourceId");
        String targetDs = str(config, "targetDataSourceId");

        boolean sourceOk = CompilerSupport.requireAvailableDataSource(
                collector, context.metadata(), workspaceId, sourceDs, "sourceDataSourceId", "源数据源");
        CompilerSupport.requireAvailableDataSource(
                collector, context.metadata(), workspaceId, targetDs, "targetDataSourceId", "目标数据源");

        // 源库与目标库是同一个 + 同一个 schema + 没有改名规则 = 把表迁到自己身上。
        // 这在执行期表现为"目标表已存在"或者更糟的自我覆盖,编译期就该拦住。
        if (Objects.equals(sourceDs, targetDs)
                && Objects.equals(str(config, "sourceDatabase"), str(config, "targetDatabase"))
                && Objects.equals(str(config, "sourceSchema"), str(config, "targetSchema"))
                && !isPresent(str(config, "tablePrefix"))
                && !isPresent(str(config, "tableSuffix"))) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "targetSchema",
                    "源与目标指向同一个库和模式,且没有改名规则",
                    "换一个目标库/模式,或者配置表名前缀/后缀");
        }

        List<String> tables = stringList(config, "tables");
        if (tables.isEmpty()) {
            // 空表清单的语义是「整个库」。这是功能 9 的名字所指的默认行为,
            // 但要说清楚 —— 用户可能只是忘了勾选。
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "tables",
                    "未指定表清单,将迁移源库/模式下的全部表",
                    "如只想迁移部分表,请显式勾选");
        } else if (tables.size() > MAX_TABLES) {
            collector.error(CompileStage.PLAN_OPTIMIZATION, "tables",
                    "一次迁移最多 %d 张表,当前 %d 张".formatted(MAX_TABLES, tables.size()),
                    "拆成几个任务 —— 否则一次失败要重跑几小时");
        }

        // 表结构在这里只做「读得到吗」的抽查,不逐字段校验:目标表还不存在,
        // 没有可对照的一端。真正的类型落地由建表语句负责,那一步有自己的
        // warning 通道(DialectDdl 的降级提醒)。
        if (sourceOk && !tables.isEmpty()) {
            checkTablesReadable(collector, context, workspaceId, sourceDs, config, tables);
        }

        String writeMode = validateWriteMode(collector, config);
        boolean createTable = boolValue(config.get("createTable"), true);
        if (!createTable && "OVERWRITE".equals(writeMode)) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "不自动建表 + OVERWRITE:目标表必须已存在,且会被清空",
                    "确认目标表已按预期结构建好");
        }

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(buildPlan(config, tables, writeMode, createTable),
                collector.all());
    }

    /**
     * 抽查前若干张表的结构可读性。
     *
     * <p>只查前 10 张而不是全部:一个 300 表的库会产生 300 次快照查询,而编译
     * 是一个用户点保存就触发的动作。抽查足以发现"整个库都没浏览过"这个最常见的
     * 问题,而逐表校验的收益远不及它的代价。
     */
    private void checkTablesReadable(CompileResult.Collector collector, CompileContext context,
                                     String workspaceId, String sourceDs,
                                     Map<String, Object> config, List<String> tables) {
        int sampled = Math.min(10, tables.size());
        List<String> unreadable = new ArrayList<>();
        for (int i = 0; i < sampled; i++) {
            String table = tables.get(i);
            Map<String, String> columns = context.metadata().tableColumns(
                    workspaceId, sourceDs, str(config, "sourceDatabase"),
                    str(config, "sourceSchema"), table);
            if (columns.isEmpty()) {
                unreadable.add(table);
            }
        }
        if (!unreadable.isEmpty()) {
            collector.error(CompileStage.SCHEMA_VALIDATION, "tables",
                    "读不到这些表的结构: %s".formatted(String.join("、", unreadable)),
                    "请先在数据源管理里浏览一次源库的结构,平台会缓存字段列表");
        }
    }

    private String validateWriteMode(CompileResult.Collector collector, Map<String, Object> config) {
        String writeMode = str(config, "writeMode");
        if (writeMode == null || writeMode.isBlank()) {
            return "APPEND";
        }
        if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "整库迁移的写入模式无效: " + writeMode,
                    "可选值: " + String.join(" / ", WRITE_MODES)
                            + "(UPSERT 需要逐表主键,整库迁移不支持)");
        }
        return writeMode;
    }

    private Map<String, Object> buildPlan(Map<String, Object> config, List<String> tables,
                                          String writeMode, boolean createTable) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "DB_MIGRATION");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("dataSourceId", str(config, "sourceDataSourceId"));
        source.put("database", str(config, "sourceDatabase"));
        source.put("schema", str(config, "sourceSchema"));
        source.put("tables", tables);
        plan.put("source", source);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("dataSourceId", str(config, "targetDataSourceId"));
        target.put("database", str(config, "targetDatabase"));
        target.put("schema", str(config, "targetSchema"));
        target.put("writeMode", writeMode);
        target.put("createTable", createTable);
        target.put("batchSize", config.getOrDefault("batchSize", 1000));
        plan.put("target", target);

        // 命名规则归 Metadata(功能 9 的备注),所以它编译进计划;
        // 方言归 Data,所以 DDL 不在这里生成
        Map<String, Object> naming = new LinkedHashMap<>();
        naming.put("prefix", str(config, "tablePrefix"));
        naming.put("suffix", str(config, "tableSuffix"));
        naming.put("lowercase", boolValue(config.get("lowercaseNames"), false));
        plan.put("naming", naming);

        // 用户在「预览并修改建表语句」里改过的那些,原样带进计划 ——
        // 执行期优先用它而不是重新生成,否则用户的修改会被静默丢弃
        plan.put("ddlOverrides", config.getOrDefault("ddlOverrides", Map.of()));
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
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.toString());
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
