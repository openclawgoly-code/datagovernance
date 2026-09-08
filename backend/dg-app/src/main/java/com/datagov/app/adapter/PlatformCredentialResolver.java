package com.datagov.app.adapter;

import com.datagov.metadata.spi.CredentialResolver;
import com.datagov.metadata.spi.ResolvedSecret;
import com.datagov.platform.dto.CredentialSecret;
import com.datagov.platform.service.CredentialService;
import org.springframework.stereotype.Component;

/**
 * 把 Metadata 的出站端口接到 Platform 的凭据服务上。
 *
 * <p><b>这个适配器只能存在于装配层。</b> Metadata 声明"我需要一份凭据明文"
 * ({@link CredentialResolver}),Platform 提供"我能解密凭据"
 * ({@link CredentialService}),但两者互不认识 —— Metadata 不依赖 Platform 的
 * 具体服务类,Platform 更不知道有数据源这回事。把它们接起来是 dg-app 的职责,
 * 也是 dg-app 存在的理由。
 *
 * <p>两个 Space 各自定义了结构相同的 secret 类型({@link ResolvedSecret} 与
 * {@link CredentialSecret})而不是共享一个。看起来是重复,实际是为了让任一侧
 * 的字段演进不会隐式牵动另一侧 —— 转换成本就是这里的十几行,而共享类型的
 * 代价是两个 Space 从此绑死。
 */
@Component
public class PlatformCredentialResolver implements CredentialResolver {

    private final CredentialService credentialService;

    public PlatformCredentialResolver(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public ResolvedSecret resolve(String workspaceId, String credentialId) {
        // 越权校验在 CredentialService 内部完成:它会确认该凭据确实属于这个空间
        CredentialSecret secret = credentialService.resolveSecret(workspaceId, credentialId);
        return new ResolvedSecret(
                toMetadataAuthType(secret.authType()),
                secret.username(),
                secret.secret());
    }

    private static ResolvedSecret.AuthType toMetadataAuthType(CredentialSecret.AuthType authType) {
        return switch (authType) {
            case NONE -> ResolvedSecret.AuthType.NONE;
            case BASIC -> ResolvedSecret.AuthType.BASIC;
            case TOKEN -> ResolvedSecret.AuthType.TOKEN;
            case PASSWORD -> ResolvedSecret.AuthType.PASSWORD;
        };
    }
}
