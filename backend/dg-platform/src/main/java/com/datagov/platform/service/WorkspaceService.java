package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.crypto.SecretCipher;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.platform.domain.WorkspaceLifecycle;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.entity.PlatformEntities.Role;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.entity.PlatformEntities.UserRole;
import com.datagov.platform.entity.PlatformEntities.Workspace;
import com.datagov.platform.entity.PlatformEntities.WorkspaceMember;
import com.datagov.platform.entity.PlatformEntities.WorkspaceSecret;
import com.datagov.platform.entity.WorkspaceStatus;
import com.datagov.platform.mapper.RoleMapper;
import com.datagov.platform.mapper.UserMapper;
import com.datagov.platform.mapper.UserRoleMapper;
import com.datagov.platform.mapper.WorkspaceMapper;
import com.datagov.platform.mapper.WorkspaceMemberMapper;
import com.datagov.platform.mapper.WorkspaceSecretMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 空间(租户)管理 —— 功能 28。
 *
 * <p><b>术语警示</b>:这里的「空间」是租户,不是架构中的 Space。
 * 它是平台内每个对象的顶层归属键 —— 数据源、任务、工作流、告警、审计
 * 全部按它隔离。把它独立成一个 Space 的理由正在于此:一个为所有其他 Space
 * 定义数据作用域的概念必须显式,否则每个模块会各自发明一套租户模型。
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 内置空间管理员角色的 code(功能 28「空间管理员」)。
     *
     * <p>按 code 而不是按种子里的主键查:主键是种子脚本的实现细节,code 才是
     * 这个角色的身份。一次数据迁移换掉主键不该让"谁是空间管理员"失效。
     */
    private static final String ROLE_CODE_WORKSPACE_ADMIN = "WORKSPACE_ADMIN";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceSecretMapper secretMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final SecretCipher cipher;

    public WorkspaceService(WorkspaceMapper workspaceMapper,
                            WorkspaceSecretMapper secretMapper,
                            WorkspaceMemberMapper memberMapper,
                            UserMapper userMapper,
                            RoleMapper roleMapper,
                            UserRoleMapper userRoleMapper,
                            SecretCipher cipher) {
        this.workspaceMapper = workspaceMapper;
        this.secretMapper = secretMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.cipher = cipher;
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public Workspace require(String workspaceId) {
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw BizException.notFound(ErrorCode.PLT_WORKSPACE_NOT_FOUND, workspaceId);
        }
        return workspace;
    }

    /**
     * 列出某用户可访问的空间。
     *
     * <p>平台管理员看全部 —— 总得有人能在所有空间都失联时进去救火。
     * 普通用户只看被显式授权的空间(功能 28 的「授权用户」绑定)。
     */
    public List<WorkspaceView> listAccessible(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return List.of();
        }
        if (Boolean.TRUE.equals(user.getPlatformAdmin())) {
            return workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>()
                            .orderByAsc(Workspace::getCode)).stream()
                    .map(WorkspaceService::toView).toList();
        }

        List<WorkspaceMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<String> ids = memberships.stream().map(WorkspaceMember::getWorkspaceId).toList();
        return workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>()
                        .in(Workspace::getId, ids)
                        .orderByAsc(Workspace::getCode)).stream()
                .map(WorkspaceService::toView).toList();
    }

    /**
     * 校验用户对空间的访问权,不通过则抛 403。
     *
     * <p>切换空间、以及每个业务请求携带 {@code X-Workspace-Id} 时都要走这里 ——
     * 否则用户只要改一个请求头就能读别的空间的数据。
     */
    public void requireAccess(String userId, String workspaceId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.PLT_USER_DISABLED);
        }

        // 平台管理员对停用空间仍可进入 —— 停用之后总得有人能进去把它启用回来,
        // 否则「停用」等于「不可逆销毁」,而这不是功能 28 的语义。
        if (Boolean.TRUE.equals(user.getPlatformAdmin())) {
            require(workspaceId);
            return;
        }

        Workspace workspace = require(workspaceId);
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.PLT_WORKSPACE_FORBIDDEN,
                    "当前用户未被授权访问该空间");
        }

        // 停用检查放在授权检查之后:先回答"你有没有权限",再回答"这个空间开着没有"。
        // 反过来会让一个无权访问的人通过错误信息的差异探知某个空间存在且被停用了。
        requireActive(workspace);
    }

    /**
     * 空间停用后,普通成员的一切读写都应被拒绝。
     *
     * <p>停用是功能 28 的一个<b>运行时闸门</b>,不只是列表上的一个标记 ——
     * 如果停用之后成员照样能查数据、跑任务,那这个开关什么也没关掉。
     */
    private static void requireActive(Workspace workspace) {
        if (WorkspaceStatus.SUSPENDED.name().equals(workspace.getStatus())) {
            throw new BizException(ErrorCode.PLT_WORKSPACE_SUSPENDED,
                    "空间「%s」已停用".formatted(workspace.getName()),
                    "请联系平台管理员启用该空间");
        }
    }

    public List<String> listMemberIds(String workspaceId) {
        return memberMapper.selectList(new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)).stream()
                .map(WorkspaceMember::getUserId).toList();
    }

    // ── 命令 ────────────────────────────────────────────────────────────

    @Transactional
    public WorkspaceView create(String code, String name, String description, String operator) {
        requireCodeAvailable(code, null);

        Instant now = Instant.now();
        Workspace workspace = new Workspace();
        workspace.setId(Ids.of("ws"));
        workspace.setCode(code.trim());
        workspace.setName(name.trim());
        workspace.setDescription(description);
        workspace.setStatus("ACTIVE");
        workspace.setCreatedAt(now);
        workspace.setCreatedBy(operator);
        workspace.setUpdatedAt(now);
        workspace.setUpdatedBy(operator);
        workspace.setDeleted(false);
        workspaceMapper.insert(workspace);

        issueSecret(workspace.getId(), operator);

        log.info("空间已创建 id={} code={} name={}", workspace.getId(), code, name);
        return toView(workspace);
    }

    @Transactional
    public WorkspaceView update(String workspaceId, String name, String description, String operator) {
        Workspace workspace = require(workspaceId);
        workspace.setName(name.trim());
        workspace.setDescription(description);
        workspace.setUpdatedAt(Instant.now());
        workspace.setUpdatedBy(operator);
        workspaceMapper.updateById(workspace);
        return toView(workspace);
    }

    /**
     * EnableWorkspace / SuspendWorkspace —— 功能 28「空间启用/停用」。
     *
     * <p>停用不删任何数据:空间里的数据源、凭据、成员关系原样保留,只是所有
     * 非平台管理员的访问被 {@link #requireAccess} 挡在门外。这是「停用」而不是
     * 「删除」应有的语义 —— 启用回来之后一切照旧。
     *
     * <p>幂等:把已停用的空间再停用一次不报错。这个接口的调用方多半是管理界面
     * 上的一个开关,为一次重复点击抛 409 只会制造噪音。
     */
    @Transactional
    public WorkspaceView setStatus(String workspaceId, boolean enabled, String operator) {
        Workspace workspace = require(workspaceId);
        WorkspaceStatus target = enabled ? WorkspaceStatus.ACTIVE : WorkspaceStatus.SUSPENDED;
        WorkspaceStatus current = parseStatus(workspace.getStatus());

        if (current == target) {
            return toView(workspace);
        }
        WorkspaceLifecycle.MACHINE.checkTransition(current, target);

        workspace.setStatus(target.name());
        workspace.setUpdatedAt(Instant.now());
        workspace.setUpdatedBy(operator);
        workspaceMapper.updateById(workspace);

        log.info("空间{} id={} code={} 操作人={}",
                enabled ? "已启用" : "已停用", workspaceId, workspace.getCode(), operator);
        return toView(workspace);
    }

    // ── 空间管理员(功能 28)────────────────────────────────────────────
    // 没有新增「管理员」字段,空间管理员就是在该空间下被授予内置 WORKSPACE_ADMIN
    // 角色的用户。理由是避免两套并行的授权事实:如果既有 is_admin 标记又有角色授权,
    // 鉴权时到底以哪个为准会变成一个反复出现的问题,而两者迟早会不一致。

    public List<String> listAdminIds(String workspaceId) {
        String roleId = workspaceAdminRoleId();
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getWorkspaceId, workspaceId)
                        .eq(UserRole::getRoleId, roleId)).stream()
                .map(UserRole::getUserId).toList();
    }

    /**
     * 授予空间管理员。
     *
     * <p>顺带把人加进空间成员 —— 一个不是成员的"管理员"连空间都进不去,
     * 那个授权只是数据库里的一行无效记录。
     */
    @Transactional
    public void grantAdmin(String workspaceId, String userId, String operator) {
        require(workspaceId);
        if (userMapper.selectById(userId) == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "用户 " + userId);
        }
        addMembers(workspaceId, List.of(userId), operator);

        String roleId = workspaceAdminRoleId();
        Long exists = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getWorkspaceId, workspaceId)
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleId, roleId));
        if (exists != null && exists > 0) {
            return;     // 幂等
        }

        UserRole grant = new UserRole();
        grant.setWorkspaceId(workspaceId);
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        grant.setCreatedAt(Instant.now());
        grant.setCreatedBy(operator);
        userRoleMapper.insert(grant);

        log.info("已授予空间管理员 workspace={} user={} 操作人={}", workspaceId, userId, operator);
    }

    /**
     * 撤销空间管理员。
     *
     * <p>撤到一个不剩也允许 —— 与"删除最后一个平台管理员"不同,那种情况没人
     * 能再登录修复,而一个没有管理员的空间随时可以由任意平台管理员重新指派。
     * 为一个可恢复的状态设置硬性阻拦,只会让"移除离职人员的权限"这种正当操作
     * 卡住。降级为一条 WARN 日志。
     */
    @Transactional
    public void revokeAdmin(String workspaceId, String userId, String operator) {
        String roleId = workspaceAdminRoleId();
        int removed = userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getWorkspaceId, workspaceId)
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleId, roleId));
        if (removed == 0) {
            return;
        }
        log.info("已撤销空间管理员 workspace={} user={} 操作人={}", workspaceId, userId, operator);
        if (listAdminIds(workspaceId).isEmpty()) {
            log.warn("空间 {} 现在没有任何空间管理员,只有平台管理员能进入", workspaceId);
        }
    }

    private String workspaceAdminRoleId() {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, ROLE_CODE_WORKSPACE_ADMIN)
                .isNull(Role::getWorkspaceId)
                .last("LIMIT 1"));
        if (role == null) {
            // 内置角色由 V2 迁移种下。查不到说明库没迁移到位,这不是用户输入问题。
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "内置角色 %s 缺失,数据库未初始化完整".formatted(ROLE_CODE_WORKSPACE_ADMIN));
        }
        return role.getId();
    }

    private static WorkspaceStatus parseStatus(String raw) {
        try {
            return WorkspaceStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 库里出现了枚举之外的取值。当成 ACTIVE 会让一个本该被拦住的空间放行,
            // 所以宁可报错:这是数据问题,不该被一个默认值掩盖。
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "空间状态取值非法: " + raw);
        }
    }

    @Transactional
    public void addMembers(String workspaceId, List<String> userIds, String operator) {
        require(workspaceId);
        for (String userId : userIds) {
            Long exists = memberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                    .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                    .eq(WorkspaceMember::getUserId, userId));
            if (exists != null && exists > 0) {
                continue;   // 幂等:重复添加不报错
            }
            WorkspaceMember member = new WorkspaceMember();
            member.setWorkspaceId(workspaceId);
            member.setUserId(userId);
            member.setCreatedAt(Instant.now());
            member.setCreatedBy(operator);
            memberMapper.insert(member);
        }
    }

    @Transactional
    public void removeMember(String workspaceId, String userId) {
        memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
    }

    /**
     * 轮换空间鉴权密钥。
     *
     * <p>旧密钥标记为 RETIRED 而不是删除:调用方需要一段时间切换,
     * 立刻失效会造成一次可预见的服务中断。清理 RETIRED 记录是运维动作,
     * 不在这里做。
     */
    @Transactional
    public String rotateSecret(String workspaceId, String operator) {
        require(workspaceId);
        List<WorkspaceSecret> active = secretMapper.selectList(
                new LambdaQueryWrapper<WorkspaceSecret>()
                        .eq(WorkspaceSecret::getWorkspaceId, workspaceId)
                        .eq(WorkspaceSecret::getStatus, "ACTIVE"));
        for (WorkspaceSecret secret : active) {
            secret.setStatus("RETIRED");
            secret.setRotatedAt(Instant.now());
            secretMapper.updateById(secret);
        }
        return issueSecret(workspaceId, operator);
    }

    /**
     * 签发一对 accessKey / secretKey。
     *
     * @return 明文 secretKey —— <b>这是它唯一一次以明文出现的机会</b>,
     *         之后库里只有密文。调用方有责任把它展示给用户并提示妥善保存。
     */
    private String issueSecret(String workspaceId, String operator) {
        String accessKey = "ak_" + randomToken(16);
        String secretKey = randomToken(32);

        WorkspaceSecret secret = new WorkspaceSecret();
        secret.setId(Ids.of("wsk"));
        secret.setWorkspaceId(workspaceId);
        secret.setAccessKey(accessKey);
        secret.setSecretKeyEnc(cipher.encrypt(secretKey));
        secret.setStatus("ACTIVE");
        secret.setCreatedAt(Instant.now());
        secret.setCreatedBy(operator);
        secretMapper.insert(secret);

        return secretKey;
    }

    private void requireCodeAvailable(String code, String excludeId) {
        Long count = workspaceMapper.selectCount(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getCode, code.trim())
                .ne(excludeId != null, Workspace::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.PLT_WORKSPACE_CODE_DUPLICATED, code);
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static WorkspaceView toView(Workspace workspace) {
        return new WorkspaceView(
                workspace.getId(), workspace.getCode(), workspace.getName(),
                workspace.getDescription(), workspace.getStatus(),
                workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
