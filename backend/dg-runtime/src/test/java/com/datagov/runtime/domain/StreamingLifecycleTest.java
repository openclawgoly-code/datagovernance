package com.datagov.runtime.domain;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流任务运行态状态机(序号 18,SPACE-MODEL.md E.3)。
 *
 * <p>这台状态机是流任务与批任务"根本不同"(架构风险 R5)的落地处。所以这里
 * 断言的重点不是"迁移合法",而是<b>它与 {@link ExecutionLifecycle} 确实不一样</b>。
 */
class StreamingLifecycleTest {

    @Nested
    @DisplayName("启动与停止")
    class StartStop {

        @Test
        @DisplayName("未启动 → 启动中 → 运行中")
        void happyPath() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.PUBLISHED, StreamingStatus.STARTING)).isTrue();
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.STARTING, StreamingStatus.RUNNING)).isTrue();
        }

        @Test
        @DisplayName("停止是两段式的 —— 落 savepoint 要时间")
        void stopIsTwoPhase() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.RUNNING, StreamingStatus.STOPPING)).isTrue();
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.STOPPING, StreamingStatus.STOPPED)).isTrue();
            // 不许一步到位:那会产生一个谎言 —— 记录说已停止,而作业还在落 savepoint
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.RUNNING, StreamingStatus.STOPPED)).isFalse();
        }

        @Test
        @DisplayName("启动过程中可以叫停 —— 点了启动又后悔是常见操作")
        void canStopWhileStarting() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.STARTING, StreamingStatus.STOPPING)).isTrue();
        }

        @Test
        @DisplayName("已停止不是终态 —— 可以从 savepoint 恢复")
        void stoppedCanRestart() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.STOPPED, StreamingStatus.STARTING)).isTrue();
            assertThat(StreamingStatus.STOPPED.canStart()).isTrue();
        }

        @Test
        @DisplayName("保活失败之后人工还能拉起来")
        void failedCanRestartManually() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.FAILED, StreamingStatus.STARTING)).isTrue();
        }

        @Test
        @DisplayName("运行中不能再启动一次")
        void cannotStartTwice() {
            assertThat(StreamingStatus.RUNNING.canStart()).isFalse();
            assertThatThrownBy(() -> StreamingLifecycle.MACHINE.checkTransition(
                    StreamingStatus.RUNNING, StreamingStatus.STARTING))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("保活")
    class Keepalive {

        @Test
        @DisplayName("运行中挂掉进重启,不是失败 —— 自动重启是常态")
        void failureGoesToRestarting() {
            assertThat(StreamingLifecycle.onFailure(StreamingStatus.RUNNING, 0))
                    .isEqualTo(StreamingStatus.RESTARTING);
            assertThat(StreamingLifecycle.onFailure(StreamingStatus.RUNNING, 3))
                    .isEqualTo(StreamingStatus.RESTARTING);
        }

        @Test
        @DisplayName("重启预算用光才落保活失败")
        void exhaustedBudgetFails() {
            assertThat(StreamingLifecycle.onFailure(StreamingStatus.RUNNING,
                    StreamingLifecycle.MAX_RESTART_ATTEMPTS))
                    .isEqualTo(StreamingStatus.FAILED);
        }

        @Test
        @DisplayName("启动就起不来直接失败 —— 那类问题重启多少次都一样")
        void startupFailureSkipsRestart() {
            assertThat(StreamingLifecycle.onFailure(StreamingStatus.STARTING, 0))
                    .isEqualTo(StreamingStatus.FAILED);
        }

        @Test
        @DisplayName("停止过程中挂掉记为已停止 —— 用户要的结果已经达到")
        void failureWhileStoppingIsStopped() {
            assertThat(StreamingLifecycle.onFailure(StreamingStatus.STOPPING, 0))
                    .isEqualTo(StreamingStatus.STOPPED);
        }

        @Test
        @DisplayName("重启途中再挂是自环,不是新状态")
        void restartingSelfLoop() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.RESTARTING, StreamingStatus.RESTARTING)).isTrue();
        }

        @Test
        @DisplayName("停止的意图优先于保活")
        void stopBeatsKeepalive() {
            assertThat(StreamingLifecycle.MACHINE.canTransition(
                    StreamingStatus.RESTARTING, StreamingStatus.STOPPING)).isTrue();
        }

        @Test
        @DisplayName("退避是指数的,并且有上限")
        void backoffIsExponentialAndCapped() {
            long first = StreamingLifecycle.backoffMillis(0);
            long second = StreamingLifecycle.backoffMillis(1);
            assertThat(second).isGreaterThan(first);
            // 上限 5 分钟:不退避会在下游持续不可用时把重启变成一次对下游的压测
            assertThat(StreamingLifecycle.backoffMillis(20)).isEqualTo(300_000L);
        }
    }

    @Nested
    @DisplayName("与批执行状态机的区别(架构风险 R5)")
    class DistinctFromBatch {

        @Test
        @DisplayName("流任务没有「成功」这个状态")
        void noSucceededState() {
            // 一个跑了三个月的实时任务,问它"这次执行成功了吗"是没有意义的问题
            assertThat(java.util.Arrays.stream(StreamingStatus.values()).map(Enum::name))
                    .doesNotContain("SUCCEEDED");
        }

        @Test
        @DisplayName("活跃状态有三个,而批执行只有 RUNNING 一个")
        void threeActiveStates() {
            assertThat(java.util.Arrays.stream(StreamingStatus.values())
                    .filter(StreamingStatus::isActive).toList())
                    .containsExactlyInAnyOrder(StreamingStatus.STARTING,
                            StreamingStatus.RUNNING, StreamingStatus.RESTARTING);
        }

        @Test
        @DisplayName("没有不可逃逸的终态 —— 连保活失败都能人工拉起")
        void noAbsorbingTerminal() {
            for (StreamingStatus status : StreamingStatus.values()) {
                assertThat(StreamingLifecycle.MACHINE.nextStates(status))
                        .as("%s 应当还有出路", status)
                        .isNotEmpty();
            }
        }
    }
}
