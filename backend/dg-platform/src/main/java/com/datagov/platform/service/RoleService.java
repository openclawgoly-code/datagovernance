package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.RoleView;
import com.datagov.platform.entity.PlatformEntities.Role;
import com.datagov.platform.entity.PlatformEntities.RolePermission;
import com.datagov.platform.entity.PlatformEntities.UserRole;
import com.datagov.platform.mapper.RoleMapper;
import com.datagov.platform.mapper.RolePermissionMapper;
import com.datagov.platform.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 角色管理 —— 功能 29。
 *
 * <p>角色分两级:{@code workspaceId} 为 null 的是平台级内置角色,对所有空间可见
 * 但不可编辑;非 null 的是某个空间自己定义的角色。这样安排是为了让新建空间
 * 立刻就有可用的角色,而不必每个空间都重新配一遍权限。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;

    public RoleService(RoleMapper roleMapper,
                       RolePermissionMapper rolePermissionMapper,
                       UserRoleMapper userRoleMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** 当前空间可用的角色 = 平台内置角色 + 本空间自定义角色。 */
    public List<RoleView> listAvailable() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                        .and(w -> w.isNull(Role::getWorkspaceId)
                                .or().eq(Role::getWorkspaceId, workspaceId))
                        .orderByAsc(Role::getCode)).stream()
                .map(role -> toView(role, listPermissionCodes(role.getId())))
                .toList();
    }

    public List<String> listPermissionCodes(String roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId)).stream()
                .map(RolePermission::getPermissionCode).toList();
    }

    @Transactional
    public RoleView create(String code, String name, String description,
                           List<String> permissionCodes, String operator) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireCodeAvailable(workspaceId, code, null);

        Instant now = Instant.now();
        Role role = new Role();
        role.setId(Ids.of("role"));
        role.setWorkspaceId(workspaceId);
        role.setCode(code.trim());
        role.setName(name.trim());
        role.setDescription(description);
        role.setBuiltIn(false);
        role.setCreatedAt(now);
        role.setCreatedBy(operator);
        role.setUpdatedAt(now);
        role.setUpdatedBy(operator);
        role.setDeleted(false);
        roleMapper.insert(role);

        replacePermissions(role.getId(), permissionCodes);
        return toView(role, permissionCodes);
    }

    @Transactional
    public RoleView update(String id, String name, String description,
                           List<String> permissionCodes, String operator) {
        Role role = requireEditable(id);
        role.setName(name.trim());
        role.setDescription(description);
        role.setUpdatedAt(Instant.now());
        role.setUpdatedBy(operator);
        roleMapper.updateById(role);

        replacePermissions(id, permissionCodes);
        return toView(role, permissionCodes);
    }

    @Transactional
    public void delete(String id) {
        Role role = requireEditable(id);

        // 角色被授予过就不能删:删掉之后那些用户会静默失去权限,
        // 而现象是"某人突然什么都点不了了",很难联想到是角色被删了。
        Long grants = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, id));
        if (grants != null && grants > 0) {
            throw new BizException(ErrorCode.PLT_ROLE_IN_USE,
                    "该角色已授予 %d 个用户,请先解除授予".formatted(grants));
        }

        roleMapper.deleteById(id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 内置角色对所有空间生效,允许任何一个空间改它等于让一个租户影响其他租户。 */
    private Role requireEditable(String id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "角色 " + id);
        }
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "内置角色不可修改或删除: " + role.getCode());
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (role.getWorkspaceId() != null && !role.getWorkspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCode.PLT_WORKSPACE_FORBIDDEN, "该角色不属于当前空间");
        }
        return role;
    }

    private void replacePermissions(String roleId, List<String> permissionCodes) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId));
        if (permissionCodes == null) {
            return;
        }
        for (String code : permissionCodes.stream().distinct().toList()) {
            RolePermission link = new RolePermission();
            link.setRoleId(roleId);
            link.setPermissionCode(code);
            rolePermissionMapper.insert(link);
        }
    }

    private void requireCodeAvailable(String workspaceId, String code, String excludeId) {
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getWorkspaceId, workspaceId)
                .eq(Role::getCode, code.trim())
                .ne(excludeId != null, Role::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.PLT_ROLE_CODE_DUPLICATED, code);
        }
    }

    private static RoleView toView(Role role, List<String> permissionCodes) {
        return new RoleView(role.getId(), role.getWorkspaceId(), role.getCode(),
                role.getName(), role.getDescription(),
                Boolean.TRUE.equals(role.getBuiltIn()), permissionCodes);
    }
}
