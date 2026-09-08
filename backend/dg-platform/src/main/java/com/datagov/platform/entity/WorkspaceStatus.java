package com.datagov.platform.entity;

/**
 * {@link Workspace} 的生命周期状态 —— 对应 SPACE-MODEL.md E.7 的状态机 {@code ACTIVE ⇄ SUSPENDED}。
 *
 * <p>文档里 E.7 其实还有第三态 {@code ARCHIVED},但 F.1 给出的 Command 清单只有
 * {@code EnableWorkspace}/{@code SuspendWorkspace} 两个,没有 Archive 命令。
 * P1 只落地文档已经给出触发器的那部分 —— 没有 Command 触发的状态迁移在 E 章节
 * 自己的原则里就是"设计缺陷",所以宁可先不支持 ARCHIVED,也不要发明一个没有
 * Command 对应的迁移入口。等文档补齐 ArchiveWorkspace 后再扩展这个枚举。
 */
public enum WorkspaceStatus {
    ACTIVE,
    SUSPENDED
}
