package com.datagov.platform.domain;

import com.datagov.common.error.BizException;
import com.datagov.platform.entity.WorkspaceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.datagov.platform.entity.WorkspaceStatus.ACTIVE;
import static com.datagov.platform.entity.WorkspaceStatus.SUSPENDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 空间状态机(功能 28)。
 *
 * <p>与数据源状态机同一套断言:所有状态可达、无死状态、每条迁移都有触发器。
 * 两个状态的机器今天当然过得了,价值在于 ArchiveWorkspace 补进来的那天 ——
 * 到时新增的边会被这里的自检一并检查,而不必有人记得手工复核。
 */
@DisplayName("空间状态机")
class WorkspaceLifecycleTest {

    @Test
    @DisplayName("完整性自检通过:所有状态可达、无死状态、每条迁移都有触发器")
    void machineIsStructurallyComplete() {
        List<String> violations = WorkspaceLifecycle.MACHINE.validate();
        assertThat(violations).as("状态机结构违规: %s", violations).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE ⇄ SUSPENDED 双向可迁移,且没有终态")
    void suspendAndEnableAreBothReversible() {
        assertThat(WorkspaceLifecycle.MACHINE.canTransition(ACTIVE, SUSPENDED)).isTrue();
        assertThat(WorkspaceLifecycle.MACHINE.canTransition(SUSPENDED, ACTIVE)).isTrue();

        // 没有终态是刻意的:停用必须可逆,否则它就成了变相的删除
        assertThat(WorkspaceLifecycle.MACHINE.terminals()).isEmpty();
    }

    @Test
    @DisplayName("自环不被允许 —— 幂等由 Service 处理,不靠状态机放行")
    void selfTransitionIsRejected() {
        assertThatThrownBy(() -> WorkspaceLifecycle.MACHINE.checkTransition(ACTIVE, ACTIVE))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("枚举里的每个状态都覆盖到了")
    void everyStateIsReachable() {
        assertThat(WorkspaceLifecycle.MACHINE.reachableStates())
                .containsExactlyInAnyOrder(WorkspaceStatus.values());
    }

    @Test
    @DisplayName("触发器名与 F.1 的 Command 名字面一致")
    void triggersMatchCommandNames() {
        assertThat(WorkspaceLifecycle.MACHINE.transitions())
                .extracting(t -> t.from() + "->" + t.to() + ":" + t.trigger())
                .containsExactlyInAnyOrder(
                        "ACTIVE->SUSPENDED:SuspendWorkspace",
                        "SUSPENDED->ACTIVE:EnableWorkspace");
    }
}
