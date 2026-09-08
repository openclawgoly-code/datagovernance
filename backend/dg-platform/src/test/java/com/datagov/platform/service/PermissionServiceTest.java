package com.datagov.platform.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限解析 —— 空间隔离的判定核心。
 */
@DisplayName("权限解析")
class PermissionServiceTest {

    private static final String WS_A = "ws_alpha";
    private static final String WS_B = "ws_beta";

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private RolePermissionMapper rolePermissionMapper;
    private PermissionMapper permissionMapper;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleMapper = mock(RoleMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);
        permissionMapper = mock(PermissionMapper.class);
        service = new PermissionService(userMapper, userRoleMapper, roleMapper,
                rolePermissionMapper, permissionMapper);

        when(permissionMapper.selectList(any())).thenReturn(allPermissions());
    }

    @Test
    @DisplayName("平台管理员拥有全部权限,不受空间限制")
    void platformAdminGetsEverything() {
        when(userMapper.selectById("usr_admin")).thenReturn(user("usr_admin", true, "ACTIVE"));

        Set<String> permissions = service.resolvePermissions("usr_admin", WS_A);

        // 总得有人能创建第一个空间、能在所有空间都失联时进去救火
        assertThat(permissions).contains(
                "menu:metadata", "metadata:datasource:create", "platform:workspace:create");
    }

    @Test
    @DisplayName("普通用户按当前空间下的角色解析权限")
    void normalUserResolvesByRoleInWorkspace() {
        when(userMapper.selectById("usr_1")).thenReturn(user("usr_1", false, "ACTIVE"));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(WS_A, "usr_1", "role_dev")));
        when(roleMapper.selectList(any())).thenReturn(List.of(role("role_dev")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                rolePermission("role_dev", "menu:metadata"),
                rolePermission("role_dev", "metadata:datasource:read")));

        Set<String> permissions = service.resolvePermissions("usr_1", WS_A);

        assertThat(permissions).containsExactlyInAnyOrder(
                "menu:metadata", "metadata:datasource:read");
        assertThat(permissions).doesNotContain("platform:workspace:create");
    }

    @Test
    @DisplayName("在未被授权的空间下解析出空权限集")
    void unauthorizedWorkspaceYieldsNoPermissions() {
        when(userMapper.selectById("usr_1")).thenReturn(user("usr_1", false, "ACTIVE"));
        // 该用户在 WS_B 下没有任何角色授予
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolvePermissions("usr_1", WS_B)).isEmpty();
    }

    @Test
    @DisplayName("被停用的用户没有任何权限,即便他是平台管理员")
    void disabledUserHasNoPermissions() {
        when(userMapper.selectById("usr_admin")).thenReturn(user("usr_admin", true, "DISABLED"));
        assertThat(service.resolvePermissions("usr_admin", WS_A)).isEmpty();
    }

    @Test
    @DisplayName("未选空间的普通用户没有业务权限,但不报错")
    void noWorkspaceYieldsEmptySetNotError() {
        when(userMapper.selectById("usr_1")).thenReturn(user("usr_1", false, "ACTIVE"));
        // 登录后、选空间前就是这个状态,属于正常流程而非异常
        assertThat(service.resolvePermissions("usr_1", null)).isEmpty();
    }

    @Test
    @DisplayName("角色被删除后,它授予的权限立即失效")
    void deletedRoleRevokesPermissionsImmediately() {
        when(userMapper.selectById("usr_1")).thenReturn(user("usr_1", false, "ACTIVE"));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(WS_A, "usr_1", "role_gone")));
        // 逻辑删除后 selectList 查不到这个角色
        when(roleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolvePermissions("usr_1", WS_A)).isEmpty();
    }

    @Test
    @DisplayName("菜单树只包含用户有权的节点,且父节点无权时整棵子树消失")
    void menuTreeFiltersByPermission() {
        List<MenuNode> tree = service.buildMenuTree(Set.of(
                "menu:metadata", "menu:metadata:datasource"));

        assertThat(tree).extracting(MenuNode::code).containsExactly("menu:metadata");
        assertThat(tree.get(0).children()).extracting(MenuNode::code)
                .containsExactly("menu:metadata:datasource");

        // 「基础配置」及其子项没有权限,整棵子树不该出现 ——
        // 否则会出现看得见子菜单却找不到入口的悬空节点
        assertThat(tree).extracting(MenuNode::code).doesNotContain("menu:settings");
    }

    @Test
    @DisplayName("菜单节点带 ownerSpace,便于追溯「这个菜单归哪个 Space 管」")
    void menuCarriesOwnerSpace() {
        List<MenuNode> tree = service.buildMenuTree(Set.of("menu:metadata", "menu:settings"));

        // 这一列编码了「基础配置菜单横跨三个 Space」这条推导结论
        assertThat(tree).extracting(MenuNode::ownerSpace)
                .containsExactlyInAnyOrder("METADATA", "PLATFORM");
    }

    @Test
    @DisplayName("空权限集得到空菜单树")
    void emptyPermissionsYieldEmptyMenu() {
        assertThat(service.buildMenuTree(Set.of())).isEmpty();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static List<Permission> allPermissions() {
        return List.of(
                permission("menu:metadata", "数据源管理", "MENU", null, 20, "METADATA"),
                permission("menu:metadata:datasource", "数据源", "MENU", "menu:metadata", 21, "METADATA"),
                permission("metadata:datasource:read", "查看数据源", "ACTION", "menu:metadata:datasource", 1, "METADATA"),
                permission("metadata:datasource:create", "新建数据源", "ACTION", "menu:metadata:datasource", 2, "METADATA"),
                permission("menu:settings", "基础配置", "MENU", null, 90, "PLATFORM"),
                permission("menu:settings:workspace", "空间管理", "MENU", "menu:settings", 91, "PLATFORM"),
                permission("platform:workspace:create", "新建空间", "ACTION", "menu:settings:workspace", 2, "PLATFORM"));
    }

    private static Permission permission(String code, String name, String type,
                                         String parent, int sort, String ownerSpace) {
        Permission permission = new Permission();
        permission.setCode(code);
        permission.setName(name);
        permission.setType(type);
        permission.setParentCode(parent);
        permission.setSortOrder(sort);
        permission.setOwnerSpace(ownerSpace);
        permission.setBuiltIn(true);
        return permission;
    }

    private static User user(String id, boolean platformAdmin, String status) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setPlatformAdmin(platformAdmin);
        user.setStatus(status);
        return user;
    }

    private static UserRole grant(String workspaceId, String userId, String roleId) {
        UserRole grant = new UserRole();
        grant.setWorkspaceId(workspaceId);
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        return grant;
    }

    private static Role role(String id) {
        Role role = new Role();
        role.setId(id);
        role.setCode(id);
        return role;
    }

    private static RolePermission rolePermission(String roleId, String code) {
        RolePermission link = new RolePermission();
        link.setRoleId(roleId);
        link.setPermissionCode(code);
        return link;
    }
}
