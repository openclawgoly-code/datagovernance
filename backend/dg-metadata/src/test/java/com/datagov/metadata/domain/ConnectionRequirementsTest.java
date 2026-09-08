package com.datagov.metadata.domain;

import com.datagov.common.error.BizException;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接参数校验与变更判定 —— 纯领域规则,一个桩都不需要。
 */
@DisplayName("连接参数规则")
class ConnectionRequirementsTest {

    @Nested
    @DisplayName("多节点校验(功能 2)")
    class Nodes {

        @Test
        @DisplayName("Doris 可以配多个 FE 节点")
        void mppAcceptsExtraNodes() {
            assertThatCode(() -> ConnectionRequirements.validate(doris(
                    node("fe-2", 9030), node("fe-3", 9030))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非 MPP 类型配了节点直接报错,而不是悄悄忽略")
        void nonMppRejectsNodes() {
            assertThatThrownBy(() -> ConnectionRequirements.validate(
                    command(DataSourceType.MYSQL, "db-1", 3306, List.of(node("db-2", 3306)))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不支持多节点");
        }

        @Test
        @DisplayName("与主节点重复的附加节点被拒绝 —— 那是假的高可用")
        void duplicateOfPrimaryIsRejected() {
            assertThatThrownBy(() -> ConnectionRequirements.validate(doris(node("fe-1", 9030))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("重复");
        }

        @Test
        @DisplayName("主节点端口留空时按默认端口归一化后仍能查出重复")
        void duplicateIsDetectedAcrossDefaultPort() {
            DataSourceUpsertCommand command = command(DataSourceType.DORIS, "fe-1", null,
                    List.of(node("fe-1", DataSourceType.DORIS.defaultPort())));
            assertThatThrownBy(() -> ConnectionRequirements.validate(command))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("重复");
        }

        @Test
        @DisplayName("附加节点之间互相重复也被拒绝")
        void duplicateAmongExtrasIsRejected() {
            assertThatThrownBy(() -> ConnectionRequirements.validate(
                    doris(node("fe-2", 9030), node("FE-2", 9030))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("重复");
        }

        @Test
        @DisplayName("节点主机为空 / 端口越界被拒绝")
        void malformedNodeIsRejected() {
            assertThatThrownBy(() -> ConnectionRequirements.validate(doris(node("  ", 9030))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("主机地址不能为空");

            assertThatThrownBy(() -> ConnectionRequirements.validate(doris(node("fe-2", 70000))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("1-65535");
        }

        @Test
        @DisplayName("节点数量有上限")
        void tooManyNodesRejected() {
            ConnectionConfig.Node[] many = IntStream
                    .rangeClosed(1, ConnectionRequirements.MAX_EXTRA_NODES + 1)
                    .mapToObj(i -> node("fe-extra-" + i, 9030))
                    .toArray(ConnectionConfig.Node[]::new);
            assertThatThrownBy(() -> ConnectionRequirements.validate(doris(many)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("最多");
        }
    }

    @Nested
    @DisplayName("连接变更判定")
    class Changed {

        private final ConnectionRequirements.Snapshot stored = new ConnectionRequirements.Snapshot(
                "fe-1", 9030, "warehouse", "etl",
                null, null, null, null, "cred_1");

        @Test
        @DisplayName("只改名称/描述不算连接变更 —— 不该为改错别字重测一次连接")
        void renameIsNotAConnectionChange() {
            DataSourceUpsertCommand renamed = new DataSourceUpsertCommand(
                    "新名字", DataSourceType.DORIS, "新描述", null,
                    "fe-1", 9030, "warehouse", "etl", Map.of(), List.of(),
                    null, null, "cred_1", null, null);

            assertThat(ConnectionRequirements.connectionChanged(renamed, stored, null, null))
                    .isFalse();
        }

        @Test
        @DisplayName("加一个节点算连接变更 —— 改了节点就是连到别的集群")
        void addingANodeIsAConnectionChange() {
            DataSourceUpsertCommand withNode = command(DataSourceType.DORIS, "fe-1", 9030,
                    List.of(node("fe-2", 9030)));

            assertThat(ConnectionRequirements.connectionChanged(
                    withNode, stored, null, "[{\"host\":\"fe-2\",\"port\":9030}]"))
                    .isTrue();
        }

        @Test
        @DisplayName("换凭据算连接变更")
        void swappingCredentialIsAConnectionChange() {
            DataSourceUpsertCommand rebound = new DataSourceUpsertCommand(
                    "ds", DataSourceType.DORIS, null, null,
                    "fe-1", 9030, "warehouse", "etl", Map.of(), List.of(),
                    null, null, "cred_2", null, null);

            assertThat(ConnectionRequirements.connectionChanged(rebound, stored, null, null))
                    .isTrue();
        }
    }

    private static ConnectionConfig.Node node(String host, int port) {
        return new ConnectionConfig.Node(host, port);
    }

    private static DataSourceUpsertCommand doris(ConnectionConfig.Node... nodes) {
        return command(DataSourceType.DORIS, "fe-1", 9030, List.of(nodes));
    }

    private static DataSourceUpsertCommand command(DataSourceType type, String host, Integer port,
                                                   List<ConnectionConfig.Node> nodes) {
        return new DataSourceUpsertCommand(
                "ds", type, null, null,
                host, port, "warehouse", "etl", Map.of(), nodes,
                null, null, "cred_1", null, null);
    }
}
