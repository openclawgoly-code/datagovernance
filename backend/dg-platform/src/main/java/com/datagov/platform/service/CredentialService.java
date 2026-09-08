package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.crypto.SecretCipher;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.dto.CredentialView;
import com.datagov.platform.entity.PlatformEntities.Credential;
import com.datagov.platform.mapper.CredentialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 凭据托管(功能 4 的接口认证 + 功能 1-3 的数据库口令)。
 *
 * <p><b>这是平台里唯一持有凭据密文、也是唯一能解密它的地方。</b>
 * Metadata 的数据源只持有 credentialId。这样安排的实际收益:
 * 要回答"谁在什么时候解密了哪个凭据"这个审计问题,只需要看
 * {@link #resolveSecret} 的调用方,而不是把整个代码库翻一遍。
 *
 * <p>对外的查询方法一律返回 {@link CredentialView},它<b>没有</b> payload 字段 ——
 * 不是靠"记得别返回",而是类型上就没有。
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final CredentialMapper credentialMapper;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    public CredentialService(CredentialMapper credentialMapper,
                             SecretCipher cipher,
                             ObjectMapper objectMapper) {
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    // ── 查询(永不返回明文)──────────────────────────────────────────────

    public List<CredentialView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return credentialMapper.selectList(new LambdaQueryWrapper<Credential>()
                        .eq(Credential::getWorkspaceId, workspaceId)
                        .orderByDesc(Credential::getUpdatedAt)).stream()
                .map(CredentialService::toView)
                .toList();
    }

    public CredentialView get(String id) {
        return toView(requireInWorkspace(WorkspaceContext.requireWorkspaceId(), id));
    }

    // ── 命令 ────────────────────────────────────────────────────────────

    @Transactional
    public CredentialView create(String name, CredentialSecret.AuthType authType,
                                 String username, String secret, String description) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        requireNameAvailable(workspaceId, name, null);

        Credential credential = new Credential();
        credential.setId(Ids.of("cred"));
        credential.setWorkspaceId(workspaceId);
        credential.setName(name.trim());
        credential.setAuthType(authType.name());
        credential.setPayloadEnc(encrypt(authType, username, secret));
        credential.setDescription(description);
        credential.setCreatedAt(Instant.now());
        credential.setCreatedBy(operator);
        credential.setUpdatedAt(Instant.now());
        credential.setUpdatedBy(operator);
        credential.setDeleted(false);

        credentialMapper.insert(credential);
        log.info("凭据已创建 workspace={} id={} name={} authType={}",
                workspaceId, credential.getId(), credential.getName(), authType);
        return toView(credential);
    }

    /**
     * 更新凭据。
     *
     * @param secret 为 null 表示<b>不修改</b>口令 —— 编辑表单里口令框留空意味着
     *               "保持原样",而不是"改成空口令"。把两者混同会让用户
     *               改一个描述就意外清掉了口令。
     */
    @Transactional
    public CredentialView update(String id, String name, CredentialSecret.AuthType authType,
                                 String username, String secret, String description) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Credential credential = requireInWorkspace(workspaceId, id);
        requireNameAvailable(workspaceId, name, id);

        credential.setName(name.trim());
        credential.setAuthType(authType.name());
        credential.setDescription(description);
        if (secret != null) {
            credential.setPayloadEnc(encrypt(authType, username, secret));
        } else {
            // 只改用户名而不改口令:解出原口令重新封装
            CredentialSecret existing = decrypt(credential);
            credential.setPayloadEnc(encrypt(authType, username, existing.secret()));
        }
        credential.setUpdatedAt(Instant.now());
        credential.setUpdatedBy(WorkspaceContext.require().userId());

        credentialMapper.updateById(credential);
        return toView(credential);
    }

    @Transactional
    public void delete(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Credential credential = requireInWorkspace(workspaceId, id);
        // 引用检查由装配层在删除前查询 md_datasource.credential_id 完成 ——
        // Platform 不认识数据源,不该反向依赖 Metadata。
        credentialMapper.deleteById(credential.getId());
        log.info("凭据已删除 workspace={} id={}", workspaceId, id);
    }

    // ── 解密(唯一入口)──────────────────────────────────────────────────

    /**
     * 解析凭据明文。
     *
     * <p>调用方必须传入自己所处的空间,这里会校验凭据确实属于该空间 ——
     * 否则跨空间引用一个 credentialId 就能把别人空间的凭据解出来。
     *
     * @param credentialId 为 null 表示无需认证,返回 {@link CredentialSecret#none()}
     */
    public CredentialSecret resolveSecret(String workspaceId, String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return CredentialSecret.none();
        }
        Credential credential = requireInWorkspace(workspaceId, credentialId);
        // 审计留痕:只记"谁解了哪个凭据",不记内容。P4 由 Governance 接管。
        log.info("凭据被解析 workspace={} credentialId={} name={}",
                workspaceId, credentialId, credential.getName());
        return decrypt(credential);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private String encrypt(CredentialSecret.AuthType authType, String username, String secret) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "authType", authType.name(),
                    "username", username == null ? "" : username,
                    "secret", secret == null ? "" : secret));
            return cipher.encrypt(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "凭据加密失败", null, e);
        }
    }

    private CredentialSecret decrypt(Credential credential) {
        try {
            String json = cipher.decrypt(credential.getPayloadEnc());
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            String username = asString(map.get("username"));
            String secret = asString(map.get("secret"));
            return new CredentialSecret(
                    CredentialSecret.AuthType.valueOf(credential.getAuthType()),
                    username == null || username.isEmpty() ? null : username,
                    secret == null || secret.isEmpty() ? null : secret);
        } catch (IllegalStateException e) {
            // 主密钥换了或密文被篡改。这是运维事故,必须报出来而不是当作"无凭据"
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "凭据解密失败: 主密钥不匹配或密文已被篡改",
                    "credentialId=" + credential.getId(), e);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "凭据内容格式异常", "credentialId=" + credential.getId(), e);
        }
    }

    private Credential requireInWorkspace(String workspaceId, String id) {
        Credential credential = credentialMapper.selectOne(new LambdaQueryWrapper<Credential>()
                .eq(Credential::getId, id)
                .eq(Credential::getWorkspaceId, workspaceId));
        if (credential == null) {
            throw BizException.notFound(ErrorCode.PLT_CREDENTIAL_NOT_FOUND, id);
        }
        return credential;
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = credentialMapper.selectCount(new LambdaQueryWrapper<Credential>()
                .eq(Credential::getWorkspaceId, workspaceId)
                .eq(Credential::getName, name.trim())
                .ne(excludeId != null, Credential::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "凭据名称已存在: " + name);
        }
    }

    private static CredentialView toView(Credential credential) {
        return new CredentialView(
                credential.getId(),
                credential.getName(),
                credential.getAuthType(),
                credential.getDescription(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
