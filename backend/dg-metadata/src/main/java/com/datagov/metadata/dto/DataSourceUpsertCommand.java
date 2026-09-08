package com.datagov.metadata.dto;

import com.datagov.data.spi.DataSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 新建 / 编辑数据源的入参。
 *
 * <p>注意<b>没有口令字段</b>。用户在表单里填的口令由 {@code dg-app} 装配层
 * 先交给 Platform 创建或更新一条 Credential,再把得到的 {@link #credentialId}
 * 传进来。Metadata 全程不接触明文 —— 这条编排放在装配层而不是这里,
 * 正是为了让 Metadata 的 must_not_do(不得存储凭据明文)在代码结构上成立,
 * 而不是靠开发者自觉。
 */
public record DataSourceUpsertCommand(

        @NotBlank(message = "数据源名称不能为空")
        @Size(max = 128, message = "数据源名称不得超过 128 字符")
        String name,

        @NotNull(message = "数据源类型不能为空")
        DataSourceType type,

        @Size(max = 512, message = "描述不得超过 512 字符")
        String description,

        /** 所属目录(功能 5);null 表示未分类 —— 强制归类会让新建多一步无谓的选择 */
        String catalogId,

        String host,
        Integer port,
        String databaseName,
        String username,
        Map<String, String> properties,
        String jdbcUrlOverride,
        String baseUrl,

        /** 指向 pf_credential.id;无需认证的数据源可为 null */
        String credentialId,

        Integer connectTimeoutMs,
        Integer readTimeoutMs
) {

    public DataSourceUpsertCommand {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
