package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.ddl.DdlGateway;
import com.datagov.data.spi.ddl.TableDdl;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整库迁移的执行逻辑(功能 9 的执行侧、功能 10 的执行记录来源)。
 *
 * <p>一次迁移 = 逐表「建表(可选)+ 全量复制」。它<b>复用</b> {@link TableCopier}
 * 完成复制,而不是自己再写一遍读写循环 —— 迁移与同步在"把行从 A 搬到 B"这件事上
 * 完全一样,区别只在于迁移要先建表、且一次处理多张。
 *
 * <p><b>失败语义是「部分成功」而不是「全或无」。</b> 一个 200 表的迁移在第 137 张
 * 失败时,前 136 张已经建好并灌满了数据 —— DDL 是隐式提交的,假装能回滚只会
 * 让用户以为目标端是干净的。所以这里如实记录"成功 136 张,第 137 张失败",
 * 并在错误信息里给出表名,让用户能从那里续跑。
 */
@Component
public class DbMigrationRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(DbMigrationRunner.class);

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;
    private final TableCopier copier;
    /** 只读面:用来读源表结构 */
    private final DataAccessGateway gateway;
    /** 写面:建表。与只读面刻意分开 —— 见 DdlGateway 的注释 */
    private final DdlGateway ddlGateway;

    public DbMigrationRunner(DataSourceMapper dataSourceMapper,
                             ConnectionConfigAssembler assembler,
                             TableCopier copier,
                             DataAccessGateway gateway,
                             DdlGateway ddlGateway) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
        this.copier = copier;
        this.gateway = gateway;
        this.ddlGateway = ddlGateway;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.MIGRATION;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> source = section(context, "source");
        Map<String, Object> target = section(context, "target");
        Map<String, Object> naming = context.plan().get("naming") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        Map<String, String> ddlOverrides = stringMap(context.plan().get("ddlOverrides"));

        DataSourceEntity sourceDs = requireDataSource(str(source, "dataSourceId"), "源");
        DataSourceEntity targetDs = requireDataSource(str(target, "dataSourceId"), "目标");

        List<String> tables = stringList(source.get("tables"));
        if (tables.isEmpty()) {
            tables = discoverTables(sourceDs, str(source, "database"), str(source, "schema"));
            log.info("未指定表清单,从源库发现 {} 张表", tables.size());
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("源库里没有可迁移的表");
        }

        boolean createTable = Boolean.parseBoolean(String.valueOf(
                target.getOrDefault("createTable", "true")));
        int batchSize = intValue(target.get("batchSize"), 1000);
        String writeMode = str(target, "writeMode");

        long totalRead = 0;
        long totalWritten = 0;
        List<String> done = new ArrayList<>();

        for (String table : tables) {
            context.throwIfCanceled();
            String targetTable = applyNaming(table, naming);

            try {
                if (createTable) {
                    createTargetTable(sourceDs, targetDs, source, target,
                            table, targetTable, ddlOverrides.get(table));
                }
                TableCopier.CopyResult result = copier.copy(new TableCopier.CopySpec(
                        sourceDs, str(source, "database"), str(source, "schema"), table,
                        targetDs, str(target, "database"), str(target, "schema"), targetTable,
                        null, writeMode, batchSize), context);

                totalRead += result.rowsRead();
                totalWritten += result.rowsWritten();
                done.add(table);

                // 每张表结束回报一次进度。迁移动辄几十分钟,不报进度的话
                // 界面上除了"执行中"什么都没有,用户无从判断是慢还是卡住了。
                context.progress().accept(
                        new ExecutionEngine.EngineMetric(totalRead, totalWritten, 0));
                log.info("迁移完成 {}/{} 张:{} → {}(读{} 写{})",
                        done.size(), tables.size(), table, targetTable,
                        result.rowsRead(), result.rowsWritten());

            } catch (Exception e) {
                // 报出「第几张、哪一张」—— 用户要从这里续跑
                throw new IllegalStateException(
                        "迁移在第 %d/%d 张表「%s」失败(已成功 %d 张): %s".formatted(
                                done.size() + 1, tables.size(), table, done.size(), e.getMessage()), e);
            }
        }

        log.info("整库迁移完成 execution={} 共 {} 张表 读{} 写{}",
                context.executionId(), done.size(), totalRead, totalWritten);
        return RunResult.of(totalRead, totalWritten, 0);
    }

    /**
     * 建目标表。
     *
     * <p>优先用<b>用户改过的语句</b>(功能 9 的「预览并修改」)。没有改过才现场生成 ——
     * 反过来的话,用户在预览里的修改会被静默丢弃,那个功能就是假的。
     */
    private void createTargetTable(DataSourceEntity sourceDs, DataSourceEntity targetDs,
                                   Map<String, Object> source, Map<String, Object> target,
                                   String sourceTable, String targetTable,
                                   String ddlOverride) throws Exception {
        ConnectionConfig targetConfig = assembler.assemble(targetDs, targetDs.getWorkspaceId());

        List<String> statements;
        if (ddlOverride != null && !ddlOverride.isBlank()) {
            statements = splitStatements(ddlOverride);
            log.info("表 {} 使用用户确认的建表语句({} 条)", targetTable, statements.size());
        } else {
            TableDdl.CreateTableSpec spec = buildSpec(sourceDs, source, target,
                    sourceTable, targetTable);
            TableDdl.GeneratedDdl generated =
                    ddlGateway.generateCreateTable(targetDs.getType(), spec);
            statements = generated.statements();
            for (String warning : generated.warnings()) {
                log.warn("建表提醒 {}: {}", targetTable, warning);
            }
        }
        ddlGateway.executeDdl(targetDs.getType(), targetConfig, statements);
    }

    /** 从源表的目录结构生成目标表规格。 */
    private TableDdl.CreateTableSpec buildSpec(DataSourceEntity sourceDs,
                                               Map<String, Object> source,
                                               Map<String, Object> target,
                                               String sourceTable, String targetTable) {
        ConnectionConfig sourceConfig = assembler.assemble(sourceDs, sourceDs.getWorkspaceId());
        CatalogModel.CatalogPage page = gateway.browse(sourceDs.getType(), sourceConfig,
                CatalogPath.ofTable(str(source, "database"), str(source, "schema"), sourceTable));

        List<TableDdl.ColumnSpec> columns = page.columns().stream()
                .map(c -> new TableDdl.ColumnSpec(c.name(), c.canonicalType(),
                        c.precision(), c.scale(), c.nullable(), c.comment()))
                .toList();
        List<String> primaryKeys = page.columns().stream()
                .filter(CatalogModel.ColumnInfo::primaryKey)
                .map(CatalogModel.ColumnInfo::name)
                .toList();

        return new TableDdl.CreateTableSpec(
                str(target, "database"), str(target, "schema"), targetTable,
                columns, primaryKeys, null, Map.of());
    }

    /** 未指定表清单时,从源库发现全部表。 */
    private List<String> discoverTables(DataSourceEntity sourceDs, String database, String schema) {
        ConnectionConfig config = assembler.assemble(sourceDs, sourceDs.getWorkspaceId());
        CatalogPath path = schema != null && !schema.isBlank()
                ? CatalogPath.ofSchema(database, schema)
                : CatalogPath.ofDatabase(database);
        return gateway.browse(sourceDs.getType(), config, path).tables().stream()
                // 视图不迁:它是查询而不是数据,在目标端建一张同名表会让
                // 「视图」这个概念在迁移后凭空消失,而用户不会知道
                .filter(t -> t.kind() == CatalogModel.TableKind.TABLE)
                .map(CatalogModel.TableInfo::name)
                .toList();
    }

    /** 目标表命名规则。命名归 Metadata(功能 9 的备注),这里只是执行它。 */
    static String applyNaming(String sourceTable, Map<String, Object> naming) {
        String prefix = naming.get("prefix") == null ? "" : naming.get("prefix").toString();
        String suffix = naming.get("suffix") == null ? "" : naming.get("suffix").toString();
        String name = prefix + sourceTable + suffix;
        return Boolean.parseBoolean(String.valueOf(naming.getOrDefault("lowercase", "false")))
                ? name.toLowerCase()
                : name;
    }

    /**
     * 把用户改过的脚本拆成语句。
     *
     * <p>按分号拆是够用的:建表语句里出现分号只可能在字符串字面量里(注释文本),
     * 而那种情况极少且拆错了会立刻报语法错误 —— 不会静默建出一张错的表。
     * 真正的 SQL 解析要引入一个 parser,收益不抵成本。
     */
    static List<String> splitStatements(String script) {
        return java.util.Arrays.stream(script.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public String classify(Exception e) {
        return e instanceof java.sql.SQLException ? "DAT_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    private DataSourceEntity requireDataSource(String id, String label) {
        DataSourceEntity entity = id == null ? null : dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("%s数据源不存在: %s".formatted(label, id));
        }
        if (!entity.getType().isJdbc()) {
            throw new IllegalArgumentException(
                    "整库迁移只支持关系型/MPP 数据源,%s 是 %s"
                            .formatted(entity.getName(), entity.getType().displayName()));
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(RunContext context, String key) {
        Object value = context.plan().get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("物理计划缺少 " + key + " 段");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) ->
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return result;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .filter(s -> !s.isBlank()).toList();
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
