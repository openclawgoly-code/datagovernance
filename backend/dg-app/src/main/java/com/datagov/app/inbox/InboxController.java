package com.datagov.app.inbox;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.app.inbox.InboxService.InboxPage;
import com.datagov.app.inbox.InboxService.InboxStateView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/inbox")
@Tag(name = "治理收件箱", description = "执行、告警与敏感标签的统一待处理队列")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    @RequirePermission("governance:inbox:read")
    @Operation(summary = "治理收件箱", description = "只返回当前用户有来源读权限的条目")
    public ApiResponse<InboxPage> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(inboxService.list(page, size, sourceType, state, severity, keyword));
    }

    @PostMapping("/{sourceType}/{sourceId}/dismiss")
    @RequirePermission("governance:inbox:manage")
    @Operation(summary = "移出治理收件箱", description = "不改变来源对象状态")
    public ApiResponse<InboxStateView> dismiss(@PathVariable String sourceType,
                                               @PathVariable String sourceId) {
        return ApiResponse.ok(inboxService.setState(sourceType, sourceId, true));
    }

    @PostMapping("/{sourceType}/{sourceId}/reopen")
    @RequirePermission("governance:inbox:manage")
    @Operation(summary = "恢复到治理收件箱")
    public ApiResponse<InboxStateView> reopen(@PathVariable String sourceType,
                                              @PathVariable String sourceId) {
        return ApiResponse.ok(inboxService.setState(sourceType, sourceId, false));
    }
}
