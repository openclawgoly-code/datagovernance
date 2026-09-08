package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.crypto.SecretCipher;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.entity.PlatformEntities.Workspace;
import com.datagov.platform.entity.PlatformEntities.WorkspaceMember;
import com.datagov.platform.entity.PlatformEntities.WorkspaceSecret;
import com.datagov.platform.mapper.UserMapper;
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

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceSecretMapper secretMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final SecretCipher cipher;

    public WorkspaceService(WorkspaceMapper workspaceMapper,
                            WorkspaceSecretMapper secretMapper,
                            WorkspaceMemberMapper memberMapper,
                            UserMapper userMapper,
                            SecretCipher cipher) {
        this.workspaceMapper = workspaceMapper;
        this.secretMapper = secretMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
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
        if (Boolean.TRUE.equals(user.getPlatformAdmin())) {
            require(workspaceId);
            return;
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.PLT_WORKSPACE_FORBIDDEN,
                    "当前用户未被授权访问该空间");
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
