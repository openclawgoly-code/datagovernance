package com.datagov.metadata.dto;

import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.DataSourceEntity;

import java.time.Instant;
import java.util.Map;

/**
 * 数据源对外视图。
 *
 * <p><b>这个 record 里永远不会出现口令字段</b> —— 不是靠"记得别放",而是
 * 因为 {@link DataSourceEntity} 本身就没有口令,它只有 {@link #credentialId}。
 * 把凭据挪到 Platform Space 之后,"响应体泄露密文"这类事故在类型层面就不可能发生。
 *
 * <p>{@code SPACE-MODEL.md} 的验收项之一是「数据源的查询/导出/日志接口一律
 * 不得返回凭据明文」。这里是那条约束的结构性保证。
 */
public record DataSourceView(
        String id,
        String name,
        DataSourceType type,
        String typeDisplayName,
        DataSourceType.Family family,
        DataSourceStatus status,
        String description,

        /** 所属目录(功能 5);null 表示未分类 */
        String catalogId,

        // 周期连通性检查(功能 6)
        Boolean probeEnabled,
        Integer probeIntervalMinutes,
        Instant lastProbeAt,

        String host,
        Integer port,
        String databaseName,
        String username,
        Map<String, String> properties,
        String jdbcUrlOverride,
        String baseUrl,
        String credentialId,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,

        Instant lastTestAt,
        Boolean lastTestSuccess,
        String lastTestMessage,
        Long lastTestLatencyMs,

        Integer version,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {

    public static DataSourceView from(DataSourceEntity entity, Map<String, String> properties) {
        return new DataSourceView(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getType() == null ? null : entity.getType().displayName(),
                entity.getFamily(),
                entity.getStatus(),
                entity.getDescription(),
                entity.getCatalogId(),
                entity.getProbeEnabled(),
                entity.getProbeIntervalMinutes(),
                entity.getLastProbeAt(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                properties,
                entity.getJdbcUrlOverride(),
                entity.getBaseUrl(),
                entity.getCredentialId(),
                entity.getConnectTimeoutMs(),
                entity.getReadTimeoutMs(),
                entity.getLastTestAt(),
                entity.getLastTestSuccess(),
                entity.getLastTestMessage(),
                entity.getLastTestLatencyMs(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy());
    }
}
