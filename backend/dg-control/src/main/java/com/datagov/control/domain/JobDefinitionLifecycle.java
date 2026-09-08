package com.datagov.control.domain;

import com.datagov.common.lifecycle.StateMachine;

import static com.datagov.control.domain.JobDefinitionStatus.ARCHIVED;
import static com.datagov.control.domain.JobDefinitionStatus.DRAFT;
import static com.datagov.control.domain.JobDefinitionStatus.OFFLINE;
import static com.datagov.control.domain.JobDefinitionStatus.PAUSED;
import static com.datagov.control.domain.JobDefinitionStatus.PUBLISHED;
import static com.datagov.control.domain.JobDefinitionStatus.SCHEDULING;
import static com.datagov.control.domain.JobDefinitionStatus.VALIDATED;

/**
 * 任务定义状态机(SPACE-MODEL.md E.2)。
 */
public final class JobDefinitionLifecycle {

    public static final StateMachine<JobDefinitionStatus> MACHINE =
            StateMachine.builder(JobDefinitionStatus.class, DRAFT)
                    // Submit 触发编译。编译成功进 VALIDATED,失败退回 DRAFT 并携带
                    // 错误定位 —— 失败必须回到可编辑的状态,否则用户改不了。
                    .allow(DRAFT, VALIDATED, "CompileSucceeded")
                    .allow(VALIDATED, DRAFT, "UpdateDefinition")

                    .allow(VALIDATED, PUBLISHED, "PublishDefinition")

                    // 已发布的定义被修改 → 回到草稿重新编译。
                    // 修改产生新版本,SCHEDULING 中的任务在下一次触发时才用新版本;
                    // 运行中的 Execution 始终绑定它启动时的 defVersion。
                    .allow(PUBLISHED, DRAFT, "UpdateDefinition")

                    .allow(PUBLISHED, SCHEDULING, "BindSchedule")
                    .allow(SCHEDULING, PAUSED, "PauseSchedule")
                    .allow(PAUSED, SCHEDULING, "ResumeSchedule")
                    // 解绑调度回到 PUBLISHED:任务还在,只是不再周期跑,仍可手工触发
                    .allow(SCHEDULING, PUBLISHED, "UnbindSchedule")
                    .allow(PAUSED, PUBLISHED, "UnbindSchedule")
                    // 暂停中的定义也要能改。不允许的话,运维发现配置有误时只能
                    // 先恢复调度再修改 —— 而恢复调度可能立刻触发一次错误的执行。
                    .allow(PAUSED, DRAFT, "UpdateDefinition")
                    .allow(SCHEDULING, DRAFT, "UpdateDefinition")

                    .allow(PUBLISHED, OFFLINE, "OfflineDefinition")
                    .allow(SCHEDULING, OFFLINE, "OfflineDefinition")
                    .allow(PAUSED, OFFLINE, "OfflineDefinition")
                    // 下线的定义重新发布:回到 DRAFT 而不是直接 PUBLISHED ——
                    // 下线期间源表可能已经变了,必须重新编译校验一次。
                    .allow(OFFLINE, DRAFT, "UpdateDefinition")

                    .allow(OFFLINE, ARCHIVED, "ArchiveDefinition")
                    // 从未发布过的草稿也要能归档,否则试错留下的垃圾定义只能永远留着
                    .allow(DRAFT, ARCHIVED, "ArchiveDefinition")
                    .allow(VALIDATED, ARCHIVED, "ArchiveDefinition")

                    .terminal(ARCHIVED)
                    .build();

    private JobDefinitionLifecycle() {
    }
}
