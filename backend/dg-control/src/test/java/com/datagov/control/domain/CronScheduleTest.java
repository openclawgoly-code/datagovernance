package com.datagov.control.domain;

import com.datagov.common.error.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cron 调度策略(功能 16)")
class CronScheduleTest {

    @Test
    @DisplayName("crontab 风格的 5 段会被自动补成 6 段")
    void fiveFieldCrontabIsAccepted() {
        CronSchedule schedule = CronSchedule.parse("0 2 * * *", "Asia/Shanghai", null);
        assertThat(schedule.expression()).isEqualTo("0 0 2 * * *");
    }

    @Test
    @DisplayName("6 段表达式原样保留")
    void sixFieldExpressionIsKept() {
        assertThat(CronSchedule.parse("0 0 2 * * *", null, null).expression())
                .isEqualTo("0 0 2 * * *");
    }

    @Test
    @DisplayName("多余空白被规整,不影响解析")
    void extraWhitespaceIsNormalized() {
        assertThat(CronSchedule.parse("  0   2  *  * *  ", null, null).expression())
                .isEqualTo("0 0 2 * * *");
    }

    @Test
    @DisplayName("非法表达式报错,并说明本平台用 6 段格式")
    void invalidExpressionExplainsTheSixFieldFormat() {
        assertThatThrownBy(() -> CronSchedule.parse("not a cron", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Cron 表达式无效");
    }

    @Test
    @DisplayName("默认时区是 Asia/Shanghai,可显式覆盖")
    void timezoneDefaultsAndOverrides() {
        assertThat(CronSchedule.parse("0 2 * * *", null, null).timezone())
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(CronSchedule.parse("0 2 * * *", "UTC", null).timezone())
                .isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("时区名非法时报错,而不是悄悄回落到默认时区")
    void invalidTimezoneIsRejected() {
        assertThatThrownBy(() -> CronSchedule.parse("0 2 * * *", "Asia/NotAPlace", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("时区无效");
    }

    @Test
    @DisplayName("同一个表达式在不同时区算出不同的触发时刻")
    void timezoneActuallyAffectsFireTime() {
        Instant reference = Instant.parse("2026-03-01T00:00:00Z");
        Instant shanghai = CronSchedule.parse("0 2 * * *", "Asia/Shanghai", null)
                .nextFireAfter(reference);
        Instant utc = CronSchedule.parse("0 2 * * *", "UTC", null).nextFireAfter(reference);
        assertThat(shanghai).isNotEqualTo(utc);
    }

    @Test
    @DisplayName("过密的调度被拒 —— 每秒一次对目标库是持续压力")
    void tooFrequentScheduleIsRejected() {
        assertThatThrownBy(() -> CronSchedule.parse("* * * * * *", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("调度间隔不得小于");
    }

    @Test
    @DisplayName("每分钟一次正好在下限上,允许")
    void oneMinuteIntervalIsAllowed() {
        assertThatCode(() -> CronSchedule.parse("0 * * * * *", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("预览返回接下来 n 次触发时刻,且严格递增")
    void previewReturnsIncreasingTimes() {
        List<Instant> times = CronSchedule.parse("0 2 * * *", "UTC", null)
                .nextFireTimes(Instant.parse("2026-03-01T00:00:00Z"), 5);

        assertThat(times).hasSize(5);
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfter(times.get(i - 1));
            assertThat(Duration.between(times.get(i - 1), times.get(i)))
                    .isEqualTo(Duration.ofDays(1));
        }
    }

    @Test
    @DisplayName("并发策略默认 SKIP,非法值报错")
    void misfirePolicyDefaultsToSkip() {
        assertThat(CronSchedule.parse("0 2 * * *", null, null).misfirePolicy())
                .isEqualTo(CronSchedule.MisfirePolicy.SKIP);
        assertThat(CronSchedule.parse("0 2 * * *", null, "CONCURRENT").misfirePolicy())
                .isEqualTo(CronSchedule.MisfirePolicy.CONCURRENT);

        assertThatThrownBy(() -> CronSchedule.parse("0 2 * * *", null, "WHATEVER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("并发策略无效");
    }
}
