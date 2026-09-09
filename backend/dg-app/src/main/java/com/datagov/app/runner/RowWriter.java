package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.rule.RuleInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 把一批「字段名 → 值」的行写进目标表。
 *
 * <p>{@link TableCopier} 处理的是「表到表」,源那一端是 ResultSet;文件解析
 * (功能 12)与接口解析(功能 13)的源是 CSV 行或 JSON 对象,没有 ResultSet。
 * 两者共用的是<b>写入</b>那一半 —— 批次、提交、规则、指标 —— 所以把它单独提出来。
 *
 * <p>用起来是一个流式的 session:开一次,喂很多行,关一次。这个形状是必需的 ——
 * 文件可能有几百万行,不能先在内存里攒成 List 再写。
 */
@Component
public class RowWriter {

    private static final Logger log = LoggerFactory.getLogger(RowWriter.class);

    private final ConnectionConfigAssembler assembler;

    public RowWriter(ConnectionConfigAssembler assembler) {
        this.assembler = assembler;
    }

    /**
     * 打开一次写入会话。
     *
     * @param sourceFields  源字段名,顺序与 {@code targetColumns} 一一对应
     * @param fieldRules    源字段 → 规则链(功能 17)
     */
    public Session open(DataSourceEntity targetDs, String database, String schema, String table,
                        List<String> sourceFields, List<String> targetColumns,
                        Map<String, List<RuleInterpreter.Rule>> fieldRules,
                        String writeMode, int batchSize,
                        JobRunner.RunContext context) throws SQLException {
        Connection connection = open(targetDs);
        try {
            connection.setAutoCommit(false);
            String qualified = TableCopier.qualified(targetDs, database, schema, table);

            if ("OVERWRITE".equals(writeMode)) {
                // DELETE 而不是 TRUNCATE:后者在多数库上是 DDL 会隐式提交,
                // 让后续失败时的回滚失去意义
                try (Statement statement = connection.createStatement()) {
                    int deleted = statement.executeUpdate("DELETE FROM " + qualified);
                    connection.commit();
                    log.info("OVERWRITE 模式已清空目标表 {},删除 {} 行", qualified, deleted);
                }
            }

            String columnList = targetColumns.stream().map(c -> TableCopier.quote(targetDs, c))
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            String placeholders = String.join(", ",
                    Collections.nCopies(targetColumns.size(), "?"));
            String insertSql = "INSERT INTO %s (%s) VALUES (%s)"
                    .formatted(qualified, columnList, placeholders);

            return new Session(connection, connection.prepareStatement(insertSql),
                    sourceFields, fieldRules, batchSize,
                    TableCopier.idempotentWriteMode(writeMode) ? null : context);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    /**
     * 一次写入会话。
     *
     * <p>实现 AutoCloseable,但 {@link #close()} <b>不提交</b>剩余批次 ——
     * 那要显式调 {@link #finish()}。理由是 try-with-resources 在异常路径上
     * 也会调 close,而异常路径恰恰是最不该提交的时候。
     */
    public static final class Session implements AutoCloseable {

        private final Connection connection;
        private final PreparedStatement statement;
        private final List<String> sourceFields;
        private final List<RuleInterpreter.Rule>[] rulesByColumn;
        private final boolean anyRules;
        private final int batchSize;
        /** 非幂等写入模式下才有值 —— 每次 commit 之后要声明"重投会写重" */
        private final JobRunner.RunContext unsafeToRetryContext;

        private long rowsWritten;
        private int pendingInBatch;

        @SuppressWarnings("unchecked")
        private Session(Connection connection, PreparedStatement statement,
                        List<String> sourceFields,
                        Map<String, List<RuleInterpreter.Rule>> fieldRules, int batchSize,
                        JobRunner.RunContext unsafeToRetryContext) {
            this.connection = connection;
            this.statement = statement;
            this.sourceFields = sourceFields;
            this.batchSize = Math.max(1, batchSize);
            this.unsafeToRetryContext = unsafeToRetryContext;

            // 规则链按列序展开:每行每列查一次 Map 是可观的开销,
            // 而这个循环会跑几百万次
            this.rulesByColumn = new List[sourceFields.size()];
            boolean any = false;
            for (int i = 0; i < sourceFields.size(); i++) {
                rulesByColumn[i] = fieldRules == null ? null : fieldRules.get(sourceFields.get(i));
                any |= rulesByColumn[i] != null && !rulesByColumn[i].isEmpty();
            }
            this.anyRules = any;
        }

        /** 写一行。row 用源字段名取值,缺失的字段写 null。 */
        public void write(Map<String, Object> row) throws SQLException {
            for (int i = 0; i < sourceFields.size(); i++) {
                Object cell = row.get(sourceFields.get(i));
                if (anyRules) {
                    List<RuleInterpreter.Rule> rules = rulesByColumn[i];
                    if (rules != null && !rules.isEmpty()) {
                        cell = RuleInterpreter.applyAll(cell, rules);
                    }
                }
                statement.setObject(i + 1, cell);
            }
            statement.addBatch();
            if (++pendingInBatch >= batchSize) {
                flush();
            }
        }

        /** 提交剩余批次并返回写入行数。<b>成功路径上必须调它</b>。 */
        public long finish() throws SQLException {
            if (pendingInBatch > 0) {
                flush();
            }
            return rowsWritten;
        }

        public long rowsWritten() {
            return rowsWritten;
        }

        private void flush() throws SQLException {
            int[] results = statement.executeBatch();
            connection.commit();
            // 与 TableCopier.flush 同一条规矩:提交成功的那一刻就声明出去,
            // 因为从这一刻起重投会把这一批再写一遍。标在提交点,不标在异常处。
            if (unsafeToRetryContext != null) {
                unsafeToRetryContext.markUnsafeToRetry();
            }
            statement.clearBatch();
            for (int result : results) {
                // SUCCESS_NO_INFO(-2)表示成功但驱动不报行数 —— 按 1 计
                rowsWritten += result >= 0 ? result
                        : (result == Statement.SUCCESS_NO_INFO ? 1 : 0);
            }
            pendingInBatch = 0;
        }

        @Override
        public void close() {
            // 不提交:异常路径也会走到这里,而那正是最不该提交的时候。
            // 未提交的批次由 rollback 丢弃 —— 已提交的留在目标端,
            // 这是 APPEND 语义的固有代价,不假装能做到全或无。
            try {
                connection.rollback();
            } catch (SQLException e) {
                log.debug("关闭写入会话时回滚失败(多半是已经提交完了)", e);
            }
            try {
                statement.close();
            } catch (SQLException ignored) {
                // 关闭失败没有可做的补救
            }
            closeQuietly(connection);
        }
    }

    private Connection open(DataSourceEntity entity) throws SQLException {
        ConnectionConfig config = assembler.assemble(entity, entity.getWorkspaceId());
        Properties props = new Properties();
        if (config.username() != null) {
            props.setProperty("user", config.username());
        }
        if (config.password() != null) {
            props.setProperty("password", config.password());
        }
        config.properties().forEach(props::setProperty);

        String url = config.jdbcUrlOverride() != null && !config.jdbcUrlOverride().isBlank()
                ? config.jdbcUrlOverride()
                : jdbcUrl(entity, config);
        return DriverManager.getConnection(url, props);
    }

    /** 与 TableCopier 的同名方法一致;两者会在接入 Flink 时一起消失。 */
    private String jdbcUrl(DataSourceEntity entity, ConnectionConfig config) {
        int port = config.port() != null ? config.port() : entity.getType().defaultPort();
        String database = config.database() == null ? "" : config.database();
        return switch (entity.getType()) {
            case MYSQL, DORIS, STARROCKS ->
                    "jdbc:mysql://%s:%d/%s".formatted(config.host(), port, database);
            case POSTGRESQL ->
                    "jdbc:postgresql://%s:%d/%s".formatted(config.host(), port,
                            database.isBlank() ? "postgres" : database);
            case ORACLE ->
                    "jdbc:oracle:thin:@//%s:%d/%s".formatted(config.host(), port,
                            database.startsWith("/") ? database.substring(1) : database);
            case SQLSERVER ->
                    "jdbc:sqlserver://%s:%d;databaseName=%s".formatted(config.host(), port, database);
            case DAMENG -> "jdbc:dm://%s:%d".formatted(config.host(), port);
            default -> throw new IllegalArgumentException(
                    "写入不支持数据源类型 " + entity.getType());
        };
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败没有可做的补救
        }
    }
}
