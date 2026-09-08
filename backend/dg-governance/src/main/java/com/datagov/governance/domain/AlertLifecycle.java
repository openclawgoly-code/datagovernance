package com.datagov.governance.domain;

import com.datagov.common.lifecycle.StateMachine;

import static com.datagov.governance.domain.AlertStatus.ACKNOWLEDGED;
import static com.datagov.governance.domain.AlertStatus.NOTIFIED;
import static com.datagov.governance.domain.AlertStatus.NOTIFYING;
import static com.datagov.governance.domain.AlertStatus.NOTIFY_FAILED;
import static com.datagov.governance.domain.AlertStatus.RESOLVED;
import static com.datagov.governance.domain.AlertStatus.SUPPRESSED;
import static com.datagov.governance.domain.AlertStatus.TRIGGERED;

/** 告警状态机(SPACE-MODEL.md E.6,序号 25/26)。 */
public final class AlertLifecycle {

    public static final StateMachine<AlertStatus> MACHINE =
            StateMachine.builder(AlertStatus.class, TRIGGERED)
                    .allow(TRIGGERED, NOTIFYING, "NotifyAlert")
                    // 抑制窗口内:仍然记录,但不推送
                    .allow(TRIGGERED, SUPPRESSED, "AlertSuppressed")
                    // 没配渠道的规则,告警照样落库 —— 界面上仍然看得见
                    .allow(TRIGGERED, NOTIFIED, "AlertNotified")

                    .allow(NOTIFYING, NOTIFIED, "AlertNotified")
                    .allow(NOTIFYING, NOTIFY_FAILED, "AlertNotifyFailed")

                    .allow(NOTIFIED, ACKNOWLEDGED, "AcknowledgeAlert")
                    .allow(NOTIFIED, RESOLVED, "ResolveAlert")
                    .allow(ACKNOWLEDGED, RESOLVED, "ResolveAlert")

                    // 推送失败的也能认领与关闭:问题本身可能已经解决了,
                    // 而"没人收到通知"不该让这条告警永远关不掉
                    .allow(NOTIFY_FAILED, ACKNOWLEDGED, "AcknowledgeAlert")
                    .allow(NOTIFY_FAILED, RESOLVED, "ResolveAlert")
                    // 重试推送:渠道恢复之后
                    .allow(NOTIFY_FAILED, NOTIFYING, "NotifyAlert")

                    // 被抑制的同样能被认领/关闭 —— 抑制的是推送,不是问题
                    .allow(SUPPRESSED, ACKNOWLEDGED, "AcknowledgeAlert")
                    .allow(SUPPRESSED, RESOLVED, "ResolveAlert")

                    .terminal(RESOLVED)
                    .build();

    private AlertLifecycle() {
    }
}
