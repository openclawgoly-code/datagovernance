package com.datagov.platform.dto;

import java.util.List;

/**
 * 角色视图。
 *
 * @param workspaceId 为 null 表示平台级内置角色,对所有空间可见但不可编辑
 */
public record RoleView(
        String id,
        String workspaceId,
        String code,
        String name,
        String description,
        boolean builtIn,
        List<String> permissionCodes
) {
}
