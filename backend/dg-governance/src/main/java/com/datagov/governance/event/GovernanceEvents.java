package com.datagov.governance.event;

import java.time.Instant;

/**
 * Governance Space 对外发布的事件。
 *
 * <p>与 Runtime / Metadata 同构:P6 走 Spring 的进程内事件总线,
 * 契约里的 Kafka topic {@code dg.governance.report} 是拆进程之后的事。
 * 事件保持<b>自足</b>(不含只有 Governance 才能解引用的句柄),这样搬到
 * Kafka 时改的是发布器,不是消费者。
 *
 * <p>命名一律用过去式:事件表达"已经发生的事实",不是"请你去做某事"。
 * 报告发布<b>不触发任何 Space 的动作</b> —— 与"Governance 只发事件不发命令"
 * 一致(docs/space-contracts/governance.yaml 的 must_not_do)。
 */
public final class GovernanceEvents {

    private GovernanceEvents() {
    }

    /**
     * 报告发布了新版本。
     *
     * @param version 本次发布的版本号;首版为 1
     */
    public record AnalysisReportPublished(
            String workspaceId, String reportId, int version, Instant occurredAt) {
    }

    /** 报告被撤回。撤回后仍可查,只是默认列表不再显示 */
    public record AnalysisReportRetracted(
            String workspaceId, String reportId, Instant occurredAt) {
    }
}
