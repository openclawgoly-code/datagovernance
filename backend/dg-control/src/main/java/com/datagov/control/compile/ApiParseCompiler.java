package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口解析入库(功能 13)的编译器。
 *
 * <p>与文件解析同构 —— 源是一次 HTTP 拉取而不是一批文件。凭据归 Platform
 * (功能 13 的备注:「Platform(凭据)」),所以这里只有数据源 ID,
 * 认证信息在执行期由 Metadata 的凭据装配器解析。
 *
 * <p>配置形状:
 * <pre>
 * sourceDataSourceId  RestAPI 数据源(baseUrl 与认证在它身上)
 * path                相对 baseUrl 的路径,如 /v1/orders
 * method              GET | POST,默认 GET
 * queryParams         { 名: 值 }
 * headers             { 名: 值 } —— 不含认证头,那由凭据注入
 * body                POST 的请求体(JSON 字符串)
 * jsonPath            数据数组所在的路径,如 data.items;留空表示顶层就是数组
 * pagination          { mode: NONE|PAGE|CURSOR, pageParam, sizeParam, size,
 *                       cursorPath, cursorParam, maxPages }
 * targetDataSourceId / targetDatabase / targetSchema / targetTable
 * fieldMappings       JSON 字段 → 目标字段
 * writeMode / batchSize
 * </pre>
 */
@Component
public class ApiParseCompiler implements JobCompiler {

    private static final List<String> METHODS = List.of("GET", "POST");
    private static final List<String> PAGINATION_MODES = List.of("NONE", "PAGE", "CURSOR");
    private static final List<String> WRITE_MODES = List.of("APPEND", "OVERWRITE");

    /**
     * 分页拉取的页数上限。
     *
     * <p>没有上限的分页是一个无限循环的邀请:接口返回的 hasMore 恒为 true,
     * 或游标不前进,任务会一直拉到超时 —— 而超时之前它已经打了对方几十万次。
     */
    static final int MAX_PAGES_LIMIT = 10_000;

    @Override
    public JobType jobType() {
        return JobType.API_PARSE;
    }

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        CompilerSupport.requireAvailableDataSource(collector, context.metadata(), workspaceId,
                str(config, "sourceDataSourceId"), "sourceDataSourceId", "接口数据源");

        String method = str(config, "method");
        if (method == null || method.isBlank()) {
            method = "GET";
        } else if (!METHODS.contains(method)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "method",
                    "不支持的请求方法: " + method, "可选值: " + String.join(" / ", METHODS));
        }

        validatePagination(collector, config);

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
                    "至少映射一个字段,否则这次拉取不会写入任何数据");
        }
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            if (!targetColumns.isEmpty() && !targetColumns.containsKey(mapping.getValue())) {
                collector.error(CompileStage.SCHEMA_VALIDATION,
                        "fieldMappings." + mapping.getKey(),
                        "目标表没有字段「%s」".formatted(mapping.getValue()));
            }
        }
        // 源侧无从校验:接口返回什么字段,只有真的调一次才知道。
        // 这里不编造检查 —— 给不出的保证就不给。

        String writeMode = str(config, "writeMode");
        if (writeMode == null || writeMode.isBlank()) {
            writeMode = "APPEND";
        } else if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "writeMode",
                    "写入模式无效: " + writeMode, "可选值: " + String.join(" / ", WRITE_MODES));
        }

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(buildPlan(config, method, mappings, writeMode),
                collector.all());
    }

    private void validatePagination(CompileResult.Collector collector, Map<String, Object> config) {
        Object raw = config.get("pagination");
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> pagination = castMap(map);
        String mode = str(pagination, "mode");
        if (mode == null || mode.isBlank() || "NONE".equals(mode)) {
            return;
        }
        if (!PAGINATION_MODES.contains(mode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "pagination.mode",
                    "不支持的分页方式: " + mode, "可选值: " + String.join(" / ", PAGINATION_MODES));
            return;
        }

        if ("PAGE".equals(mode) && !isPresent(str(pagination, "pageParam"))) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "pagination.pageParam",
                    "页码分页必须指定页码参数名");
        }
        if ("CURSOR".equals(mode)) {
            if (!isPresent(str(pagination, "cursorPath"))) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "pagination.cursorPath",
                        "游标分页必须指定游标在响应里的路径");
            }
            if (!isPresent(str(pagination, "cursorParam"))) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "pagination.cursorParam",
                        "游标分页必须指定游标参数名");
            }
        }

        int maxPages = intValue(pagination.get("maxPages"), 0);
        if (maxPages <= 0) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "pagination.maxPages",
                    "分页拉取必须指定页数上限",
                    "没有上限的分页是一个无限循环的邀请:接口的 hasMore 恒为 true、"
                            + "或游标不前进时,任务会一直拉到超时 —— 而超时之前"
                            + "它已经打了对方几十万次");
        } else if (maxPages > MAX_PAGES_LIMIT) {
            collector.error(CompileStage.PLAN_OPTIMIZATION, "pagination.maxPages",
                    "页数上限不得超过 %d,当前 %d".formatted(MAX_PAGES_LIMIT, maxPages));
        }
    }

    private Map<String, Object> buildPlan(Map<String, Object> config, String method,
                                          Map<String, String> mappings, String writeMode) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "API_PARSE");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("dataSourceId", str(config, "sourceDataSourceId"));
        source.put("path", str(config, "path"));
        source.put("method", method);
        source.put("queryParams", config.getOrDefault("queryParams", Map.of()));
        // 认证头不在这里 —— 它由执行期从 Platform 的凭据托管取,
        // 编译进计划等于让一份 Token 躺在 physical_plan_json 里
        source.put("headers", config.getOrDefault("headers", Map.of()));
        source.put("body", str(config, "body"));
        source.put("jsonPath", str(config, "jsonPath"));
        source.put("pagination", config.getOrDefault("pagination", Map.of("mode", "NONE")));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
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
