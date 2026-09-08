package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagov.governance.domain.AlertStatus;
import lombok.Data;

import java.time.Instant;

/**
 * Governance Space 的持久化实体。
 */
public final class GovernanceEntities {

    private GovernanceEntities() {
    }

    /**
     * 告警规则(序号 25)。
     *
     * <p>需求给的四个维度:范围(全部任务 / 指定任务)、触发方式、通知渠道、
     * 告警频率。前三个直接落成列,第四个 —— 「告警频率」—— 落成
     * {@link #suppressWindowSeconds}:它在实现上就是"通知之后多久内不再通知"。
     *
     * <p>把它写成"每 N 分钟最多告警一次"也能实现同样的效果,但抑制窗口这个
     * 说法更准确:被抑制的告警<b>仍然记录</b>,只是不推送。两种说法在"要不要
     * 留下记录"这个问题上会给出不同的答案,而那正是排查时最需要的东西。
     */
    @Data
    @TableName("gv_alert_rule")
    public static class AlertRule {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        private String description;

        /**
         * 触发条件。
         *
         * <p>EXECUTION_FAILED / EXECUTION_TIMEOUT / EXECUTION_SLOW /
         * STREAMING_SUPERVISION_FAILED / DISPATCH_REJECTED
         */
        private String triggerType;

        /**
         * 作用范围:ALL 表示全部任务,SPECIFIC 表示只盯 {@link #targetJobIdsJson}。
         *
         * <p>需求原文就是这两种。没有做成"按标签匹配"之类更灵活的东西 ——
         * 那会引入一套匹配语言,而用户此刻要的只是"盯住这三个关键任务"。
         */
        private String scope;
        /** SPECIFIC 时的任务定义 ID 列表(JSON 数组) */
        private String targetJobIdsJson;

        /** EXECUTION_SLOW 用:超过这个毫秒数算慢 */
        private Long thresholdMs;

        /** 通知渠道 ID 列表(JSON 数组);空表示只记录不推送 */
        private String channelIdsJson;

        /**
         * 抑制窗口(秒)—— 需求里的「告警频率」。
         *
         * <p>0 表示不抑制。一个每分钟跑一次、连续失败两小时的任务,不抑制的话
         * 会推送 120 条 —— 那不是"及时",那是让人把告警静音。
         */
        private Integer suppressWindowSeconds;

        /** ENABLED / DISABLED */
        private String status;

        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;

        @TableLogic
        private Boolean deleted;
    }

    /**
     * 一条告警(序号 26)。
     *
     * <p>不可变的部分是"什么时候、因为什么触发的";可变的只有状态与处理人。
     * 告警内容不允许事后修改 —— 它是一条事实记录。
     */
    @Data
    @TableName("gv_alert")
    public static class Alert {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String ruleId;
        private String ruleName;

        private AlertStatus status;

        /**
         * 同源标识。
         *
         * <p>抑制窗口按它分组:同一个规则 + 同一个任务的连续失败算同源。
         * 若只按 ruleId 分组,一个"全部任务失败就告警"的规则会在 A 任务
         * 告警后把 B 任务的失败也抑制掉 —— 而那是两个不相关的故障。
         */
        private String sourceKey;

        /** 触发它的执行记录;不是由执行触发的(如数据源不可达)则为 null */
        private String executionId;
        private String jobRefId;
        private String jobName;

        private String title;
        private String content;
        /** INFO / WARNING / CRITICAL */
        private String severity;

        private Instant triggeredAt;
        private Instant notifiedAt;
        /** 被抑制时指向"压住它的那一条" —— 排查时能顺着链找到源头 */
        private String suppressedBy;

        private String acknowledgedBy;
        private Instant acknowledgedAt;
        private Instant resolvedAt;
        private String resolveNote;

        /** 推送失败的原因。渠道坏了这件事必须留痕 */
        private String notifyError;

        private Instant createdAt;
    }

    /**
     * 通知渠道(序号 33)。
     *
     * <p><b>归 Governance 而非配置模块</b>(架构风险 R2):渠道有连通性状态,
     * 一个发不出去的渠道会让所有挂在它上面的告警静默失效 —— 那是治理问题,
     * 不是配置项。
     */
    @Data
    @TableName("gv_alert_channel")
    public static class AlertChannel {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        /** EMAIL / WEBHOOK */
        private String type;

        /** 渠道配置(JSON):EMAIL 是收件人列表,WEBHOOK 是 URL 与请求头 */
        private String configJson;

        /** ACTIVE / UNREACHABLE / DISABLED */
        private String status;

        /** 最近一次连通性测试 */
        private Instant lastTestedAt;
        private Boolean lastTestSucceeded;
        private String lastTestMessage;

        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;

        @TableLogic
        private Boolean deleted;
    }

    /**
     * 审计记录(序号 27)。
     *
     * <p><b>不可变、只追加。</b> 没有 update 接口,没有逻辑删除列 —— 一条能被
     * 修改的审计记录不是审计记录。保留期通常远长于执行日志,所以它单独一张表
     * 而不是塞进执行记录里。
     */
    @Data
    @TableName("gv_audit_record")
    public static class AuditRecord {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;

        /** 谁 */
        private String userId;
        private String username;
        private String clientIp;

        /** 做了什么:CREATE / UPDATE / DELETE / EXECUTE / LOGIN / EXPORT … */
        private String action;
        /** 对什么:DATASOURCE / JOB / EXECUTION / RULE / WORKSPACE / USER … */
        private String resourceType;
        private String resourceId;
        private String resourceName;

        /** 归属哪个 Space。序号 27 明确要求跨数据集成与数据开发聚合 */
        private String ownerSpace;

        /** 成功与否。失败的操作同样要审计 —— 那往往才是要查的 */
        private Boolean succeeded;
        private String errorCode;

        /** 请求摘要(方法 + 路径)。不存请求体:里面可能有凭据 */
        private String requestSummary;
        private String detail;

        private Instant occurredAt;
    }
}
