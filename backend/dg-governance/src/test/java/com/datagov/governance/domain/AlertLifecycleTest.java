package com.datagov.governance.domain;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 告警状态机(序号 25/26,SPACE-MODEL.md E.6)。 */
class AlertLifecycleTest {

    @Nested
    @DisplayName("推送路径")
    class Notification {

        @Test
        @DisplayName("正常路径:触发 → 推送中 → 已通知")
        void happyPath() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.TRIGGERED, AlertStatus.NOTIFYING)).isTrue();
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.NOTIFYING, AlertStatus.NOTIFIED)).isTrue();
        }

        @Test
        @DisplayName("没配渠道时直接落已通知 —— 界面上仍然看得见它")
        void noChannelStillNotified() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.TRIGGERED, AlertStatus.NOTIFIED)).isTrue();
        }

        @Test
        @DisplayName("渠道不可达落推送失败,而不是静默丢弃")
        void unreachableChannelFails() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.NOTIFYING, AlertStatus.NOTIFY_FAILED)).isTrue();
        }

        @Test
        @DisplayName("渠道恢复后可以重推")
        void canRetryAfterChannelRecovers() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.NOTIFY_FAILED, AlertStatus.NOTIFYING)).isTrue();
        }
    }

    @Nested
    @DisplayName("抑制(需求里的「告警频率」)")
    class Suppression {

        @Test
        @DisplayName("被抑制是一个独立状态,不是「未通知」")
        void suppressedIsDistinct() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.TRIGGERED, AlertStatus.SUPPRESSED)).isTrue();
            // 三种"没送出去"要能分开:配置生效、渠道故障、还没轮到它
            assertThat(AlertStatus.SUPPRESSED).isNotEqualTo(AlertStatus.NOTIFY_FAILED);
        }

        @Test
        @DisplayName("被抑制的告警仍然能被认领与关闭 —— 抑制的是推送,不是问题")
        void suppressedCanStillBeHandled() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.SUPPRESSED, AlertStatus.ACKNOWLEDGED)).isTrue();
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.SUPPRESSED, AlertStatus.RESOLVED)).isTrue();
        }

        @Test
        @DisplayName("被抑制的仍算未关闭 —— 它只是没推送,问题还在")
        void suppressedIsStillOpen() {
            assertThat(AlertStatus.SUPPRESSED.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("处理与终态")
    class Handling {

        @Test
        @DisplayName("已解决是唯一的终态")
        void onlyResolvedIsTerminal() {
            assertThat(AlertLifecycle.MACHINE.terminals())
                    .containsExactly(AlertStatus.RESOLVED);
        }

        @Test
        @DisplayName("已解决之后不能再改状态")
        void resolvedIsFinal() {
            assertThat(AlertLifecycle.MACHINE.nextStates(AlertStatus.RESOLVED)).isEmpty();
            assertThatThrownBy(() -> AlertLifecycle.MACHINE.checkTransition(
                    AlertStatus.RESOLVED, AlertStatus.ACKNOWLEDGED))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("可以跳过认领直接关闭 —— 值班的人常常是修完了才回来点")
        void canResolveWithoutAcknowledging() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.NOTIFIED, AlertStatus.RESOLVED)).isTrue();
        }

        @Test
        @DisplayName("推送失败的也能关闭 —— 没人收到通知不该让告警永远关不掉")
        void failedNotifyCanStillResolve() {
            assertThat(AlertLifecycle.MACHINE.canTransition(
                    AlertStatus.NOTIFY_FAILED, AlertStatus.RESOLVED)).isTrue();
        }

        @Test
        @DisplayName("除已解决外都算未关闭")
        void openMeansNotResolved() {
            long open = Arrays.stream(AlertStatus.values()).filter(AlertStatus::isOpen).count();
            assertThat(open).isEqualTo(AlertStatus.values().length - 1);
        }
    }
}
