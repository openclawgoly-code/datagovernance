package com.datagov.platform.dto;

import java.util.List;
import java.util.Set;

/**
 * 登录 / 切换空间的返回。
 *
 * <p>一次性把令牌、可访问空间、当前空间、权限码与菜单树全给前端,
 * 省掉登录后立刻再打一次 {@code /auth/me} 的往返。
 *
 * @param currentWorkspaceId 只有一个可访问空间时自动选中;多个时为 null,
 *                           前端应引导用户选择
 */
public record LoginResult(
        String token,
        UserView user,
        List<WorkspaceView> workspaces,
        String currentWorkspaceId,
        Set<String> permissions,
        List<MenuNode> menus
) {
}
