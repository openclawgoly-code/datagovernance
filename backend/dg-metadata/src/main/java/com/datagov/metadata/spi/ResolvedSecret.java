package com.datagov.metadata.spi;

/**
 * 一次性解析出来的凭据明文。
 *
 * <p><b>这个对象绝不能被持久化、缓存或日志化。</b> 它只在"组装 ConnectionConfig →
 * 交给 DataAccessGateway → 方法返回"这段调用栈里存在。
 *
 * <p>{@link #toString()} 被覆写为脱敏形式,因为依赖调用方"记得别打印"是不可靠的 ——
 * 一次 {@code log.debug("secret={}", secret)} 就足以让凭据进日志文件,而日志
 * 往往比数据库更容易被复制出去。
 *
 * @param authType 认证方式,取值对齐 {@code pf_credential.auth_type}
 * @param username 用户名。可能为 null(TOKEN / NONE 认证)
 * @param secret   口令 / Token 明文。NONE 认证时为 null
 */
public record ResolvedSecret(AuthType authType, String username, String secret) {

    public enum AuthType {
        /** 无认证 —— 匿名 FTP、开放的 RestAPI */
        NONE,
        /** 用户名 + 口令,HTTP Basic 语义 */
        BASIC,
        /** Bearer Token / API Key */
        TOKEN,
        /** 数据库口令 */
        PASSWORD
    }

    public static ResolvedSecret none() {
        return new ResolvedSecret(AuthType.NONE, null, null);
    }

    public boolean hasSecret() {
        return secret != null && !secret.isEmpty();
    }

    @Override
    public String toString() {
        return "ResolvedSecret[authType=%s, username=%s, secret=%s]".formatted(
                authType, username, hasSecret() ? "******" : "<none>");
    }
}
