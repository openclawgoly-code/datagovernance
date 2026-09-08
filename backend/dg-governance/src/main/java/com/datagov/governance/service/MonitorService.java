package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.entity.RuntimeEntities.Execution;
import com.datagov.runtime.mapper.ExecutionMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务监控(序号 24)。
 *
 * <p><b>这是架构约束 R4 的第一条证据。</b> 需求原文要求这五个口径「跨数据集成
 * 与数据开发聚合」——也就是说,离线同步、整库迁移、文件解析、实时开发、
 * 离线开发、工作流的执行必须能被<b>一次</b>算清楚。
 *
 * <p>因为执行事实只有 {@code rt_execution} 一张表,这个类才能只写一个查询。
 * 若当初按菜单把执行记录拆成五张表,这里的每一个口径都会是一个五表 UNION,
 * 而那个 UNION 会在第六种任务类型出现时被人忘记更新 —— 于是"失败数"从此
 * 少算一类任务,而没有人会发现。
 *
 * <p>它<b>只读</b>。Governance 的 must_not_do 第一条是「不得成为业务流程的
 * 执行者」——监控看到失败率飙升时,它发一条告警,不去暂停任何任务。
 */
@Service
public class MonitorService {

    private final ExecutionMapper executionMapper;

    public MonitorService(ExecutionMapper executionMapper) {
        this.executionMapper = executionMapper;
    }

    /**
     * 监控面板(序号 24 的五个口径)。
     *
     * @param totalExecutions 任务实例执行总数
     * @param failedExecutions 失败数
     * @param todayRowsExtracted 今日新增抽取(行)
     * @param totalRowsExtracted 总计抽取(行)
     * @param avgLatencyMs 任务时延 —— 平均耗时
     */
    public record MonitorDashboard(
            long totalExecutions,
            long failedExecutions,
            long todayRowsExtracted,
            long totalRowsExtracted,
            long avgLatencyMs,

            /** 失败率(百分比,一位小数)。总数为 0 时是 0 而不是 NaN */
            double failureRatePercent,
            /** 当前正在跑的。它不是需求里的口径,但值班的人第一眼看的就是它 */
            long runningExecutions,

            /** 按作业种类拆分 —— 让"哪一类在拖后腿"一眼可见 */
            List<TypeBreakdown> byJobType,
            /** 最近的失败,供直接点进去看 */
            List<RecentFailure> recentFailures,

            /** 统计窗口的起点;null 表示全量 */
            Instant since,
            Instant generatedAt
    ) {
    }

    public record TypeBreakdown(
            JobRefType jobRefType,
            String displayName,
            long total,
            long failed,
            long rowsWritten
    ) {
    }

    public record RecentFailure(
            String executionId,
            String jobName,
            JobRefType jobRefType,
            String status,
            String message,
            String errorCode,
            Instant finishedAt
    ) {
    }

    /**
     * 算出面板。
     *
     * @param days 统计最近几天;null 或 <= 0 表示全量
     */
    public MonitorDashboard dashboard(Integer days) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Instant now = Instant.now();
        Instant since = days != null && days > 0
                ? now.minusSeconds(days * 86400L)
                : null;

        // 一次拉齐再在内存里分组。这个选择有前提:单空间的执行记录在
        // P1-P4 的规模下是万级,一次查询几十毫秒。到十万级就该换成
        // 几个 GROUP BY —— 那时这个方法体会变,但调用方与口径不必变。
        List<Execution> executions = executionMapper.selectList(
                new LambdaQueryWrapper<Execution>()
                        .eq(Execution::getWorkspaceId, workspaceId)
                        .ge(since != null, Execution::getSubmittedAt, since)
                        // 工作流父执行不计入总数:它自己不跑任何东西,只是
                        // 那几个节点的容器。算进去会让"执行总数"凭空多出一倍
                        .ne(Execution::getJobRefType, JobRefType.WORKFLOW));

        long total = executions.size();
        long failed = 0;
        long running = 0;
        long totalRows = 0;
        long latencySum = 0;
        long latencyCount = 0;
        long todayRows = 0;

        // "今天"按平台所在时区算。跨时区团队里这个口径要说清楚,
        // 否则凌晨的数字对不上
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        Map<JobRefType, long[]> byType = new LinkedHashMap<>();
        List<RecentFailure> failures = new ArrayList<>();

        for (Execution e : executions) {
            long[] bucket = byType.computeIfAbsent(e.getJobRefType(), k -> new long[3]);
            bucket[0]++;

            boolean isFailure = e.getStatus() == ExecutionStatus.FAILED
                    || e.getStatus() == ExecutionStatus.TIMEOUT;
            if (isFailure) {
                failed++;
                bucket[1]++;
                failures.add(new RecentFailure(e.getId(), e.getJobName(), e.getJobRefType(),
                        e.getStatus().displayName(), e.getMessage(), e.getErrorCode(),
                        e.getFinishedAt()));
            }
            if (!e.getStatus().isTerminal()) {
                running++;
            }

            long rows = e.getRowsWritten() == null ? 0 : e.getRowsWritten();
            totalRows += rows;
            bucket[2] += rows;
            // 「今日新增抽取」按<b>结束时间</b>算:一个昨晚开始、今早跑完的
            // 迁移,它搬的数据是今天到位的
            Instant finished = e.getFinishedAt();
            if (finished != null && !finished.isBefore(todayStart)) {
                todayRows += rows;
            }

            // 时延只统计跑完的:还在跑的那些耗时会一直变大,把它们算进平均值
            // 会让这个数字随刷新页面而增长
            if (e.getStatus().isTerminal() && e.getDurationMs() != null) {
                latencySum += e.getDurationMs();
                latencyCount++;
            }
        }

        failures.sort((a, b) -> {
            Instant x = a.finishedAt();
            Instant y = b.finishedAt();
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });

        List<TypeBreakdown> breakdown = byType.entrySet().stream()
                .map(entry -> new TypeBreakdown(entry.getKey(),
                        entry.getKey() == null ? "未知" : entry.getKey().displayName(),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .sorted((a, b) -> Long.compare(b.total(), a.total()))
                .toList();

        double rate = total == 0 ? 0 : Math.round(failed * 1000.0 / total) / 10.0;

        return new MonitorDashboard(total, failed, todayRows, totalRows,
                latencyCount == 0 ? 0 : latencySum / latencyCount,
                rate, running, breakdown,
                failures.size() > 10 ? failures.subList(0, 10) : failures,
                since, now);
    }
}
