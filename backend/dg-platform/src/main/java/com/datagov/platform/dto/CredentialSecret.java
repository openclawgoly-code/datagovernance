package com.datagov.platform.dto;

/**
 * 解密后的凭据内容。
 *
 * <p><b>绝不能被持久化、缓存或日志化。</b> 它只在"解密 → 交给调用方 → 用完"
 * 这段调用栈里存在。{@link #toString()} 已覆写为脱敏形式,因为依赖调用方
 * "记得别打印"是不可靠的 —— 一次 {@code log.debug("cred={}", cred)} 就足以
 * 让凭据进日志文件,而日志往往比数据库更容易被复制出去。
 *
 * <p>本类型属于 Platform Space。Metadata 那边有一个结构相同但独立定义的
 * {@code ResolvedSecret},由装配层做转换 —— 两个 Space 不共享数据结构,
 * 是为了让任何一方的字段演进不会隐式牵动另一方。
 */
public record CredentialSecret(AuthType authType, String username, String secret) {

    public enum AuthType {
        NONE, BASIC, TOKEN, PASSWORD
    }

    public static CredentialSecret none() {
        return new CredentialSecret(AuthType.NONE, null, null);
    }

    public boolean hasSecret() {
        return secret != null && !secret.isEmpty();
    }

    @Override
    public String toString() {
        return "CredentialSecret[authType=%s, username=%s, secret=%s]".formatted(
                authType, username, hasSecret() ? "******" : "<none>");
    }
}
