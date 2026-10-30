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
import java.util.concurrent.ConcurrentHashMap;

/**
 * SuperSonic 的薄客户端。
 *
 * <p>按“平台用户映射到的 SuperSonic 账号”缓存 token 和聊天会话。缓存键含
 * 口令摘要:凭据轮换后旧 session 自然失效,不会长期拿旧口令。
 *
 * <p>口令只用于登录,不落日志、不进缓存明文字段。
 */
@Component
public class SuperSonicClient {

    private static final Logger log = LoggerFactory.getLogger(SuperSonicClient.class);
    private static final String CHAT_NAME = "platform-insight";

    private final InsightProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

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

    public record DatasetOption(long id, String name, String domain) {
    }

    private static final class Session {
        private volatile String token;
        private volatile Instant tokenExpiresAt = Instant.EPOCH;
        private volatile Integer chatId;
        private volatile Integer chatAgentId;
    }

    public List<DatasetOption> datasets() {
        return datasets(properties.getUsername(), properties.getPassword());
    }

    public List<DatasetOption> datasets(String username, String password) {
        requireConfigured(username, password);
        Map<String, Object> response = get("/api/chat/conf/getDomainDataSetTree",
                token(username, password));
        if (!Integer.valueOf(200).equals(response.get("code"))
                || !(response.get("data") instanceof List<?> roots)) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "读取数据集失败: " + response.get("msg"));
        }
        List<DatasetOption> options = new java.util.ArrayList<>();
        for (Object root : roots) {
            if (root instanceof Map<?, ?> domain) {
                collectDatasets(domain, string(domain.get("name")), options);
            }
        }
        return options;
    }

    private void collectDatasets(Map<?, ?> node, String domain, List<DatasetOption> options) {
        if ("DATASET".equals(string(node.get("type"))) && node.get("id") instanceof Number id) {
            options.add(new DatasetOption(id.longValue(), string(node.get("name")), domain));
            return;
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    collectDatasets(childMap, domain, options);
                }
            }
        }
    }

    public AskResult ask(String question, int agentId, long datasetId) {
        return ask(question, agentId, datasetId, properties.getUsername(), properties.getPassword());
    }

    public AskResult ask(String question, int agentId, long datasetId,
                         String username, String password) {
        requireConfigured(username, password);
        String accessToken = token(username, password);
        int sessionId = chatId(username, password, accessToken, agentId);
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

    private void requireConfigured(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                    "未配置 SuperSonic 账号或口令");
        }
    }

    private int chatId(String username, String password, String accessToken, int agentId) {
        Session session = session(username, password);
        synchronized (session) {
            if (session.chatId != null && Integer.valueOf(agentId).equals(session.chatAgentId)) {
                return session.chatId;
            }
            String encoded = URLEncoder.encode(CHAT_NAME, StandardCharsets.UTF_8);
            Map<String, Object> created = post(
                    "/api/chat/manage/save?chatName=" + encoded + "&agentId=" + agentId,
                    null, accessToken);
            if (!Integer.valueOf(200).equals(created.get("code"))) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "创建问数会话失败: " + created.get("msg"));
            }
            Map<String, Object> chats = get(
                    "/api/chat/manage/getAll?agentId=" + agentId, accessToken);
            if (!(chats.get("data") instanceof List<?> list)) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED, "读取问数会话失败");
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> chat
                        && CHAT_NAME.equals(string(chat.get("chatName")))
                        && chat.get("chatId") instanceof Number id) {
                    session.chatId = id.intValue();
                    session.chatAgentId = agentId;
                    return session.chatId;
                }
            }
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED, "找不到刚创建的问数会话");
        }
    }

    private String token(String username, String password) {
        Session session = session(username, password);
        if (session.token != null && Instant.now().isBefore(session.tokenExpiresAt)) {
            return session.token;
        }
        synchronized (session) {
            if (session.token != null && Instant.now().isBefore(session.tokenExpiresAt)) {
                return session.token;
            }
            Map<String, Object> response = post("/api/auth/user/login",
                    Map.of("name", username, "password", password), null);
            if (!Integer.valueOf(200).equals(response.get("code"))
                    || !(response.get("data") instanceof String value)) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "SuperSonic 登录失败: " + response.get("msg"));
            }
            session.token = value;
            session.tokenExpiresAt = Instant.now().plus(Duration.ofMinutes(10));
            log.debug("SuperSonic 登录成功 user={}", username);
            return session.token;
        }
    }

    private Session session(String username, String password) {
        String key = username + "|" + Integer.toHexString(
                (password == null ? "" : password).hashCode());
        return sessions.computeIfAbsent(key, ignored -> new Session());
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
