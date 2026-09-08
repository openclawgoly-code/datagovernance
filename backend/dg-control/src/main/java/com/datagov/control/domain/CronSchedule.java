package com.datagov.control.domain;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron 调度策略(功能 16「支持 Cron 表达式的周期调度策略配置」)。
 *
 * <p>用 Spring 的 {@link CronExpression} 而不是自己解析,也不是引入 Quartz:
 * 前者会在闰秒、夏令时、月末这些地方错得很隐蔽;后者带来一整套调度框架,
 * 而我们需要的只是"下一次什么时候"这一个能力 —— 触发、锁、状态都已经有了。
 *
 * <p><b>Spring 的 Cron 是 6 段(含秒),不是 Linux 的 5 段。</b> 用户多半是照着
 * crontab 的习惯写的,所以 {@link #parse} 会把 5 段自动补成 6 段,并在
 * 校验时明确说明这件事 —— 直接报"表达式非法"会让人对着一个在 crontab 里
 * 完全正确的字符串发呆。
 */
public record CronSchedule(String expression, ZoneId timezone, MisfirePolicy misfirePolicy) {

    /** 最小触发间隔。比这更密的调度对目标库是持续压力,而且几乎一定是配错了。 */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /**
     * 上一次还没结束时,到点了怎么办。
     *
     * <p>这个选项存在的理由:一个每 5 分钟跑一次、单次要跑 20 分钟的同步任务。
     * 没有这个策略,它会在一小时内堆出十几个实例一起冲击目标库。
     */
    public enum MisfirePolicy {
        /** 跳过本次。默认 —— 数据同步类任务跳一次通常无害,堆积一定有害 */
        SKIP("跳过本次"),
        /** 排队,等上一次结束后立刻补跑一次(只补一次,不累积) */
        QUEUE("排队补跑"),
        /** 允许并发。只在任务本身幂等且轻量时才该选 */
        CONCURRENT("允许并发");

        private final String displayName;

        MisfirePolicy(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static CronSchedule parse(String rawExpression, String timezoneId,
                                     String misfirePolicyName) {
        String normalized = normalize(rawExpression);
        if (!CronExpression.isValidExpression(normalized)) {
            throw new BizException(ErrorCode.CTL_INVALID_CRON,
                    "Cron 表达式无效: " + rawExpression,
                    "本平台使用 6 段格式(秒 分 时 日 月 周)。crontab 风格的 5 段"
                            + "会被自动补上秒位,例如 \"0 2 * * *\" 等价于 \"0 0 2 * * *\"");
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(timezoneId == null || timezoneId.isBlank()
                    ? DEFAULT_TIMEZONE : timezoneId);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CTL_INVALID_CRON,
                    "时区无效: " + timezoneId, "请使用 IANA 时区名,如 Asia/Shanghai", e);
        }

        MisfirePolicy policy;
        try {
            policy = misfirePolicyName == null || misfirePolicyName.isBlank()
                    ? MisfirePolicy.SKIP
                    : MisfirePolicy.valueOf(misfirePolicyName);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.CTL_INVALID_CRON,
                    "并发策略无效: " + misfirePolicyName,
                    "可选值: SKIP / QUEUE / CONCURRENT", e);
        }

        CronSchedule schedule = new CronSchedule(normalized, zone, policy);
        schedule.requireReasonableInterval();
        return schedule;
    }

    /**
     * 5 段补成 6 段。
     *
     * <p>用户十有八九是从 crontab 或某个在线生成器抄过来的,那些都是 5 段。
     * 不做这件事,最常见的一次配置会得到一条看不懂的报错。
     */
    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.CTL_INVALID_CRON, "Cron 表达式不能为空");
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.split(" ").length == 5 ? "0 " + trimmed : trimmed;
    }

    /**
     * 拒绝过密的调度。
     *
     * <p>算法是往后取三次触发点看间隔,而不是解析表达式本身 —— 表达式的写法
     * 千奇百怪(`*&#47;30 * * * * *` 和 `0,30 * * * * *` 是同一件事),
     * 只有实际触发点不会骗人。
     */
    private void requireReasonableInterval() {
        List<Instant> upcoming = nextFireTimes(Instant.now(), 3);
        for (int i = 1; i < upcoming.size(); i++) {
            Duration gap = Duration.between(upcoming.get(i - 1), upcoming.get(i));
            if (gap.compareTo(MIN_INTERVAL) < 0) {
                throw new BizException(ErrorCode.CTL_INVALID_CRON,
                        "调度间隔不得小于 %d 秒,当前约 %d 秒"
                                .formatted(MIN_INTERVAL.toSeconds(), gap.toSeconds()),
                        "过于频繁的调度对目标库是持续压力,且多半是表达式写错了");
            }
        }
    }

    /** 下一次触发时刻;表达式永远不会再触发(如指定了已过去的年份)时返回 null */
    public Instant nextFireAfter(Instant after) {
        ZonedDateTime next = CronExpression.parse(expression)
                .next(ZonedDateTime.ofInstant(after, timezone));
        return next == null ? null : next.toInstant();
    }

    /** 接下来的 n 次触发时刻。UI 上的「最近几次执行时间」预览用它。 */
    public List<Instant> nextFireTimes(Instant after, int count) {
        List<Instant> result = new ArrayList<>(count);
        CronExpression cron = CronExpression.parse(expression);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(after, timezone);
        for (int i = 0; i < count; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) {
                break;
            }
            result.add(cursor.toInstant());
        }
        return result;
    }
}
