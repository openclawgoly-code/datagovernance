package com.datagov.metadata.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.dto.DataSourceUpsertCommand;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 附加节点数量上限。FE 通常 3 台,给到 8 已经很宽裕;设上限是因为节点列表
     * 会被拼进 JDBC URL,无上限意味着一个超长 URL 可以从表单一路传到驱动。
     */
    public static final int MAX_EXTRA_NODES = 8;

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

        validateNodes(command);
    }

    /**
     * 校验附加节点(功能 2)。
     *
     * <p>非 MPP 类型填了节点直接报错,而不是静默忽略 —— 用户填了一堆节点却
     * 毫无效果,比一条明确的报错难排查得多。
     */
    private static void validateNodes(DataSourceUpsertCommand command) {
        List<ConnectionConfig.Node> nodes = command.nodes();
        if (nodes.isEmpty()) {
            return;
        }
        if (command.type().family() != DataSourceType.Family.MPP) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "%s 类型不支持多节点配置,附加节点仅对 Doris / StarRocks 有效"
                            .formatted(command.type().displayName()));
        }
        if (nodes.size() > MAX_EXTRA_NODES) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "附加节点最多 %d 个,当前 %d 个".formatted(MAX_EXTRA_NODES, nodes.size()));
        }

        // 主节点也参与查重:host+port 与主节点相同的"附加"节点是纯粹的浪费,
        // 驱动会把同一台机器当成两个故障转移目标,高可用是假的。
        int defaultPort = command.type().defaultPort();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(endpointKey(command.host(), command.port(), defaultPort));
        for (ConnectionConfig.Node node : nodes) {
            if (!isPresent(node.host())) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID, "附加节点的主机地址不能为空");
            }
            if (node.port() < 1 || node.port() > 65535) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "附加节点 %s 的端口必须在 1-65535 之间,当前为 %d"
                                .formatted(node.host(), node.port()));
            }
            if (!seen.add(endpointKey(node.host(), node.port(), defaultPort))) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "节点 %s:%d 重复".formatted(node.host().trim(), node.port()));
            }
        }
    }

    /**
     * 端点标识。空端口按该类型的默认端口归一化 —— 否则"主节点留空 + 附加节点
     * 显式填 9030"这种最常见的重复配法会漏过查重。
     */
    private static String endpointKey(String host, Integer port, int defaultPort) {
        int effective = port == null || port <= 0 ? defaultPort : port;
        return (host == null ? "" : host.trim().toLowerCase()) + ":" + effective;
    }

    /**
     * 已存下来的那一份连接参数。
     *
     * <p>存在的理由是可读性:{@link #connectionChanged} 原本是十个同类型的位置参数,
     * 相邻的 {@code propertiesJson} 与 {@code newPropertiesJson} 一旦传反,
     * 编译器不会有任何反应,而后果是"改了配置却不重置状态"这种沉默的错误。
     */
    public record Snapshot(String host, Integer port, String database, String username,
                           String propertiesJson, String nodesJson,
                           String jdbcUrlOverride, String baseUrl, String credentialId) {
    }

    /**
     * 判断两次配置之间,<b>连接参数</b>是否发生了变化。
     *
     * <p>只改名称或描述不该让一个已验证通过的数据源被打回 DRAFT ——
     * 那会逼用户为了改个错别字而重新测一遍连接。
     *
     * <p>节点列表算连接参数:改了节点就是连到别的集群了(功能 2)。
     * 而 properties 里的驱动调优参数也算 —— 一个 {@code useSSL=true} 足以
     * 让原本能连的库连不上,不重测就等于让一条过期的结论继续挂在界面上。
     */
    public static boolean connectionChanged(DataSourceUpsertCommand command, Snapshot stored,
                                            String newPropertiesJson, String newNodesJson) {
        return !equal(command.host(), stored.host())
                || !equal(command.port(), stored.port())
                || !equal(command.databaseName(), stored.database())
                || !equal(command.username(), stored.username())
                || !equal(newPropertiesJson, stored.propertiesJson())
                || !equal(newNodesJson, stored.nodesJson())
                || !equal(command.jdbcUrlOverride(), stored.jdbcUrlOverride())
                || !equal(command.baseUrl(), stored.baseUrl())
                || !equal(command.credentialId(), stored.credentialId());
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
