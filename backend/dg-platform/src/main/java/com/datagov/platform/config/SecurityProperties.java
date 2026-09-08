package com.datagov.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全相关配置(对应 {@code dg.security.*})。
 *
 * <p><b>没有任何一项有可用的内置默认值。</b> 主密钥与 JWT 签名密钥留空时应用
 * 拒绝启动 —— 见 {@link PlatformConfig}。理由:一个内置默认密钥会随代码库
 * 分发给所有人,用它加密的生产凭据等同于明文。宁可起不来。
 */
@ConfigurationProperties(prefix = "dg.security")
public class SecurityProperties {

    /** 凭据与空间密钥的 AES-GCM 主密钥。一旦用于加密过数据就不能更换。 */
    private String secretKey;

    private Jwt jwt = new Jwt();

    /** 无需认证即可访问的路径 */
    private List<String> permitPaths = new ArrayList<>();

    public static class Jwt {
        private String issuer = "datagovernance";
        /** 令牌有效期(小时)。P1 无刷新令牌,过期即重新登录。 */
        private int ttlHours = 8;
        private String signingKey;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
        public String getSigningKey() { return signingKey; }
        public void setSigningKey(String signingKey) { this.signingKey = signingKey; }
    }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public List<String> getPermitPaths() { return permitPaths; }
    public void setPermitPaths(List<String> permitPaths) { this.permitPaths = permitPaths; }
}
