package com.datagov.common.tenant;

import java.util.Set;

/**
 * 一次请求的调用者身份与作用域。
 *
 * <p><b>术语警示(风险 R3)</b>: 这里的 {@code workspaceId} 对应平台功能菜单中的
 * 「空间管理」(功能 28)—— 它是<b>租户</b>,不是架构意义上的 Space(边界)。
 * 全代码库中 Space 一词只用于架构讨论与文档,任何运行时对象一律用 Workspace。
 *
 * @param userId        用户 ID
 * @param username      登录名
 * @param workspaceId   当前生效的空间(租户)ID;平台级操作(如空间管理本身)可为 null
 * @param platformAdmin 是否平台管理员 —— 可跨空间操作,是唯一被允许绕过空间隔离的身份
 * @param permissions   当前空间下已解析的菜单/操作权限码集合
 */
public record Caller(
        String userId,
        String username,
        String workspaceId,
        boolean platformAdmin,
        Set<String> permissions
) {

    public Caller {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return platformAdmin || permissions.contains(permission);
    }

    /** 派生出切换空间后的调用者身份;权限需由 Platform Space 重新解析后注入。 */
    public Caller withWorkspace(String newWorkspaceId, Set<String> resolvedPermissions) {
        return new Caller(userId, username, newWorkspaceId, platformAdmin, resolvedPermissions);
    }
}
