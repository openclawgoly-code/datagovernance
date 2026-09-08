package com.datagov.data.spi;

import java.util.Map;

/**
 * 规范化的连接参数。
 *
 * <p><b>这是一个瞬时值对象,不是持久化实体。</b> Metadata Space 持有的是
 * DataSource 实体(含加密后的密码);要发起一次探测时,由 Metadata 解密并
 * 组装出本对象交给 Data Space。Data Space 因此永远不需要知道
 * "凭据存在哪、怎么加密" —— 那是 Platform/Metadata 的事。
 *
 * @param host            主机;RestAPI 类型下可为空(用 baseUrl)
 * @param port            端口
 * @param database        库名 / Oracle 的 SID 或 Service Name / FTP 的根目录
 * @param username        用户名
 * @param password        <b>明文</b>密码 —— 仅存在于内存与调用栈,禁止落日志
 * @param properties      驱动扩展参数(如 useSSL、serverTimezone、oracle.jdbc.*)
 * @param jdbcUrlOverride 直填 JDBC URL;非空时优先于 host/port/database 拼装
 * @param baseUrl         RestAPI 的基础地址
 * @param connectTimeoutMillis 连接超时
 * @param readTimeoutMillis    读取超时
 */
public record ConnectionConfig(
        String host,
        Integer port,
        String database,
        String username,
        String password,
        Map<String, String> properties,
        String jdbcUrlOverride,
        String baseUrl,
        int connectTimeoutMillis,
        int readTimeoutMillis
) {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;

    public ConnectionConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        if (connectTimeoutMillis <= 0) {
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        }
        if (readTimeoutMillis <= 0) {
            readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
        }
    }

    public String property(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * 脱敏视图,用于日志与错误信息。
     *
     * <p>{@link #toString()} 被覆写指向它,这样即便有人不小心把整个 config
     * 塞进日志,密码也不会泄露 —— 依赖"记得脱敏"是不可靠的。
     */
    public String masked() {
        return "ConnectionConfig[host=%s, port=%s, database=%s, username=%s, password=%s, baseUrl=%s]"
                .formatted(host, port, database, username,
                        password == null || password.isEmpty() ? "<none>" : "******", baseUrl);
    }

    @Override
    public String toString() {
        return masked();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String host;
        private Integer port;
        private String database;
        private String username;
        private String password;
        private Map<String, String> properties = Map.of();
        private String jdbcUrlOverride;
        private String baseUrl;
        private int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private int readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(Integer v) { this.port = v; return this; }
        public Builder database(String v) { this.database = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder properties(Map<String, String> v) { this.properties = v; return this; }
        public Builder jdbcUrlOverride(String v) { this.jdbcUrlOverride = v; return this; }
        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder connectTimeoutMillis(int v) { this.connectTimeoutMillis = v; return this; }
        public Builder readTimeoutMillis(int v) { this.readTimeoutMillis = v; return this; }

        public ConnectionConfig build() {
            return new ConnectionConfig(host, port, database, username, password, properties,
                    jdbcUrlOverride, baseUrl, connectTimeoutMillis, readTimeoutMillis);
        }
    }
}
