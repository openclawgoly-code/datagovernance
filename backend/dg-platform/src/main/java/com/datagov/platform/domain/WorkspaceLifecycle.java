package com.datagov.platform.domain;

import com.datagov.common.lifecycle.StateMachine;
import com.datagov.platform.entity.WorkspaceStatus;

/**
 * 空间生命周期(功能 28「空间启用/停用」)—— 对应 SPACE-MODEL.md E.7 的 {@code ACTIVE ⇄ SUSPENDED}。
 *
 * <p>状态机只有两个状态、两条边,写成显式状态机看似小题大做,但它换来的是
 * 与其它六个核心对象一致的自检能力:{@code validate()} 能断言没有孤岛状态、
 * 没有死状态、每条边都有触发器名。一个"只有两个状态"的对象今天不会出错,
 * 等 ArchiveWorkspace 补上时才会 —— 那时有状态机在,新增的边会被同一套断言检查。
 *
 * <p>{@link WorkspaceStatus} 里解释了为什么暂不支持 ARCHIVED:F.1 的 Command
 * 清单里没有对应的触发器,而"没有 Command 触发的状态迁移"按 E 章节自己的
 * 原则就是设计缺陷。
 */
public final class WorkspaceLifecycle {

    /** SuspendWorkspace 的触发器名 —— 与 F.1 的 Command 名保持字面一致。 */
    public static final String TRIGGER_SUSPEND = "SuspendWorkspace";

    /** EnableWorkspace 的触发器名。 */
    public static final String TRIGGER_ENABLE = "EnableWorkspace";

    public static final StateMachine<WorkspaceStatus> MACHINE =
            StateMachine.builder(WorkspaceStatus.class, WorkspaceStatus.ACTIVE)
                    .allow(WorkspaceStatus.ACTIVE, WorkspaceStatus.SUSPENDED, TRIGGER_SUSPEND)
                    .allow(WorkspaceStatus.SUSPENDED, WorkspaceStatus.ACTIVE, TRIGGER_ENABLE)
                    .build();

    private WorkspaceLifecycle() {
    }
}
