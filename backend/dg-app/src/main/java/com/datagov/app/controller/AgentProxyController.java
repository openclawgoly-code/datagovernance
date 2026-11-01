package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pi agent 服务的 Java 侧代理。
 *
 * <p>前端只访问本控制器的 {@code /api/v1/insight/agent/**}，由平台鉴权后把
 * workspace/username 注入 Node 服务；Node 服务只监听 127.0.0.1。SSE 用
 * ResponseBodyEmitter 原样转发字节流，避免在 Java 侧重新拼 SSE 语义。
 */
@RestController
@RequestMapping("/api/v1/insight/agent")
@Tag(name = "智能问数 Agent", description = "多轮对话与 Pi agent 工具过程")
public class AgentProxyController {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper;

    @Value("${dg.insight.agent-base-url:http://127.0.0.1:8090}")
    private String agentBaseUrl;

    public AgentProxyController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/conversations")
    @RequirePermission("insight:ask")
    @Operation(summary = "Agent 会话列表")
    public ResponseEntity<String> list() {
        Caller caller = WorkspaceContext.require();
        String path = "/conversations?workspaceId=%s&username=%s".formatted(
                encode(caller.workspaceId()), encode(caller.username()));
        return forward("GET", path, null);
    }

    @PostMapping("/conversations")
    @RequirePermission("insight:ask")
    @Operation(summary = "新建 Agent 会话")
    public ResponseEntity<String> create(@RequestBody(required = false) Map<String, Object> body) {
        Caller caller = WorkspaceContext.require();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        payload.put("workspaceId", caller.workspaceId());
        payload.put("userId", caller.userId());
        payload.put("platformAdmin", caller.platformAdmin());
        payload.put("username", caller.username());
        return forward("POST", "/conversations", payload);
    }

    @GetMapping("/conversations/{id}")
    @RequirePermission("insight:ask")
    @Operation(summary = "Agent 会话详情")
    public ResponseEntity<String> detail(@PathVariable String id) {
        requireOwnership(id);
        return forward("GET", "/conversations/" + encodePath(id), null);
    }

    @PostMapping("/conversations/{id}/messages")
    @RequirePermission("insight:ask")
    @Operation(summary = "发送消息")
    public ResponseEntity<String> send(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        requireOwnership(id);
        Caller caller = WorkspaceContext.require();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // 旧会话可能还没存过 userId；每次发送都补齐，工具端点才能按真实用户鉴权。
        payload.put("userId", caller.userId());
        payload.put("platformAdmin", caller.platformAdmin());
        payload.put("username", caller.username());
        return forward("POST", "/conversations/" + encodePath(id) + "/messages", payload);
    }

    @PostMapping("/conversations/{id}/abort")
    @RequirePermission("insight:ask")
    @Operation(summary = "停止当前 Agent 轮次")
    public ResponseEntity<String> abort(@PathVariable String id) {
        requireOwnership(id);
        return forward("POST", "/conversations/" + encodePath(id) + "/abort", Map.of());
    }

    @GetMapping(value = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("insight:ask")
    @Operation(summary = "Agent 事件流", description = "原样转发 Node 服务的 SSE 字节流")
    public ResponseBodyEmitter events(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") long after) {
        requireOwnership(id);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        Thread.ofVirtual().name("agent-sse-" + id).start(() -> {
            try (InputStream input = openEvents(id, after);
                 java.io.Reader reader = new java.io.InputStreamReader(
                         input, java.nio.charset.StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    emitter.send(new String(buffer, 0, read));
                }
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private InputStream openEvents(String id, long after) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(agentBaseUrl + "/conversations/" + encodePath(id)
                                + "/events?after=" + after))
                .timeout(Duration.ofHours(1))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "Agent 事件流不可用: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void requireOwnership(String id) {
        Caller caller = WorkspaceContext.require();
        ResponseEntity<String> response = forward("GET", "/conversations/" + encodePath(id), null);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "Agent 会话");
        }
        try {
            Map<String, Object> data = objectMapper.readValue(response.getBody(),
                    new TypeReference<>() {
                    });
            boolean sameWorkspace = caller.workspaceId() != null
                    && caller.workspaceId().equals(data.get("workspaceId"));
            boolean sameUser = caller.username() != null
                    && caller.username().equals(data.get("username"));
            if (!sameWorkspace || !sameUser) {
                // 不暴露“存在但不属于你”，统一按不存在处理。
                throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "Agent 会话");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "Agent 会话响应无法解析", ex.getMessage(), ex);
        }
    }

    private ResponseEntity<String> forward(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(agentBaseUrl + path))
                    .timeout(Duration.ofSeconds(150));
            if ("POST".equals(method)) {
                String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
                builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(response.body());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "Agent 服务不可用: " + ex.getMessage(), null, ex);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
