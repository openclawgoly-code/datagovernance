package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.platform.dto.MenuNode;
import com.datagov.platform.entity.PlatformEntities.Permission;
import com.datagov.platform.entity.PlatformEntities.Role;
import com.datagov.platform.entity.PlatformEntities.RolePermission;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.entity.PlatformEntities.UserRole;
import com.datagov.platform.mapper.PermissionMapper;
import com.datagov.platform.mapper.RoleMapper;
import com.datagov.platform.mapper.RolePermissionMapper;
import com.datagov.platform.mapper.UserMapper;
import com.datagov.platform.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限解析与菜单组装(功能 29)。
 *
 * <p>这是空间隔离的判定核心:同一个用户在不同空间下拥有的权限是不同的,
 * 因此权限解析<b>必须</b>同时接收 userId 与 workspaceId,不存在
 * "这个用户的权限"这种脱离空间的说法。
 */
@Service
public class PermissionService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    public PermissionService(UserMapper userMapper,
                             UserRoleMapper userRoleMapper,
                             RoleMapper roleMapper,
                             RolePermissionMapper rolePermissionMapper,
                             PermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    /**
     * 解析某用户在某空间下的有效权限码集合。
     *
     * <p>平台管理员拥有一切 —— 这是唯一被允许绕过空间隔离的身份,
     * 因为总得有人能创建第一个空间、能在所有空间都失联时进去救火。
     * 除此之外的所有身份都严格按 {@code pf_user_role}(限定 workspaceId)解析。
     */
    public Set<String> resolvePermissions(String userId, String workspaceId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return Set.of();
        }
        if (Boolean.TRUE.equals(user.getPlatformAdmin())) {
            return allPermissionCodes();
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            // 没有空间上下文的普通用户没有任何业务权限。
            // 返回空集而不是抛异常:登录后、选空间前就是这个状态。
            return Set.of();
        }

        List<UserRole> grants = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getWorkspaceId, workspaceId));
        if (grants.isEmpty()) {
            return Set.of();
        }

        List<String> roleIds = grants.stream().map(UserRole::getRoleId).toList();
        // 角色可能已被删除(逻辑删除),此时它授予的权限应当立即失效
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getId, roleIds));
        if (roles.isEmpty()) {
            return Set.of();
        }
        List<String> liveRoleIds = roles.stream().map(Role::getId).toList();

        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, liveRoleIds)).stream()
                .map(RolePermission::getPermissionCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 按权限码集合组装菜单树。
     *
     * <p>只保留 {@code type = MENU} 且用户有权的节点。父节点无权时整棵子树都不出现 ——
     * 否则会出现"看得见子菜单却找不到入口"的悬空节点。
     */
    public List<MenuNode> buildMenuTree(Set<String> permissionCodes) {
        List<Permission> menus = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                        .eq(Permission::getType, "MENU")).stream()
                .filter(p -> permissionCodes.contains(p.getCode()))
                .sorted(Comparator.comparing(
                        Permission::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<String, List<Permission>> byParent = menus.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getParentCode() == null ? "" : p.getParentCode(),
                        LinkedHashMapSupplier(), Collectors.toList()));

        return buildChildren("", byParent);
    }

    /** 权限全集(树形),供角色配置页勾选。 */
    public List<MenuNode> listAllPermissions() {
        List<Permission> all = permissionMapper.selectList(null).stream()
                .sorted(Comparator.comparing(
                        Permission::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<String, List<Permission>> byParent = all.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getParentCode() == null ? "" : p.getParentCode(),
                        LinkedHashMapSupplier(), Collectors.toList()));

        return buildChildren("", byParent);
    }

    public Set<String> allPermissionCodes() {
        return permissionMapper.selectList(null).stream()
                .map(Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private List<MenuNode> buildChildren(String parentCode, Map<String, List<Permission>> byParent) {
        List<Permission> children = byParent.get(parentCode);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<MenuNode> nodes = new ArrayList<>(children.size());
        for (Permission permission : children) {
            nodes.add(new MenuNode(
                    permission.getCode(),
                    permission.getName(),
                    permission.getRoutePath(),
                    permission.getIcon(),
                    permission.getSortOrder(),
                    permission.getOwnerSpace(),
                    buildChildren(permission.getCode(), byParent)));
        }
        return nodes;
    }

    /** 保持分组顺序,让菜单顺序可预期 —— HashMap 的顺序会让菜单每次启动都可能不同。 */
    private static <K, V> java.util.function.Supplier<Map<K, List<V>>> LinkedHashMapSupplier() {
        return java.util.LinkedHashMap::new;
    }
}
