package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.spi.CredentialResolver;
import com.datagov.metadata.spi.ResolvedSecret;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把数据源定义(不含口令)与凭据(来自 Platform)组装成一次性的 {@link ConnectionConfig}。
 *
 * <p><b>这是整个 Metadata Space 里唯一接触凭据明文的地方。</b> 集中在一处的价值:
 * 要回答"平台在哪些时刻解密了凭据"这个审计问题时,只需要看这一个类的调用方,
 * 而不是把整个模块翻一遍。
 *
 * <p>组装出的 {@link ConnectionConfig} 是<b>瞬时对象</b>:用完即弃,不缓存、不入库、
 * 不进日志。它的 {@code toString()} 已被覆写为脱敏形式。
 */
@Component
public class ConnectionConfigAssembler {

    private static final TypeReference<Map<String, String>> PROPERTIES_TYPE =
            new TypeReference<>() {
            };

    private final CredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;

    public ConnectionConfigAssembler(CredentialResolver credentialResolver, ObjectMapper objectMapper) {
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * @param entity      数据源定义
     * @param workspaceId 当前空间 —— 传给 Platform 做越权校验,不能省
     */
    public ConnectionConfig assemble(DataSourceEntity entity, String workspaceId) {
        ResolvedSecret secret = credentialResolver.resolve(workspaceId, entity.getCredentialId());

        // 用户名的取值顺序:数据源上的优先,凭据里的兜底。
        // 数据源上的 username 是"这个库用哪个账号连"的标识,通常更具体;
        // 凭据里的 username 服务于 BASIC 认证这类用户名与口令绑定的场景。
        String username = entity.getUsername() != null && !entity.getUsername().isBlank()
                ? entity.getUsername()
                : secret.username();

        return ConnectionConfig.builder()
                .host(entity.getHost())
                .port(entity.getPort())
                .database(entity.getDatabaseName())
                .username(username)
                .password(secret.secret())
                .properties(parseProperties(entity.getPropertiesJson()))
                .jdbcUrlOverride(entity.getJdbcUrlOverride())
                .baseUrl(entity.getBaseUrl())
                .connectTimeoutMillis(orDefault(entity.getConnectTimeoutMs(),
                        ConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS))
                .readTimeoutMillis(orDefault(entity.getReadTimeoutMs(),
                        ConnectionConfig.DEFAULT_READ_TIMEOUT_MILLIS))
                .build();
    }

    public Map<String, String> parseProperties(String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(propertiesJson, PROPERTIES_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // 扩展参数是用户填的,存进来时是合法 JSON,读出来解析不了说明数据坏了。
            // 静默当空 Map 会让用户的驱动参数悄悄失效,不如直接报错。
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "数据源扩展参数不是合法的 JSON 对象", e.getMessage(), e);
        }
    }

    public String writeProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "扩展参数无法序列化", e.getMessage(), e);
        }
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
