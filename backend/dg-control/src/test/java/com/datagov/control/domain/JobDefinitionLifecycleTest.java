package com.datagov.control.domain;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.datagov.control.domain.JobDefinitionStatus.ARCHIVED;
import static com.datagov.control.domain.JobDefinitionStatus.DRAFT;
import static com.datagov.control.domain.JobDefinitionStatus.OFFLINE;
import static com.datagov.control.domain.JobDefinitionStatus.PAUSED;
import static com.datagov.control.domain.JobDefinitionStatus.PUBLISHED;
import static com.datagov.control.domain.JobDefinitionStatus.SCHEDULING;
import static com.datagov.control.domain.JobDefinitionStatus.VALIDATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("任务定义状态机")
class JobDefinitionLifecycleTest {

    @Test
    @DisplayName("完整性自检通过:所有状态可达、无死状态、每条迁移都有触发器")
    void machineIsStructurallyComplete() {
        List<String> violations = JobDefinitionLifecycle.MACHINE.validate();
        assertThat(violations).as("状态机结构违规: %s", violations).isEmpty();
    }

    @Test
    @DisplayName("正常路径:DRAFT → VALIDATED → PUBLISHED → SCHEDULING")
    void happyPath() {
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(DRAFT, VALIDATED)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(VALIDATED, PUBLISHED)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(PUBLISHED, SCHEDULING)).isTrue();
    }

    @Test
    @DisplayName("任何状态下的修改都能回到 DRAFT —— 否则改不了配置就只能删掉重建")
    void updateAlwaysReturnsToDraft() {
        for (JobDefinitionStatus from : List.of(VALIDATED, PUBLISHED, SCHEDULING, PAUSED, OFFLINE)) {
            assertThat(JobDefinitionLifecycle.MACHINE.canTransition(from, DRAFT))
                    .as("%s 应能被修改回草稿", from)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("调度可暂停可恢复,也可解绑回到已发布")
    void scheduleIsFullyReversible() {
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(SCHEDULING, PAUSED)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(PAUSED, SCHEDULING)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(SCHEDULING, PUBLISHED)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(PAUSED, PUBLISHED)).isTrue();
    }

    @Test
    @DisplayName("下线后重新发布要经过 DRAFT —— 下线期间源表可能已经变了")
    void republishRequiresRecompile() {
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(OFFLINE, DRAFT)).isTrue();
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(OFFLINE, PUBLISHED)).isFalse();
    }

    @Test
    @DisplayName("ARCHIVED 是唯一终态")
    void archivedIsTheOnlyTerminal() {
        assertThat(JobDefinitionLifecycle.MACHINE.terminals()).containsExactly(ARCHIVED);
        assertThat(JobDefinitionLifecycle.MACHINE.nextStates(ARCHIVED)).isEmpty();
    }

    @Test
    @DisplayName("暂停中仍可手工触发 —— 暂停关掉的是自动触发")
    void pausedIsStillManuallyRunnable() {
        assertThat(PAUSED.isRunnable()).isTrue();
        assertThat(SCHEDULING.isRunnable()).isTrue();
        assertThat(PUBLISHED.isRunnable()).isTrue();
        assertThat(DRAFT.isRunnable()).isFalse();
        assertThat(OFFLINE.isRunnable()).isFalse();
    }

    @Test
    @DisplayName("草稿可以直接归档 —— 试错留下的垃圾定义不该只能永远留着")
    void draftCanBeArchivedDirectly() {
        assertThat(JobDefinitionLifecycle.MACHINE.canTransition(DRAFT, ARCHIVED)).isTrue();
    }

    @Test
    @DisplayName("非法迁移抛 409")
    void illegalTransitionIsRejected() {
        assertThatThrownBy(() -> JobDefinitionLifecycle.MACHINE.checkTransition(DRAFT, SCHEDULING))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("一次性任务与常驻任务都不允许绑调度")
    void nonSchedulableTypesAreDeclared() {
        assertThat(JobType.DB_MIGRATION.isSchedulable()).isFalse();
        assertThat(JobType.STREAMING.isSchedulable()).isFalse();
        assertThat(JobType.STREAMING.isLongRunning()).isTrue();
        assertThat(JobType.OFFLINE_SYNC.isSchedulable()).isTrue();
    }
}
