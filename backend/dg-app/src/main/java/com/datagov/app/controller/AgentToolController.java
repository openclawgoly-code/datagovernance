package com.datagov.app.controller;

import com.datagov.app.insight.InsightQueryService;
import com.datagov.app.insight.InsightQueryService.Answer;
import com.datagov.app.insight.InsightQueryService.AskRequest;
import com.datagov.app.insight.SuperSonicClient;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Pi agent 服务的内部工具端点。
 *
 * <p>Node Agent 不持有平台 JWT，也不直接连数据库；它用独立服务令牌调用这里，
 * 由本控制器把 workspace/user 还原成 {@link WorkspaceContext}，再复用平台的
 * 问数、脱敏与审计链路。端点只监听本机（由部署层保证），并额外校验服务令牌。
 */
@RestController
@RequestMapping("/api/v1/internal/agent")
@Tag(name = "Agent 内部工具", description = "仅供本地 Pi agent 服务调用")
public class AgentToolController {

    private final InsightQueryService queryService;

    @Value("${dg.insight.agent-token:}")
    private String agentToken;

    public AgentToolController(InsightQueryService queryService) {
        this.queryService = queryService;
    }

    public record AskToolRequest(String workspaceId, String username, String question,
                                 Long dataSetId) {
    }

    @GetMapping("/datasets")
    @Operation(summary = "Agent 工具：可用数据集")
    public ApiResponse<List<SuperSonicClient.DatasetOption>> datasets(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestParam String workspaceId,
            @RequestParam String username) {
        requireToken(token);
        return ApiResponse.ok(withCaller(workspaceId, username, queryService::datasets));
    }

    @PostMapping("/ask")
    @Operation(summary = "Agent 工具：自然语言数据查询")
    public ApiResponse<Answer> ask(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestBody AskToolRequest request) {
        requireToken(token);
        if (request == null || request.workspaceId() == null
                || request.username() == null || request.question() == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "workspaceId / username / question 必填");
        }
        return ApiResponse.ok(withCaller(request.workspaceId(), request.username(),
                () -> queryService.ask(new AskRequest(request.question(),
                        request.dataSetId(), null))));
    }

    private void requireToken(String token) {
        if (agentToken == null || agentToken.isBlank()) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                    "未配置 dg.insight.agent-token，Agent 工具端点不可用");
        }
        if (token == null || !agentToken.equals(token)) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "Agent 服务令牌无效");
        }
    }

    /**
     * 工具调用没有用户 JWT，但需要复用需要 WorkspaceContext 的服务。
     * 只授予 ask/read 权限，且每个请求结束立刻恢复原上下文。
     */
    private <T> T withCaller(String workspaceId, String username,
                             java.util.function.Supplier<T> action) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BizException(ErrorCode.PLT_MISSING_WORKSPACE, "Agent 工具请求缺少 workspaceId");
        }
        Caller previous = WorkspaceContext.get();
        Caller caller = new Caller(
                "agent:" + username,
                username,
                workspaceId,
                false,
                Set.of("insight:ask", "insight:read"));
        WorkspaceContext.set(caller);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                WorkspaceContext.clear();
            } else {
                WorkspaceContext.set(previous);
            }
        }
    }
}
