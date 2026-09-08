package com.datagov.metadata.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.lifecycle.StateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.datagov.metadata.domain.DataSourceStatus.ARCHIVED;
import static com.datagov.metadata.domain.DataSourceStatus.AVAILABLE;
import static com.datagov.metadata.domain.DataSourceStatus.DISABLED;
import static com.datagov.metadata.domain.DataSourceStatus.DRAFT;
import static com.datagov.metadata.domain.DataSourceStatus.TESTING;
import static com.datagov.metadata.domain.DataSourceStatus.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据源状态机。
 *
 * <p>第一个测试直接对应架构验证清单第 4 条「状态机完整性」——
 * 每个状态可达、无死状态、每条迁移有触发器。把它做成断言而不是文档里的承诺,
 * 是 {@link StateMachine#validate()} 存在的全部理由。
 */
@DisplayName("数据源状态机")
class DataSourceLifecycleTest {

    @Test
    @DisplayName("完整性自检通过:所有状态可达、无死状态、每条迁移都有触发器")
    void machineIsStructurallyComplete() {
        List<String> violations = DataSourceLifecycle.MACHINE.validate();
        assertThat(violations)
                .as("状态机结构违规: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("枚举里的每个状态都从初始状态可达")
    void everyStateIsReachable() {
        assertThat(DataSourceLifecycle.MACHINE.reachableStates())
                .containsExactlyInAnyOrder(DRAFT, TESTING, AVAILABLE, UNREACHABLE, DISABLED, ARCHIVED);
    }

    @Test
    @DisplayName("ARCHIVED 是唯一终态,且没有出边")
    void archivedIsTheOnlyTerminalState() {
        assertThat(DataSourceLifecycle.MACHINE.terminals()).containsExactly(ARCHIVED);
        assertThat(DataSourceLifecycle.MACHINE.nextStates(ARCHIVED)).isEmpty();
        assertThat(ARCHIVED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("正常验证路径:DRAFT → TESTING → AVAILABLE")
    void happyPath() {
        assertThat(DataSourceLifecycle.MACHINE.canTransition(DRAFT, TESTING)).isTrue();
        assertThat(DataSourceLifecycle.MACHINE.canTransition(TESTING, AVAILABLE)).isTrue();
        assertThat(AVAILABLE.isUsable()).isTrue();
    }

    @Test
    @DisplayName("手工测试失败回 DRAFT,周期探测失败进 UNREACHABLE —— 这两条边不能混")
    void manualFailureAndProbeFailureLandDifferently() {
        // 这是本状态机最容易被做错的地方。合并成一个"失败"状态,
        // 等于让用户自己去猜该改配置还是该找网管。
        assertThat(DataSourceLifecycle.afterConnectivityResult(TESTING, false, false))
                .as("手工测试失败 → 配置可能就没配对")
                .isEqualTo(DRAFT);

        assertThat(DataSourceLifecycle.afterConnectivityResult(AVAILABLE, false, true))
                .as("周期探测失败 → 本来是好的,环境出了状况")
                .isEqualTo(UNREACHABLE);

        assertThat(DataSourceLifecycle.afterConnectivityResult(UNREACHABLE, true, true))
                .as("探测恢复 → 回到可用")
                .isEqualTo(AVAILABLE);
    }

    @Test
    @DisplayName("UNREACHABLE 与 AVAILABLE 之间可以来回")
    void unreachableIsRecoverable() {
        assertThat(DataSourceLifecycle.MACHINE.canTransition(AVAILABLE, UNREACHABLE)).isTrue();
        assertThat(DataSourceLifecycle.MACHINE.canTransition(UNREACHABLE, AVAILABLE)).isTrue();
    }

    @Test
    @DisplayName("改连接参数把已验证的数据源打回 DRAFT")
    void updatingConnectionInvalidatesVerification() {
        // 不打回的话,用户可以把一个已验证的数据源改成任意错误配置,
        // 而列表里仍然显示"可用"
        assertThat(DataSourceLifecycle.MACHINE.canTransition(AVAILABLE, DRAFT)).isTrue();
        assertThat(DataSourceLifecycle.MACHINE.canTransition(UNREACHABLE, DRAFT)).isTrue();
    }

    @Test
    @DisplayName("停用后可重新启用,但回到 DRAFT 而不是直接可用")
    void reEnableRequiresRevalidation() {
        assertThat(DataSourceLifecycle.MACHINE.canTransition(DISABLED, DRAFT)).isTrue();
        // 停用期间目标端可能已变化,直接恢复成 AVAILABLE 等于宣称一个从未验证过的结论
        assertThat(DataSourceLifecycle.MACHINE.canTransition(DISABLED, AVAILABLE)).isFalse();
    }

    @Test
    @DisplayName("归档只能从 DISABLED 进入")
    void archiveOnlyFromDisabled() {
        assertThat(DataSourceLifecycle.MACHINE.canTransition(DISABLED, ARCHIVED)).isTrue();
        assertThat(DataSourceLifecycle.MACHINE.canTransition(AVAILABLE, ARCHIVED)).isFalse();
        assertThat(DataSourceLifecycle.MACHINE.canTransition(DRAFT, ARCHIVED)).isFalse();
    }

    @Test
    @DisplayName("非法迁移抛 SYS_ILLEGAL_STATE_TRANSITION,并提示允许的后继状态")
    void illegalTransitionIsRejectedWithGuidance() {
        assertThatThrownBy(() -> DataSourceLifecycle.MACHINE.checkTransition(DRAFT, AVAILABLE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许的状态迁移")
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("SYS_ILLEGAL_STATE_TRANSITION"));

        // 终态之后什么都不能做
        assertThatThrownBy(() -> DataSourceLifecycle.MACHINE.checkTransition(ARCHIVED, DRAFT))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("每条迁移都带触发器名,与 Command/Event 清单对得上")
    void everyTransitionNamesItsTrigger() {
        assertThat(DataSourceLifecycle.MACHINE.transitions())
                .allSatisfy(t -> assertThat(t.trigger()).isNotBlank())
                .extracting(StateMachine.Transition::trigger)
                .contains("TestConnectivity", "ConnectivitySucceeded", "ConnectivityFailed",
                        "ProbeFailed", "ProbeSucceeded", "DisableDataSource", "Archive");
    }
}
