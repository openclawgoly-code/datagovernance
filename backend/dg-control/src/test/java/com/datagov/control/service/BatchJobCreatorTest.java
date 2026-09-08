package com.datagov.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量创建的命名规则(功能 14)。
 *
 * <p>只测纯函数部分。创建本身走的是 {@code JobDefinitionService.create},那条路径
 * 已经被单条创建的测试与 verify-p2 的端到端断言覆盖 —— 在这里再 mock 一遍
 * mapper 只会得到一个测试替身之间互相验证的测试。
 */
class BatchJobCreatorTest {

    @Nested
    @DisplayName("任务名模板")
    class NamePattern {

        @Test
        @DisplayName("{table} 占位符被替换成表名")
        void replacesPlaceholder() {
            assertThat(BatchJobCreator.renderName("同步-{table}", "t_order"))
                    .isEqualTo("同步-t_order");
        }

        @Test
        @DisplayName("模板可以在任意位置带占位符")
        void placeholderAnywhere() {
            assertThat(BatchJobCreator.renderName("{table}_daily_sync", "t_user"))
                    .isEqualTo("t_user_daily_sync");
        }

        @Test
        @DisplayName("空模板走默认")
        void defaultsWhenBlank() {
            assertThat(BatchJobCreator.renderName(null, "t_order")).isEqualTo("同步-t_order");
            assertThat(BatchJobCreator.renderName("   ", "t_order")).isEqualTo("同步-t_order");
        }

        @Test
        @DisplayName("模板里没有占位符时仍然追加表名 —— 否则第二张表就撞名")
        void appendsTableWhenNoPlaceholder() {
            // 这是关键的一条:用户填了「每日同步」这样一个固定名字,20 张表会
            // 撞出 19 条"名称已存在"。追加表名让它仍然可用
            assertThat(BatchJobCreator.renderName("每日同步", "t_order"))
                    .isEqualTo("每日同步-t_order");
        }

        @Test
        @DisplayName("同一个模板对不同表产生不同的名字")
        void uniquePerTable() {
            String a = BatchJobCreator.renderName("同步-{table}", "t_a");
            String b = BatchJobCreator.renderName("同步-{table}", "t_b");
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("批量上限")
    class BatchLimit {

        @Test
        @DisplayName("上限是 200 —— 再多更像是想要整库迁移")
        void limitIsTwoHundred() {
            assertThat(BatchJobCreator.MAX_BATCH).isEqualTo(200);
        }
    }
}
