package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.rule.RuleInterpreter;
import com.datagov.runtime.spi.ExecutionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口解析入库(功能 13)。
 *
 * <p>按配置调用 HTTP 接口,把返回的 JSON 数组写进目标表。支持页码与游标两种分页。
 *
 * <p><b>认证由凭据装配器注入</b>(功能 13 的备注:「Platform(凭据)」)——
 * 物理计划里没有 Token,它在执行期才从 Platform 的凭据托管取出来。编译进计划
 * 等于让一份 Token 躺在 {@code ctl_job_definition.physical_plan_json} 里,
 * 而任务定义是所有人都能看的。
 */
@Component
public class ApiParseRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiParseRunner.class);

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;
    private final RowWriter rowWriter;
    private final RuleResolver ruleResolver;
    private final ObjectMapper objectMapper;

    public ApiParseRunner(DataSourceMapper dataSourceMapper,
                          ConnectionConfigAssembler assembler,
                          RowWriter rowWriter,
                          RuleResolver ruleResolver,
                          ObjectMapper objectMapper) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
        this.rowWriter = rowWriter;
        this.ruleResolver = ruleResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.API_PARSE;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> source = section(context, "source");
        Map<String, Object> target = section(context, "target");
        Map<String, String> mappings = stringMap(context.plan().get("fieldMappings"));
        Map<String, List<RuleInterpreter.Rule>> fieldRules =
                ruleResolver.resolve(context.workspaceId(), context.plan().get("fieldRules"));

        DataSourceEntity sourceDs = requireDataSource(str(source, "dataSourceId"), "接口");
        DataSourceEntity targetDs = requireDataSource(str(target, "dataSourceId"), "目标");
        ConnectionConfig config = assembler.assemble(sourceDs, sourceDs.getWorkspaceId());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                // 不自动跟随重定向:接口返回 302 多半意味着路径配错了或被网关
                // 拦到了登录页,静默跟过去只会解析出一堆无意义的 HTML
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        List<String> sourceFields = List.copyOf(mappings.keySet());
        List<String> targetColumns = sourceFields.stream().map(mappings::get).toList();

        long rowsRead = 0;
        try (RowWriter.Session session = rowWriter.open(targetDs, str(target, "database"),
                str(target, "schema"), str(target, "table"), sourceFields, targetColumns,
                fieldRules, str(target, "writeMode"), intValue(target.get("batchSize"), 1000),
                context)) {

            rowsRead = fetchAll(client, config, source, session, context);
            long written = session.finish();
            log.info("接口解析完成 execution={} 读{}行 写{}行",
                    context.executionId(), rowsRead, written);
            return RunResult.of(rowsRead, written, 0);
        }
    }

    /** 按分页配置反复拉取,直到没有下一页或到达上限。 */
    private long fetchAll(HttpClient client, ConnectionConfig config, Map<String, Object> source,
                          RowWriter.Session session, RunContext context) throws Exception {
        Map<String, Object> pagination = source.get("pagination") instanceof Map<?, ?> m
                ? castMap(m) : Map.of("mode", "NONE");
        String mode = String.valueOf(pagination.getOrDefault("mode", "NONE"));
        int maxPages = "NONE".equals(mode) ? 1 : intValue(pagination.get("maxPages"), 1);

        long total = 0;
        String cursor = null;
        for (int page = 1; page <= maxPages; page++) {
            context.throwIfCanceled();

            Map<String, String> query = new LinkedHashMap<>(stringMap(source.get("queryParams")));
            if ("PAGE".equals(mode)) {
                query.put(String.valueOf(pagination.get("pageParam")), String.valueOf(page));
                String sizeParam = str(pagination, "sizeParam");
                if (sizeParam != null && !sizeParam.isBlank()) {
                    query.put(sizeParam, String.valueOf(intValue(pagination.get("size"), 100)));
                }
            } else if ("CURSOR".equals(mode) && cursor != null) {
                query.put(String.valueOf(pagination.get("cursorParam")), cursor);
            }

            JsonNode root = request(client, config, source, query);
            JsonNode array = extract(root, str(source, "jsonPath"));
            int pageRows = 0;
            for (JsonNode item : array) {
                context.throwIfCanceled();
                session.write(FileParseRunner.toRow(item));
                pageRows++;
            }
            total += pageRows;
            context.progress().accept(
                    new ExecutionEngine.EngineMetric(total, session.rowsWritten(), 0));
            log.info("第 {}/{} 页拉到 {} 行(累计 {})", page, maxPages, pageRows, total);

            // 空页即结束。这是唯一在所有接口上都成立的终止条件 ——
            // hasMore 之类的字段各家命名不同,而空页人人都有。
            if (pageRows == 0) {
                break;
            }
            if ("CURSOR".equals(mode)) {
                JsonNode cursorNode = extractNode(root, str(pagination, "cursorPath"));
                String next = cursorNode == null || cursorNode.isNull()
                        ? null : cursorNode.asText();
                if (next == null || next.isBlank() || next.equals(cursor)) {
                    // 游标不前进就停:再拉一次会拿到同一页,无限循环
                    break;
                }
                cursor = next;
            } else if ("NONE".equals(mode)) {
                break;
            }
        }
        return total;
    }

    private JsonNode request(HttpClient client, ConnectionConfig config,
                             Map<String, Object> source, Map<String, String> query)
            throws Exception {
        String url = buildUrl(config, str(source, "path"), query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.readTimeoutMillis()))
                .header("Accept", "application/json");

        stringMap(source.get("headers")).forEach(builder::header);
        // 认证头在这里注入,而不是在计划里 —— 计划是持久化的,Token 不该落进去
        applyAuth(builder, config);

        String method = String.valueOf(source.getOrDefault("method", "GET"));
        String body = str(source, "body");
        HttpRequest httpRequest = "POST".equals(method)
                ? builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : body, StandardCharsets.UTF_8)).build()
                : builder.GET().build();

        HttpResponse<String> response = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) {
            // 响应体截断:出错时接口常返回一整页 HTML,原样塞进错误信息会把
            // 执行记录撑爆,而排障需要的只是开头那几行
            String preview = response.body() == null ? ""
                    : response.body().substring(0, Math.min(500, response.body().length()));
            throw new IllegalStateException(
                    "接口返回 HTTP %d: %s".formatted(response.statusCode(), preview));
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * 注入认证。
     *
     * <p>三种方式对应 {@code CredentialSecret.AuthType}:无认证、Basic、Token。
     * 具体用哪一种由数据源绑定的凭据决定,这里只负责翻译成 HTTP 头。
     */
    private void applyAuth(HttpRequest.Builder builder, ConnectionConfig config) {
        if (config.password() == null || config.password().isBlank()) {
            return;
        }
        if (config.username() != null && !config.username().isBlank()) {
            String basic = java.util.Base64.getEncoder().encodeToString(
                    "%s:%s".formatted(config.username(), config.password())
                            .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        } else {
            // 没有用户名 = Token 认证。头名可通过数据源的扩展参数改 ——
            // 不是所有接口都用标准的 Authorization
            String headerName = config.property("authHeader", "Authorization");
            String prefix = config.property("authPrefix", "Bearer ");
            builder.header(headerName, prefix + config.password());
        }
    }

    private String buildUrl(ConnectionConfig config, String path, Map<String, String> query) {
        String base = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
        String suffix = path == null || path.isBlank() ? ""
                : (path.startsWith("/") ? path : "/" + path);
        StringBuilder url = new StringBuilder(base).append(suffix);

        if (!query.isEmpty()) {
            List<String> pairs = new ArrayList<>(query.size());
            query.forEach((k, v) -> pairs.add(
                    URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
            // 路径里已经有 ? 时用 & 续上 —— 用户在 path 里写死几个参数是常见的
            url.append(suffix.contains("?") ? '&' : '?').append(String.join("&", pairs));
        }
        return url.toString();
    }

    /** 按 jsonPath 取出数组。 */
    private JsonNode extract(JsonNode root, String jsonPath) {
        JsonNode node = extractNode(root, jsonPath);
        if (node == null || !node.isArray()) {
            throw new IllegalStateException("JSON 路径「%s」指向的不是数组"
                    .formatted(jsonPath == null || jsonPath.isBlank() ? "(顶层)" : jsonPath));
        }
        return node;
    }

    static JsonNode extractNode(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonNode node = root;
        for (String segment : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.path(segment);
        }
        return node.isMissingNode() ? null : node;
    }

    @Override
    public String classify(Exception e) {
        if (e instanceof java.sql.SQLException) {
            return "DAT_QUERY_FAILED";
        }
        return e instanceof java.io.IOException || e instanceof InterruptedException
                ? "DAT_CONNECT_FAILED" : "SYS_INTERNAL_ERROR";
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
