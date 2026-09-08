package com.datagov.runtime.domain;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.datagov.runtime.domain.ExecutionStatus.CANCELED;
import static com.datagov.runtime.domain.ExecutionStatus.CANCELING;
import static com.datagov.runtime.domain.ExecutionStatus.DISPATCHED;
import static com.datagov.runtime.domain.ExecutionStatus.FAILED;
import static com.datagov.runtime.domain.ExecutionStatus.PENDING;
import static com.datagov.runtime.domain.ExecutionStatus.RUNNING;
import static com.datagov.runtime.domain.ExecutionStatus.SUCCEEDED;
import static com.datagov.runtime.domain.ExecutionStatus.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("执行状态机")
class ExecutionLifecycleTest {

    @Test
    @DisplayName("完整性自检通过:所有状态可达、无死状态、每条迁移都有触发器")
    void machineIsStructurallyComplete() {
        List<String> violations = ExecutionLifecycle.MACHINE.validate();
        assertThat(violations).as("状态机结构违规: %s", violations).isEmpty();
    }

    @Test
    @DisplayName("正常路径:PENDING → DISPATCHED → RUNNING → SUCCEEDED")
    void happyPath() {
        assertThat(ExecutionLifecycle.MACHINE.canTransition(PENDING, DISPATCHED)).isTrue();
        assertThat(ExecutionLifecycle.MACHINE.canTransition(DISPATCHED, RUNNING)).isTrue();
        assertThat(ExecutionLifecycle.MACHINE.canTransition(RUNNING, SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("下发被拒直接落 FAILED —— 不能让它停在 PENDING 静默丢失")
    void dispatchRejectedIsTerminal() {
        assertThat(ExecutionLifecycle.MACHINE.canTransition(PENDING, FAILED)).isTrue();
    }

    @Test
    @DisplayName("取消是两段式的:先 CANCELING 再 CANCELED")
    void cancelIsTwoPhase() {
        assertThat(ExecutionLifecycle.MACHINE.canTransition(RUNNING, CANCELING)).isTrue();
        assertThat(ExecutionLifecycle.MACHINE.canTransition(CANCELING, CANCELED)).isTrue();
        // 不允许一步到位:那会产生一个谎言 —— 记录说已取消,作业还在跑
        assertThat(ExecutionLifecycle.MACHINE.canTransition(RUNNING, CANCELED)).isFalse();
    }

    @Test
    @DisplayName("还没下发就取消,直接落终态,不必等任何人回话")
    void cancelBeforeDispatchIsImmediate() {
        assertThat(ExecutionLifecycle.MACHINE.canTransition(PENDING, CANCELED)).isTrue();
    }

    @Test
    @DisplayName("取消途中跑完了,据实记成成功而不是 CANCELED")
    void raceBetweenCancelAndCompletionIsRecordedHonestly() {
        assertThat(ExecutionLifecycle.MACHINE.canTransition(CANCELING, SUCCEEDED)).isTrue();
        assertThat(ExecutionLifecycle.MACHINE.canTransition(CANCELING, FAILED)).isTrue();
    }

    @Test
    @DisplayName("四个终态都没有出边")
    void terminalsHaveNoOutgoingEdges() {
        assertThat(ExecutionLifecycle.MACHINE.terminals())
                .containsExactlyInAnyOrder(SUCCEEDED, FAILED, CANCELED, TIMEOUT);
        for (ExecutionStatus terminal : ExecutionLifecycle.MACHINE.terminals()) {
            assertThat(ExecutionLifecycle.MACHINE.nextStates(terminal)).isEmpty();
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    @Test
    @DisplayName("只有失败与超时可以重试 —— 取消是人叫停的,成功没必要")
    void onlyFailureAndTimeoutAreRetryable() {
        assertThat(ExecutionLifecycle.retryable(FAILED)).isTrue();
        assertThat(ExecutionLifecycle.retryable(TIMEOUT)).isTrue();
        assertThat(ExecutionLifecycle.retryable(CANCELED)).isFalse();
        assertThat(ExecutionLifecycle.retryable(SUCCEEDED)).isFalse();
    }

    @Test
    @DisplayName("超时计入失败率,取消不计 —— 两者的口径只有一个定义")
    void timeoutCountsAsFailureButCancelDoesNot() {
        assertThat(TIMEOUT.countsAsFailure()).isTrue();
        assertThat(FAILED.countsAsFailure()).isTrue();
        assertThat(CANCELED.countsAsFailure()).isFalse();
        assertThat(SUCCEEDED.countsAsFailure()).isFalse();
    }

    @Test
    @DisplayName("非法迁移抛 409")
    void illegalTransitionIsRejected() {
        assertThatThrownBy(() -> ExecutionLifecycle.MACHINE.checkTransition(SUCCEEDED, RUNNING))
                .isInstanceOf(BizException.class);
    }
}
