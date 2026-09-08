package com.datagov.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次启动引导配置(对应 {@code dg.bootstrap.*})。
 */
@ConfigurationProperties(prefix = "dg.bootstrap")
public class BootstrapProperties {

    private boolean enabled = true;

    private String adminUsername = "admin";

    /**
     * 管理员初始口令。
     *
     * <p>留空则首次启动生成 16 位随机口令并以 WARN 级别打印一次。
     * <b>绝不要在配置文件里写死一个默认口令</b> —— 它会随代码库分发,
     * 而绝大多数部署不会去改它。
     */
    private String adminPassword;

    private String defaultWorkspaceCode = "default";
    private String defaultWorkspaceName = "默认空间";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public String getDefaultWorkspaceCode() { return defaultWorkspaceCode; }
    public void setDefaultWorkspaceCode(String v) { this.defaultWorkspaceCode = v; }
    public String getDefaultWorkspaceName() { return defaultWorkspaceName; }
    public void setDefaultWorkspaceName(String v) { this.defaultWorkspaceName = v; }
}
