package com.datagov.app.insight;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能问数网关配置。
 *
 * <p>SuperSonic 是独立服务,平台只持有一个服务账号;绝不能把 SuperSonic 的
 * admin 口令写进代码或仓库。本地用环境变量注入,生产应走平台凭据管理。
 */
@ConfigurationProperties(prefix = "dg.insight")
public class InsightProperties {

    /** SuperSonic 基地址,例如 http://localhost:9080 */
    private String baseUrl = "http://localhost:9080";
    /** 平台服务账号;SuperSonic 目前没有平台级 service token,先用普通账号登录 */
    private String username = "admin";
    /** 服务账号口令;为空时只允许启动,调用 ask 时返回“未配置” */
    private String password = "";
    /** 默认助手 ID */
    private int agentId = 5;
    /** 默认数据集 ID */
    private long defaultDatasetId = 4;
    /** 平台最多返回多少行,防止把 SuperSonic 的 1000 行全塞给前端 */
    private int maxRows = 200;
    /** 上游调用超时(秒) */
    private int timeoutSeconds = 120;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getAgentId() {
        return agentId;
    }

    public void setAgentId(int agentId) {
        this.agentId = agentId;
    }

    public long getDefaultDatasetId() {
        return defaultDatasetId;
    }

    public void setDefaultDatasetId(long defaultDatasetId) {
        this.defaultDatasetId = defaultDatasetId;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
