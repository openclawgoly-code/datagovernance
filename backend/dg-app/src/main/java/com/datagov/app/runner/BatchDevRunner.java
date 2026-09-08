package com.datagov.app.runner;

import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线(批)开发任务(序号 20)。
 *
 * <p>SQL 形态在本地就能真的跑起来:一段用户写的 SQL,在指定数据源上顺序执行。
 * 这不是占位实现 —— "在数仓里跑一段加工 SQL"正是离线开发最主要的用法,而它
 * 不需要 Flink。
 *
 * <p>JAR / PYTHON 形态需要 Flink 或 K8s,当前部署没有接入。那种情况下它
 * <b>明确失败并说清楚缺什么</b>,而不是返回一个"成功、处理 0 行"的假结果:
 * 假成功会让编译、下发、执行记录、状态机整条链路都"通过",而实际什么都没发生。
 *
 * <p>SQL 用分号切分后逐条执行,<b>在一个事务里</b>。中间失败整体回滚 ——
 * 一段加工 SQL 跑到第三句失败,前两句的写入留在库里,比整体失败更难收拾。
 */
@Component
public class BatchDevRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchDevRunner.class);

    /** 单次批作业里的语句数上限。超过它多半是误把一个迁移脚本贴了进来 */
    private static final int MAX_STATEMENTS = 100;

    private final DataSourceMapper dataSourceMapper;
    private final TableCopier tableCopier;

    public BatchDevRunner(DataSourceMapper dataSourceMapper, TableCopier tableCopier) {
        this.dataSourceMapper = dataSourceMapper;
        this.tableCopier = tableCopier;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.BATCH_DEV;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> plan = context.plan();
        String sourceKind = String.valueOf(plan.getOrDefault("sourceKind", "SQL"));

        if (!"SQL".equals(sourceKind)) {
            throw new UnsupportedOperationException(
                    "%s 形态的离线开发作业需要 Flink / K8s 引擎,当前部署未接入"
                            .formatted(sourceKind));
        }

        String dataSourceId = context.requirePlanString("dataSourceId");
        String sql = context.requirePlanString("sql");
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("作业 SQL 里没有可执行的语句");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException(
                    "作业含 %d 条语句,超过上限 %d".formatted(statements.size(), MAX_STATEMENTS));
        }

        DataSourceEntity entity = dataSourceMapper.selectById(dataSourceId);
        if (entity == null) {
            throw new IllegalArgumentException("数据源不存在: " + dataSourceId);
        }

        long affected = 0;
        try (Connection connection = tableCopier.open(entity)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (int i = 0; i < statements.size(); i++) {
                    // 每条语句之间查一次取消旗:一段跑十分钟的加工 SQL,用户点了
                    // 取消却要等它全部跑完,那个取消按钮等于没有
                    context.throwIfCanceled();
                    String single = statements.get(i);
                    log.debug("批作业执行第 {}/{} 条语句 execution={}",
                            i + 1, statements.size(), context.executionId());
                    boolean hasResultSet = statement.execute(single);
                    if (!hasResultSet) {
                        int count = statement.getUpdateCount();
                        if (count > 0) {
                            affected += count;
                        }
                    }
                    context.progress().accept(
                            new com.datagov.runtime.spi.ExecutionEngine.EngineMetric(0, affected, 0));
                }
            }
            connection.commit();
        }

        log.info("批作业完成 execution={} 语句={} 条 影响={} 行",
                context.executionId(), statements.size(), affected);
        // 读取行数记 0:批作业的"读"发生在数据库内部,平台看不到也不该编造
        return RunResult.of(0, affected, 0);
    }

    @Override
    public String classify(Exception e) {
        if (e instanceof UnsupportedOperationException) {
            // 环境缺件,不是平台 bug —— 值班的人该找运维而不是开发
            return "RTM_EXECUTOR_UNAVAILABLE";
        }
        return e instanceof SQLException ? "DTA_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    /**
     * 按分号切分语句。
     *
     * <p>会正确跳过字符串字面量与注释里的分号 —— 一个朴素的 {@code split(";")}
     * 会把 {@code WHERE name = 'a;b'} 切成两半,而那种数据真实存在。
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (!inSingle && !inDouble) {
                if (c == '-' && next == '-') {
                    inLineComment = true;
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
                if (c == ';') {
                    addIfNotBlank(statements, current);
                    current.setLength(0);
                    continue;
                }
            }
            if (c == '\'' && !inDouble) {
                // 连续两个单引号是转义的引号,不是字符串结束
                if (inSingle && next == '\'') {
                    current.append(c).append(next);
                    i++;
                    continue;
                }
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            current.append(c);
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder buffer) {
        String value = buffer.toString().trim();
        if (!value.isEmpty()) {
            statements.add(value);
        }
    }
}
