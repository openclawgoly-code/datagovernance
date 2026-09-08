package com.datagov.metadata.event;

import com.datagov.metadata.domain.DataSourceStatus;

import java.time.Instant;

/**
 * Metadata Space 对外发布的数据源领域事件。
 *
 * <p>对应 {@code docs/space-contracts/metadata.yaml} 的 {@code events_produced}。
 * 命名一律用<b>过去式</b> —— 事件表达的是"已经发生的事实",不是"请你去做某事"。
 * 一旦出现 {@code RefreshSchemaRequested} 这类命名,说明它其实是个 Command,
 * 走错了通道。
 *
 * <p>P1 用 Spring 的 {@code ApplicationEventPublisher} 在进程内投递,不引入消息中间件:
 * 此刻唯一的消费方(Governance)还不存在,为一个尚未出现的订阅者搭 Kafka
 * 是纯粹的成本。事件的<b>语义</b>先立住,投递机制到 P4 再换 —— 那时改的是
 * 发布器实现,不是这些 record 的定义。
 */
public final class DataSourceEvents {

    private DataSourceEvents() {
    }

    /** 数据源注册成功。 */
    public record DataSourceRegistered(
            String workspaceId, String dataSourceId, String name, String type, Instant occurredAt) {
    }

    /** 数据源定义被修改。携带前后状态,便于 Governance 判断是否需要重新审批。 */
    public record DataSourceUpdated(
            String workspaceId, String dataSourceId, int newVersion,
            boolean connectionChanged, DataSourceStatus statusAfter, Instant occurredAt) {
    }

    /** 数据源被人工停用。 */
    public record DataSourceDisabled(
            String workspaceId, String dataSourceId, String operator, Instant occurredAt) {
    }

    /**
     * 数据源从可用变为不可达。
     *
     * <p>{@code SPACE-MODEL.md} E.1:「UNREACHABLE 进入时发 DataSourceUnreachable 事件
     * → Governance 按 AlertRule 决定是否告警」。告警与否<b>不</b>由 Metadata 判断 ——
     * 它只负责陈述事实,要不要吵醒谁是 Governance 的策略。
     */
    public record DataSourceUnreachable(
            String workspaceId, String dataSourceId, String name,
            String reason, Instant occurredAt) {
    }

    /** 连通性验证通过,状态回到可用。 */
    public record DataSourceBecameAvailable(
            String workspaceId, String dataSourceId, long latencyMillis, Instant occurredAt) {
    }
}
