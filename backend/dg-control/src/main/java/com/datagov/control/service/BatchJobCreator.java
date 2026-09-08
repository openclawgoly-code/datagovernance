package com.datagov.control.service;

import com.datagov.common.error.BizException;
import com.datagov.control.domain.JobType;
import com.datagov.control.dto.JobDefinitionDtos.JobDefinitionView;
import com.datagov.control.dto.JobDefinitionDtos.UpsertRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量新增同步任务(功能 14「离线同步-批量新增」)。
 *
 * <p>需求的备注说得很清楚:「批量创建的是<b>定义</b>;执行仍逐个独立 Execution」。
 * 所以这里不是一个"批量任务",而是一次批量的定义创建 —— 创建完之后它们是 N 个
 * 完全独立的任务,各自编译、各自调度、各自有执行记录。
 *
 * <p>这个区分很重要:做成"一个任务同步 N 张表"会让 N 张表共享一次执行记录,
 * 于是第 7 张表失败时整条记录是失败的,而前 6 张其实成功了 —— 序号 24 的监控
 * 从此说不清"今天同步成功了多少张表"。
 */
@Service
public class BatchJobCreator {

    private static final Logger log = LoggerFactory.getLogger(BatchJobCreator.class);

    /** 一次批量创建的上限。超过它更像是想要整库迁移(功能 9)。 */
    static final int MAX_BATCH = 200;

    private final JobDefinitionService jobService;

    public BatchJobCreator(JobDefinitionService jobService) {
        this.jobService = jobService;
    }

    /**
     * 批量创建请求。
     *
     * @param tables 源表清单。每张表生成一个独立的同步任务
     * @param namePattern 任务名模板,{@code {table}} 会被替换成表名
     */
    public record BatchRequest(
            String namePattern,
            String description,
            String catalogId,

            String sourceDataSourceId,
            String sourceDatabase,
            String sourceSchema,
            List<String> tables,

            String targetDataSourceId,
            String targetDatabase,
            String targetSchema,
            /** 目标表名规则;留空表示与源表同名 */
            String targetTablePrefix,
            String targetTableSuffix,

            String writeMode,
            Integer batchSize,
            Long timeoutMs,
            Integer retryMaxAttempts,

            String cronExpression,
            String cronTimezone,
            String misfirePolicy
    ) {
    }

    /**
     * 一次批量创建的结果。
     *
     * <p><b>部分成功是正常结果,不是异常。</b> 20 张表里有 3 张重名,该创建的
     * 17 张仍然要创建 —— 整批回滚只会逼用户手工挑出那 3 张再来一遍。
     * 所以失败逐条返回,而不是抛异常。
     */
    public record BatchResult(
            List<JobDefinitionView> created,
            List<Failure> failed
    ) {

        public record Failure(String table, String reason) {
        }

        public int total() {
            return created.size() + failed.size();
        }
    }

    public BatchResult createAll(BatchRequest request) {
        List<String> tables = request.tables() == null ? List.of() : request.tables();
        if (tables.isEmpty()) {
            throw new BizException(com.datagov.common.error.ErrorCode.SYS_VALIDATION_FAILED,
                    "没有选择任何源表");
        }
        if (tables.size() > MAX_BATCH) {
            throw new BizException(com.datagov.common.error.ErrorCode.SYS_VALIDATION_FAILED,
                    "一次最多批量创建 %d 个任务,当前 %d".formatted(MAX_BATCH, tables.size()),
                    "要同步整个库,请改用整库迁移");
        }

        List<JobDefinitionView> created = new ArrayList<>();
        List<BatchResult.Failure> failed = new ArrayList<>();

        for (String table : tables) {
            try {
                created.add(jobService.create(toUpsert(request, table)));
            } catch (BizException e) {
                // 逐条记录失败原因。最常见的是重名 —— 用户多半是第二次批量创建,
                // 而告诉他"哪几张已经有了"比整批失败有用得多
                failed.add(new BatchResult.Failure(table, e.getMessage()));
            }
        }

        log.info("批量创建同步任务:成功 {} 个,失败 {} 个", created.size(), failed.size());
        return new BatchResult(created, failed);
    }

    private UpsertRequest toUpsert(BatchRequest request, String table) {
        String targetTable = orEmpty(request.targetTablePrefix()) + table
                + orEmpty(request.targetTableSuffix());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceDataSourceId", request.sourceDataSourceId());
        config.put("sourceDatabase", request.sourceDatabase());
        config.put("sourceSchema", request.sourceSchema());
        config.put("sourceTable", table);
        config.put("targetDataSourceId", request.targetDataSourceId());
        config.put("targetDatabase", request.targetDatabase());
        config.put("targetSchema", request.targetSchema());
        config.put("targetTable", targetTable);
        config.put("writeMode", request.writeMode() == null ? "APPEND" : request.writeMode());
        config.put("batchSize", request.batchSize() == null ? 1000 : request.batchSize());
        // 字段映射留空:批量创建时平台不知道每张表有哪些字段,而猜一个映射
        // 比留空更糟 —— 用户会以为它是对的。编译时会明确报"没有配置任何字段映射"。
        config.put("fieldMappings", Map.of());

        return new UpsertRequest(
                renderName(request.namePattern(), table),
                JobType.OFFLINE_SYNC,
                request.description(),
                request.catalogId(),
                config,
                request.cronExpression(),
                request.cronTimezone(),
                request.misfirePolicy(),
                request.timeoutMs(),
                request.retryMaxAttempts(),
                null);
    }

    /** 任务名模板。默认「同步-表名」,{table} 占位。 */
    static String renderName(String pattern, String table) {
        String effective = pattern == null || pattern.isBlank() ? "同步-{table}" : pattern;
        return effective.contains("{table}")
                ? effective.replace("{table}", table)
                // 模板里没有占位符时仍要保证名称唯一 —— 否则第二张表就撞名,
                // 而用户看到的是一串莫名其妙的"名称已存在"
                : effective + "-" + table;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
