package com.datagov.platform.config;

import com.datagov.common.crypto.AesGcmSecretCipher;
import com.datagov.common.crypto.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Platform Space 的 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties({SecurityProperties.class, BootstrapProperties.class})
public class PlatformConfig {

    /** JWT 签名密钥的最短长度。HMAC-SHA256 要求密钥不短于 256 位。 */
    private static final int MIN_SIGNING_KEY_LENGTH = 32;

    /**
     * 凭据加解密器。
     *
     * <p>主密钥未配置时<b>直接让应用起不来</b>,而不是退化成一个内置默认值。
     * 这是有意为之:用内置密钥加密的生产凭据等同于明文,而这种问题在运行时
     * 毫无征兆 —— 一切看起来都正常工作,直到代码库泄漏的那天。
     */
    @Bean
    public SecretCipher secretCipher(SecurityProperties properties) {
        String key = properties.getSecretKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("""
                    未配置 dg.security.secret-key,应用拒绝启动。

                    它是凭据与空间密钥的 AES-GCM 主密钥,没有内置默认值 ——
                    内置默认值会随代码库分发,用它加密的生产凭据等同于明文。

                    生成方式:
                        export DG_SECRET_KEY="$(openssl rand -base64 32)"

                    注意:一旦用于加密过数据就不能更换,否则已存凭据无法解密。
                    """);
        }
        return new AesGcmSecretCipher(key);
    }

    /**
     * 用户口令散列。
     *
     * <p>BCrypt 是单向的 —— 与 {@link SecretCipher} 的可逆加密是两回事。
     * 用户口令永远不需要还原,任何"找回密码"都应该是重置而不是解密。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 启动时校验 JWT 配置,把问题暴露在启动期而不是第一次登录时。 */
    @Bean
    public JwtKeyValidator jwtKeyValidator(SecurityProperties properties) {
        String key = properties.getJwt().getSigningKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("""
                    未配置 dg.security.jwt.signing-key,应用拒绝启动。

                    生成方式:
                        export DG_JWT_SIGNING_KEY="$(openssl rand -base64 48)"
                    """);
        }
        if (key.length() < MIN_SIGNING_KEY_LENGTH) {
            throw new IllegalStateException(
                    "dg.security.jwt.signing-key 至少需要 %d 个字符(HMAC-SHA256 要求 256 位密钥),当前 %d 个"
                            .formatted(MIN_SIGNING_KEY_LENGTH, key.length()));
        }
        return new JwtKeyValidator();
    }

    /** 仅用于承载启动期校验的标记类型。 */
    public static final class JwtKeyValidator {
    }
}
