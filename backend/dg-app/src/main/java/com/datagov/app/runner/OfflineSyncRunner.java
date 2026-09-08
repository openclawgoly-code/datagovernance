package com.datagov.app.runner;

import com.datagov.metadata.dto.RuleDtos.RuleView;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.RuleService;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.rule.RuleInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线同步的执行逻辑(功能 11 的执行侧、功能 15 的执行记录来源)。
 *
 * <p><b>为什么在 dg-app 而不是 dg-runtime:</b> 它要同时用到 Metadata 的数据源定义
 * (拿连接配置)和 Data 的连接器。Runtime 的 pom 里两者都没有,而且不该有 ——
 * 那两条依赖会让「Runtime 不得解释业务语义」「Runtime 不得读 metadata 定义」
 * 同时失守。装配层是唯一有资格同时看见两边的地方。
 *
 * <p>实际的读写循环在 {@link TableCopier} 里,与整库迁移共用。这个类只负责
 * 把物理计划翻译成一次 {@code CopySpec} —— 一份"跑什么"到"怎么跑"的转换。
 */
@Component
public class OfflineSyncRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(OfflineSyncRunner.class);

    private final DataSourceMapper dataSourceMapper;
    private final TableCopier copier;
    private final RuleService ruleService;

    public OfflineSyncRunner(DataSourceMapper dataSourceMapper, TableCopier copier,
                             RuleService ruleService) {
        this.dataSourceMapper = dataSourceMapper;
        this.copier = copier;
        this.ruleService = ruleService;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.OFFLINE_SYNC;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> source = section(context, "source");
        Map<String, Object> target = section(context, "target");
        Map<String, String> mappings = stringMap(context.plan().get("fieldMappings"));

        if (mappings.isEmpty()) {
            // 编译期已经拦过。走到这里说明计划是手工改的或来自旧版本编译器 ——
            // 空映射不是"复制全部字段",而是"什么都不搬",不能默默跑成空转
            throw new IllegalArgumentException("物理计划里没有字段映射");
        }

        DataSourceEntity sourceDs = requireDataSource(str(source, "dataSourceId"), "源");
        DataSourceEntity targetDs = requireDataSource(str(target, "dataSourceId"), "目标");

        log.info("离线同步开始 execution={} {} -> {} 字段{}个",
                context.executionId(), str(source, "table"), str(target, "table"), mappings.size());

        Map<String, List<RuleInterpreter.Rule>> fieldRules =
                resolveRules(context.workspaceId(), context.plan().get("fieldRules"));

        TableCopier.CopyResult result = copier.copy(new TableCopier.CopySpec(
                sourceDs, str(source, "database"), str(source, "schema"), str(source, "table"),
                targetDs, str(target, "database"), str(target, "schema"), str(target, "table"),
                mappings, str(source, "whereClause"), str(target, "writeMode"),
                intValue(target.get("batchSize"), 1000), fieldRules), context);

        log.info("离线同步完成 execution={} 读{}行 写{}行",
                context.executionId(), result.rowsRead(), result.rowsWritten());
        return RunResult.of(result.rowsRead(), result.rowsWritten(), 0);
    }

    /**
     * 把计划里的 ruleId 解析成可执行的规则链(功能 17)。
     *
     * <p>计划里存的是 ID 而不是规则内容 —— 这是架构风险 R6 的落点:改一条规则,
     * 所有引用它的任务下次执行时自动跟上,而不需要重新编译几十个任务。
     *
     * <p>代价是执行期多一次查询。值得:内嵌规则内容意味着同一条脱敏规则会在
     * 十几个任务的计划里各存一份,修一处漏九处。
     */
    private Map<String, List<RuleInterpreter.Rule>> resolveRules(String workspaceId, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, List<RuleInterpreter.Rule>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String field = String.valueOf(entry.getKey());
            List<String> ruleIds = toStringList(entry.getValue());
            if (ruleIds.isEmpty()) {
                continue;
            }
            List<RuleView> rules = ruleService.loadForExecution(workspaceId, ruleIds);
            if (rules.size() < ruleIds.size()) {
                // 规则被删了。删除保护本该拦住这种情况,走到这里说明保护被绕过
                // (直接改库、或引用计数失准)。宁可失败也不要静默少套一条规则 ——
                // 少套的那条可能正是脱敏。
                throw new IllegalStateException(
                        "字段 %s 引用的规则有 %d 条已不存在".formatted(field, ruleIds.size() - rules.size()));
            }
            result.put(field, rules.stream()
                    .map(r -> new RuleInterpreter.Rule(r.kind().name(), r.params()))
                    .toList());
        }
        log.info("已解析 {} 个字段的规则链", result.size());
        return result;
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .filter(s -> !s.isBlank()).toList();
    }

    @Override
    public String classify(Exception e) {
        // 区分"目标端的问题"与"平台的问题":值班的人第一件事就是判断
        // 该找 DBA 还是找开发
        return e instanceof SQLException ? "DAT_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    private DataSourceEntity requireDataSource(String id, String label) {
        DataSourceEntity entity = id == null ? null : dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("%s数据源不存在: %s".formatted(label, id));
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
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) ->
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return result;
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
