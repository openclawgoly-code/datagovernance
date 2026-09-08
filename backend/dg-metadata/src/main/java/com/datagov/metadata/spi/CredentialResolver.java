package com.datagov.metadata.spi;

/**
 * Metadata 向 Platform Space 要一份凭据明文的<b>出站端口</b>。
 *
 * <p>为什么是端口而不是直接调用 Platform 的服务类:{@code SPACE-MODEL.md} 的
 * must_not_do 写着「Metadata 不得存储凭据明文,只存 credentialRef」。
 * Metadata 因此既不持有密钥、也不做解密 —— 它只知道有个 credentialId,
 * 需要用的时候<b>问</b>凭据的所有者。解密发生在 Platform Space 内部,
 * 审计点也就自然落在那一处,而不是散落在每个用到数据源的地方。
 *
 * <p>实现由 {@code dg-app} 在运行时注入(适配到 Platform 的 CredentialService)。
 *
 * <p><b>实现方的义务</b>:
 * <ul>
 *   <li>必须校验 credential 属于传入的 workspace —— 否则跨空间引用一个
 *       credentialId 就能把别人空间的凭据解出来</li>
 *   <li>每次解析都应产生一条审计记录(P4 Governance 接管前先打日志,
 *       但日志里只记"谁在什么时候解了哪个凭据",不记内容)</li>
 * </ul>
 */
public interface CredentialResolver {

    /**
     * 解析凭据明文。
     *
     * @param workspaceId  当前空间,用于越权校验
     * @param credentialId 凭据 ID;为 null 表示该数据源无需认证,应返回 {@link ResolvedSecret#none()}
     * @throws com.datagov.common.error.BizException 凭据不存在或不属于该空间
     */
    ResolvedSecret resolve(String workspaceId, String credentialId);
}
