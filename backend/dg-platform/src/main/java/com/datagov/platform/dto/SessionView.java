package com.datagov.platform.dto;

import java.util.List;
import java.util.Set;

/**
 * 当前会话详情({@code GET /auth/me})。供前端刷新页面后恢复状态。
 */
public record SessionView(
        UserView user,
        WorkspaceView workspace,
        List<WorkspaceView> workspaces,
        Set<String> permissions,
        List<MenuNode> menus
) {
}
