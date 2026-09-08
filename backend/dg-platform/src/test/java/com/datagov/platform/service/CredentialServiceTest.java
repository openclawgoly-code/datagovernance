package com.datagov.platform.service;

import com.datagov.common.crypto.AesGcmSecretCipher;
import com.datagov.common.crypto.SecretCipher;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.dto.CredentialView;
import com.datagov.platform.entity.PlatformEntities.Credential;
import com.datagov.platform.mapper.CredentialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭据托管 —— 平台里唯一持有并能解密凭据的地方。
 */
@DisplayName("凭据托管")
class CredentialServiceTest {

    private static final String WS_A = "ws_alpha";
    private static final String WS_B = "ws_beta";

    private CredentialMapper credentialMapper;
    private SecretCipher cipher;
    private CredentialService service;

    @BeforeEach
    void setUp() {
        credentialMapper = mock(CredentialMapper.class);
        // 用真实的加密实现,这样加解密往返、密文格式、篡改检测都被真正测到
        cipher = new AesGcmSecretCipher("unit-test-master-key-do-not-use-in-prod");
        service = new CredentialService(credentialMapper, cipher, new ObjectMapper());

        WorkspaceContext.set(new Caller("usr_1", "alice", WS_A, false, Set.of()));
        when(credentialMapper.selectCount(any())).thenReturn(0L);
        when(credentialMapper.insert((Credential) any())).thenReturn(1);
        when(credentialMapper.updateById((Credential) any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("创建时口令被加密,库里不存明文")
    void secretIsEncryptedAtRest() {
        service.create("生产库口令", CredentialSecret.AuthType.PASSWORD,
                "dbuser", "hunter2", "用于订单库");

        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(credentialMapper).insert(captor.capture());
        String stored = captor.getValue().getPayloadEnc();

        assertThat(stored).doesNotContain("hunter2");
        assertThat(stored).startsWith("v1:");   // 带算法版本前缀,便于将来换 SM4
    }

    @Test
    @DisplayName("对外视图里没有 payload 字段 —— 类型上就不存在,不靠记得过滤")
    void viewNeverExposesPayload() {
        CredentialView view = service.create("凭据", CredentialSecret.AuthType.BASIC,
                "u", "p", null);

        // 编译期保证:CredentialView 这个 record 根本没有 payload/secret 分量
        assertThat(view.name()).isEqualTo("凭据");
        assertThat(view.authType()).isEqualTo("BASIC");
        assertThat(CredentialView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("payload", "payloadEnc", "secret", "password");
    }

    @Test
    @DisplayName("解密往返得到原始口令")
    void resolveRoundTripsSecret() {
        Credential stored = encrypted(WS_A, "cred_1", CredentialSecret.AuthType.PASSWORD,
                "dbuser", "hunter2");
        when(credentialMapper.selectOne(any())).thenReturn(stored);

        CredentialSecret resolved = service.resolveSecret(WS_A, "cred_1");

        assertThat(resolved.authType()).isEqualTo(CredentialSecret.AuthType.PASSWORD);
        assertThat(resolved.username()).isEqualTo("dbuser");
        assertThat(resolved.secret()).isEqualTo("hunter2");
    }

    @Test
    @DisplayName("credentialId 为空表示无需认证")
    void nullCredentialMeansNoAuth() {
        CredentialSecret resolved = service.resolveSecret(WS_A, null);

        assertThat(resolved.authType()).isEqualTo(CredentialSecret.AuthType.NONE);
        assertThat(resolved.hasSecret()).isFalse();
    }

    @Test
    @DisplayName("跨空间解析被拒 —— 否则引用一个 ID 就能读别人空间的凭据")
    void crossWorkspaceResolveIsRejected() {
        // 查询恒带 workspace_id 过滤,别的空间的凭据 selectOne 返回 null
        when(credentialMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.resolveSecret(WS_B, "cred_belongs_to_alpha"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_CREDENTIAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("密文被篡改时报错,而不是当作「无凭据」放行")
    void tamperedCiphertextIsReported() {
        Credential stored = encrypted(WS_A, "cred_1", CredentialSecret.AuthType.PASSWORD, "u", "p");
        // 翻转密文里的一个字符
        String payload = stored.getPayloadEnc();
        stored.setPayloadEnc(payload.substring(0, payload.length() - 2)
                + (payload.endsWith("A") ? "B" : "A"));
        when(credentialMapper.selectOne(any())).thenReturn(stored);

        // 静默当成"没有口令"会让一次数据篡改表现为"连接失败",排查方向完全错误
        assertThatThrownBy(() -> service.resolveSecret(WS_A, "cred_1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("更新时口令传 null 表示保持原样,不会被清空")
    void nullSecretOnUpdateKeepsExisting() {
        Credential stored = encrypted(WS_A, "cred_1", CredentialSecret.AuthType.PASSWORD,
                "olduser", "keepme");
        when(credentialMapper.selectOne(any())).thenReturn(stored);

        service.update("cred_1", "改了名字", CredentialSecret.AuthType.PASSWORD,
                "newuser", null, "只改用户名");

        // 编辑表单里口令框留空意味着"保持原样",而不是"改成空口令"。
        // 混同两者会让用户改个描述就意外清掉了口令。
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(credentialMapper).updateById(captor.capture());
        String json = cipher.decrypt(captor.getValue().getPayloadEnc());
        assertThat(json).contains("keepme").contains("newuser");
    }

    @Test
    @DisplayName("同空间下凭据名称唯一")
    void duplicateNameRejected() {
        when(credentialMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create("重名", CredentialSecret.AuthType.TOKEN,
                null, "t", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("凭据名称已存在");
    }

    @Test
    @DisplayName("CredentialSecret.toString 不泄露口令")
    void toStringIsMasked() {
        CredentialSecret secret = new CredentialSecret(
                CredentialSecret.AuthType.PASSWORD, "u", "super-secret");

        assertThat(secret.toString()).doesNotContain("super-secret").contains("******");
    }

    private Credential encrypted(String workspaceId, String id,
                                 CredentialSecret.AuthType authType,
                                 String username, String secret) {
        Credential credential = new Credential();
        credential.setId(id);
        credential.setWorkspaceId(workspaceId);
        credential.setName("凭据");
        credential.setAuthType(authType.name());
        credential.setPayloadEnc(cipher.encrypt(
                """
                {"authType":"%s","username":"%s","secret":"%s"}"""
                        .formatted(authType.name(), username, secret)));
        credential.setDeleted(false);
        return credential;
    }
}
