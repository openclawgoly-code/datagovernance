package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 把一张表的数据从 A 搬到 B。
 *
 * <p><b>离线同步与整库迁移共用它。</b> 两者在"把行搬过去"这件事上完全一样,
 * 区别只在于同步是一张既有的表、迁移要先建表且一次处理多张。各写一遍的话,
 * 两份读写循环会各自演化 —— 而最容易漂移的恰恰是取消检查与批量提交这些
 * 出了错才发现的细节。
 *
 * <p>P2 用 JDBC 直连。Contract 里写的是 Flink,那是 P3 的事;先把<b>语义</b>
 * (字段映射、写入模式、批次、取消、指标)跑对,换引擎时改的是这一个类。
 */
@Component
public class TableCopier {

    private static final Logger log = LoggerFactory.getLogger(TableCopier.class);

    /** 每读多少行回报一次进度。太密会把执行事实表写爆,太疏则界面上像卡住了。 */
    private static final int PROGRESS_INTERVAL = 5_000;

    private final ConnectionConfigAssembler assembler;

    public TableCopier(ConnectionConfigAssembler assembler) {
        this.assembler = assembler;
    }

    /**
     * 一次复制的规格。
     *
     * @param fieldMappings 源字段 → 目标字段。<b>null 表示同名全字段复制</b>
     *                      (整库迁移的默认行为:目标表是刚按源表结构建的)
     * @param whereClause   增量过滤条件,可选
     */
    public record CopySpec(
            DataSourceEntity sourceDs, String sourceDatabase, String sourceSchema, String sourceTable,
            DataSourceEntity targetDs, String targetDatabase, String targetSchema, String targetTable,
            java.util.Map<String, String> fieldMappings,
            String whereClause,
            String writeMode,
            int batchSize
    ) {

        /** 整库迁移用:同名全字段,无过滤。 */
        public CopySpec(DataSourceEntity sourceDs, String sourceDatabase, String sourceSchema,
                        String sourceTable, DataSourceEntity targetDs, String targetDatabase,
                        String targetSchema, String targetTable, java.util.Map<String, String> mappings,
                        String writeMode, int batchSize) {
            this(sourceDs, sourceDatabase, sourceSchema, sourceTable,
                    targetDs, targetDatabase, targetSchema, targetTable,
                    mappings, null, writeMode, batchSize);
        }
    }

    public record CopyResult(long rowsRead, long rowsWritten) {
    }

    /**
     * 执行一次复制。
     *
     * <p>三件事必须在循环里做对:
     * <ul>
     *   <li><b>流式读取</b> —— setFetchSize 加上 autoCommit=false(PostgreSQL 需要);
     *       否则驱动会把整张表拉进内存,一千万行直接 OOM</li>
     *   <li><b>定期查取消旗</b> —— 线程中断对阻塞在 JDBC 上的调用基本无效,
     *       主动退出是唯一可靠的路径</li>
     *   <li><b>批量提交</b> —— 每行一次 commit 会让复制慢一个数量级</li>
     * </ul>
     */
    public CopyResult copy(CopySpec spec, JobRunner.RunContext context) throws Exception {
        List<String> sourceColumns;
        List<String> targetColumns;

        if (spec.fieldMappings() == null || spec.fieldMappings().isEmpty()) {
            // 同名全字段:列名从源表实际查出来,不猜
            sourceColumns = readColumnNames(spec);
            targetColumns = sourceColumns;
        } else {
            sourceColumns = List.copyOf(spec.fieldMappings().keySet());
            targetColumns = sourceColumns.stream().map(spec.fieldMappings()::get).toList();
        }
        if (sourceColumns.isEmpty()) {
            throw new IllegalStateException("源表 %s 没有可复制的字段".formatted(spec.sourceTable()));
        }

        String selectSql = buildSelect(spec, sourceColumns);
        String insertSql = buildInsert(spec, targetColumns);

        try (Connection readConn = open(spec.sourceDs());
             Connection writeConn = open(spec.targetDs())) {

            // 读连接设只读:复制任务不该有任何写源库的可能,即使计划被篡改。
            // 与 ReadOnlySqlGuard 同一个思路 —— 纵深防御,不指望单点。
            readConn.setReadOnly(true);
            writeConn.setAutoCommit(false);

            if ("OVERWRITE".equals(spec.writeMode())) {
                truncateTarget(writeConn, spec);
            }
            return copyRows(spec, context, readConn, writeConn,
                    selectSql, insertSql, sourceColumns.size());
        }
    }

    private CopyResult copyRows(CopySpec spec, JobRunner.RunContext context,
                                Connection readConn, Connection writeConn,
                                String selectSql, String insertSql, int columnCount)
            throws SQLException, InterruptedException {

        long rowsRead = 0;
        long rowsWritten = 0;
        long pendingInBatch = 0;

        try (PreparedStatement read = readConn.prepareStatement(selectSql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement write = writeConn.prepareStatement(insertSql)) {

            read.setFetchSize(spec.batchSize());

            try (ResultSet rs = read.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                if (meta.getColumnCount() != columnCount) {
                    throw new IllegalStateException("源表返回 %d 列,与预期的 %d 列不符"
                            .formatted(meta.getColumnCount(), columnCount));
                }

                while (rs.next()) {
                    // 每行都查一次取消旗。检查本身极廉价(读一个 volatile),
                    // 换来的是"点了取消真的会停"。
                    context.throwIfCanceled();

                    for (int i = 1; i <= columnCount; i++) {
                        write.setObject(i, rs.getObject(i));
                    }
                    write.addBatch();
                    rowsRead++;
                    pendingInBatch++;

                    if (pendingInBatch >= spec.batchSize()) {
                        rowsWritten += flush(write, writeConn);
                        pendingInBatch = 0;
                    }
                    if (rowsRead % PROGRESS_INTERVAL == 0) {
                        context.progress().accept(
                                new ExecutionEngine.EngineMetric(rowsRead, rowsWritten, 0));
                    }
                }
            }
            if (pendingInBatch > 0) {
                rowsWritten += flush(write, writeConn);
            }
        } catch (SQLException | RuntimeException e) {
            // 回滚未提交的那一批。已提交的批次留在目标端 —— 这是 APPEND 语义的
            // 固有代价,不假装能做到全或无。要原子性就用 OVERWRITE。
            rollbackQuietly(writeConn);
            throw e;
        }
        return new CopyResult(rowsRead, rowsWritten);
    }

    private long flush(PreparedStatement write, Connection writeConn) throws SQLException {
        int[] results = write.executeBatch();
        writeConn.commit();
        write.clearBatch();
        long written = 0;
        for (int result : results) {
            // SUCCESS_NO_INFO(-2)表示成功但驱动不报行数 —— 按 1 计,
            // 当成 0 会让"写了多少行"在某些驱动上恒为 0
            written += result >= 0 ? result : (result == Statement.SUCCESS_NO_INFO ? 1 : 0);
        }
        return written;
    }

    private void truncateTarget(Connection writeConn, CopySpec spec) throws SQLException {
        String table = qualified(spec.targetDs(), spec.targetDatabase(),
                spec.targetSchema(), spec.targetTable());
        // DELETE 而不是 TRUNCATE:TRUNCATE 在多数库上是 DDL,会隐式提交,
        // 让后续失败时的回滚失去意义。慢一些,但语义是对的。
        try (Statement statement = writeConn.createStatement()) {
            int deleted = statement.executeUpdate("DELETE FROM " + table);
            writeConn.commit();
            log.info("OVERWRITE 模式已清空目标表 {},删除 {} 行", table, deleted);
        }
    }

    /** 从源表读列名。整库迁移的同名全字段复制需要它。 */
    private List<String> readColumnNames(CopySpec spec) throws SQLException {
        String table = qualified(spec.sourceDs(), spec.sourceDatabase(),
                spec.sourceSchema(), spec.sourceTable());
        try (Connection connection = open(spec.sourceDs());
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            // LIMIT 0 只取元数据不取行 —— 对大表这是唯一可接受的取列名方式。
            // 用 DatabaseMetaData.getColumns 也行,但那要处理各方言的大小写差异。
            statement.setMaxRows(1);
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>(meta.getColumnCount());
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                return columns;
            }
        }
    }

    // ── SQL 构造 ────────────────────────────────────────────────────────

    private String buildSelect(CopySpec spec, List<String> columns) {
        String table = qualified(spec.sourceDs(), spec.sourceDatabase(),
                spec.sourceSchema(), spec.sourceTable());
        String columnList = columns.stream().map(c -> quote(spec.sourceDs(), c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();

        StringBuilder sql = new StringBuilder("SELECT ").append(columnList)
                .append(" FROM ").append(table);
        if (spec.whereClause() != null && !spec.whereClause().isBlank()) {
            // where 由用户填写,已过编译期的结构校验但不是参数化的。源连接是
            // 只读的(见 copy),最坏情况是读到不该读的数据,不会写坏源库 ——
            // 但这仍是一个已知的注入面,收敛它需要一个表达式 DSL(P3)。
            sql.append(" WHERE ").append(spec.whereClause());
        }
        return sql.toString();
    }

    private String buildInsert(CopySpec spec, List<String> columns) {
        String table = qualified(spec.targetDs(), spec.targetDatabase(),
                spec.targetSchema(), spec.targetTable());
        String columnList = columns.stream().map(c -> quote(spec.targetDs(), c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(table, columnList, placeholders);
    }

    /**
     * 限定表名。
     *
     * <p>各方言的库/模式层级不同:MySQL/Doris 只有库,PostgreSQL 库+模式,
     * 达梦以模式为主。按<b>实际拿到的段</b>拼,而不是按类型分支 —— 后者每加
     * 一种数据库就要回来改一次。
     */
    static String qualified(DataSourceEntity ds, String database, String schema, String table) {
        List<String> parts = new ArrayList<>(2);
        if (schema != null && !schema.isBlank()) {
            parts.add(quote(ds, schema));
        } else if (database != null && !database.isBlank()) {
            parts.add(quote(ds, database));
        }
        parts.add(quote(ds, table));
        return String.join(".", parts);
    }

    /** 标识符引用符按方言取。用错会让带保留字的表名(如 order)整批失败。 */
    static String quote(DataSourceEntity ds, String identifier) {
        DataSourceType type = ds.getType();
        if (type == DataSourceType.MYSQL || type == DataSourceType.DORIS
                || type == DataSourceType.STARROCKS) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        if (type == DataSourceType.SQLSERVER) {
            return "[" + identifier.replace("]", "]]") + "]";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // ── 连接 ────────────────────────────────────────────────────────────

    Connection open(DataSourceEntity entity) throws SQLException {
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

    /**
     * 拼 JDBC URL。
     *
     * <p>与 {@code AbstractJdbcConnector.buildJdbcUrl} 逻辑重复,这是有意的折中:
     * 复用它需要 dg-app 依赖 dg-data-connectors 的内部实现(那些方法是 protected 的,
     * 为连接器子类而设),而把它们提成公共 API 会让连接器的内部结构变成对外契约。
     * 等 P3 接入 Flink 时这段会整体消失。
     */
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
                    "数据复制不支持数据源类型 " + entity.getType());
        };
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败", e);
        }
    }
}
