package com.datagov.metadata.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.dto.DataSourceUpsertCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * 按数据源类型校验必填连接参数。
 *
 * <p>放在 domain 而不是 service,是因为这套规则回答的是"什么样的连接配置才算
 * 一个合法的数据源定义",属于领域知识,与持久化和事务无关 —— 单测它不需要任何桩。
 *
 * <p>校验在<b>保存前</b>做,而不是等到连通性测试才暴露。理由很实际:
 * 一个缺了主机名的配置根本不该被存下来,存下来之后它会一直以 DRAFT 状态
 * 躺在列表里,而用户以为自己已经配好了。
 */
public final class ConnectionRequirements {

    private ConnectionRequirements() {
    }

    public static void validate(DataSourceUpsertCommand command) {
        DataSourceType type = command.type();
        List<String> missing = new ArrayList<>();

        // 直填 JDBC URL 时,host/port 由 URL 自己承载,不再强制
        boolean hasUrlOverride = isPresent(command.jdbcUrlOverride());

        switch (type.family()) {
            case RELATIONAL, MPP -> {
                if (!hasUrlOverride) {
                    if (!isPresent(command.host())) {
                        missing.add("主机地址");
                    }
                    // 端口可空 —— 连接器会回落到该类型的默认端口
                }
                // Oracle / 达梦 的 database 字段承载服务名或 SID,同样必填;
                // MySQL / PostgreSQL 允许留空(连默认库后再下钻)
                if (!hasUrlOverride
                        && (type == DataSourceType.ORACLE || type == DataSourceType.DAMENG)
                        && !isPresent(command.databaseName())) {
                    missing.add(type == DataSourceType.ORACLE ? "服务名或 SID" : "库名");
                }
            }
            case FILE -> {
                if (!isPresent(command.host())) {
                    missing.add("主机地址");
                }
            }
            case HTTP -> {
                if (!isPresent(command.baseUrl())) {
                    missing.add("接口地址(baseUrl)");
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "%s 类型的数据源缺少必填项: %s".formatted(
                            type.displayName(), String.join("、", missing)));
        }

        validateTimeout(command.connectTimeoutMs(), "连接超时");
        validateTimeout(command.readTimeoutMs(), "读取超时");

        if (command.port() != null && (command.port() < 1 || command.port() > 65535)) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "端口必须在 1-65535 之间,当前为 " + command.port());
        }
    }

    /**
     * 判断两次配置之间,<b>连接参数</b>是否发生了变化。
     *
     * <p>只改名称或描述不该让一个已验证通过的数据源被打回 DRAFT ——
     * 那会逼用户为了改个错别字而重新测一遍连接。
     */
    public static boolean connectionChanged(DataSourceUpsertCommand command,
                                            String host, Integer port, String database,
                                            String username, String propertiesJson,
                                            String jdbcUrlOverride, String baseUrl,
                                            String credentialId, String newPropertiesJson) {
        return !equal(command.host(), host)
                || !equal(command.port(), port)
                || !equal(command.databaseName(), database)
                || !equal(command.username(), username)
                || !equal(newPropertiesJson, propertiesJson)
                || !equal(command.jdbcUrlOverride(), jdbcUrlOverride)
                || !equal(command.baseUrl(), baseUrl)
                || !equal(command.credentialId(), credentialId);
    }

    private static void validateTimeout(Integer millis, String label) {
        if (millis != null && (millis < 1_000 || millis > 300_000)) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "%s 必须在 1000-300000 毫秒之间,当前为 %d".formatted(label, millis));
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || (left instanceof String s && s.isBlank())) {
            return right == null || (right instanceof String r && r.isBlank());
        }
        return left.equals(right);
    }
}
