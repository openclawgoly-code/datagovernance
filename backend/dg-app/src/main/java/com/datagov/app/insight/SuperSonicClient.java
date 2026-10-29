package com.datagov.app.insight;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SuperSonic 的薄客户端。
 *
 * <p>只负责三件事:登录、拿一个会话、发问。语义层、SQL 生成、图表选择都在
 * SuperSonic 里,平台不重复实现 —— 否则两套口径迟早会分叉。
 */
@Component
public class SuperSonicClient {

    private static final Logger log = LoggerFactory.getLogger(SuperSonicClient.class);
    private static final String CHAT_NAME = "platform-insight";

    private final InsightProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private volatile Integer chatId;

    public SuperSonicClient(InsightProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record Column(String name, String bizName, String type) {
    }

    public record AskResult(String queryId, String state, String sql,
                            List<Column> columns, List<Map<String, Object>> rows,
                            String summary, String error) {
    }

    public AskResult ask(String question, int agentId, long datasetId) {
        requireConfigured();
        String accessToken = token();
        int sessionId = chatId(accessToken);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queryText", question);
        payload.put("agentId", agentId);
        payload.put("dataSetId", datasetId);
        payload.put("chatId", sessionId);
        payload.put("saveAnswer", false);

        Map<String, Object> response = post("/api/chat/query/", payload, accessToken);
        if (!Integer.valueOf(200).equals(response.get("code"))) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "问数服务返回错误: " + response.get("msg"));
        }
        Object dataObject = response.get("data");
        if (!(dataObject instanceof Map<?, ?> data)) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED, "问数服务返回空结果");
        }
        return new AskResult(
                string(data.get("queryId")),
                string(data.get("queryState")),
                string(data.get("querySql")),
                columns(data.get("queryColumns")),
                rows(data.get("queryResults")),
                string(data.get("textSummary")),
                string(data.get("errorMsg")));
    }

    private void requireConfigured() {
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                    "未配置 SuperSonic 服务账号口令(DG_SUPERSONIC_PASSWORD)");
        }
    }

    private int chatId(String accessToken) {
        if (chatId != null) {
            return chatId;
        }
        synchronized (this) {
            if (chatId != null) {
                return chatId;
            }
            String encoded = URLEncoder.encode(CHAT_NAME, StandardCharsets.UTF_8);
            Map<String, Object> created = post(
                    "/api/chat/manage/save?chatName=" + encoded + "&agentId=" + properties.getAgentId(),
                    null, accessToken);
            if (!Integer.valueOf(200).equals(created.get("code"))) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "创建问数会话失败: " + created.get("msg"));
            }
            Map<String, Object> chats = get(
                    "/api/chat/manage/getAll?agentId=" + properties.getAgentId(), accessToken);
            if (!(chats.get("data") instanceof List<?> list)) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED, "读取问数会话失败");
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> chat
                        && CHAT_NAME.equals(string(chat.get("chatName")))
                        && chat.get("chatId") instanceof Number id) {
                    chatId = id.intValue();
                    return chatId;
                }
            }
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED, "找不到刚创建的问数会话");
        }
    }

    private String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        synchronized (this) {
            if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
                return token;
            }
            Map<String, Object> response = post("/api/auth/user/login",
                    Map.of("name", properties.getUsername(), "password", properties.getPassword()),
                    null);
            if (!Integer.valueOf(200).equals(response.get("code"))
                    || !(response.get("data") instanceof String value)) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "SuperSonic 登录失败: " + response.get("msg"));
            }
            token = value;
            // SuperSonic 默认 token 20 小时;这里保守缓存 10 分钟,口令轮换后最多
            // 10 分钟生效。比每次问数都登录少一跳,又不会长期握住旧凭证。
            tokenExpiresAt = Instant.now().plus(Duration.ofMinutes(10));
            return token;
        }
    }

    private Map<String, Object> get(String path, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return send(request);
    }

    private Map<String, Object> post(String path, Object body, String accessToken) {
        try {
            String json = body == null ? "" : objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (accessToken != null) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            return send(builder.build());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "调用 SuperSonic 失败: " + e.getMessage(), null, e);
        }
    }

    private Map<String, Object> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "SuperSonic HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "SuperSonic 调用失败: " + e.getMessage(), null, e);
        }
    }

    private URI uri(String path) {
        String base = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return URI.create(base + path);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<Column> columns(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> new Column(
                        string(item.get("name")),
                        string(item.get("bizName")),
                        string(item.get("type"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }
}
