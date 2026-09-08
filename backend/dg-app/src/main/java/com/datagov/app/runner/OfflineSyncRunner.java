package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.runtime.domain.JobRefType;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 离线同步的执行逻辑(功能 11 的执行侧、功能 15 的执行记录来源)。
 *
 * <p><b>为什么在 dg-app 而不是 dg-runtime:</b> 它要同时用到 Metadata 的数据源定义
 * (拿连接配置)和 Data 的连接器。Runtime 的 pom 里两者都没有,而且不该有 ——
 * 那两条依赖会让「Runtime 不得解释业务语义」「Runtime 不得读 metadata 定义」
 * 同时失守。装配层是唯一有资格同时看见两边的地方。
 *
 * <p>P2 用 JDBC 直连实现。这不是终局:Contract 里写的是 Flink。但把 Flink 的
 * 部署、算子、checkpoint 一起塞进 P2,会让「离线同步能不能跑通」这个问题被
 * 一堆基础设施问题淹没。JDBC 版本先把<b>语义</b>跑对(字段映射、写入模式、
 * 批次、取消、指标),换引擎时改的是这一个类。
 */
@Component
public class OfflineSyncRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(OfflineSyncRunner.class);

    /** 每读多少行回报一次进度。太密会把执行事实表写爆,太疏则界面上像卡住了。 */
    private static final int PROGRESS_INTERVAL = 5_000;

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;

    public OfflineSyncRunner(DataSourceMapper dataSourceMapper,
                             ConnectionConfigAssembler assembler) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.OFFLINE_SYNC;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> source = section(context, "source");
        Map<String, Object> target = section(context, "target");
        Map<String, String> mappings = stringMap(context.plan().get("fieldMappings"));

        if (mappings.isEmpty()) {
            // 编译期已经拦过,走到这里说明计划是手工改的或来自旧版本编译器
            throw new IllegalArgumentException("物理计划里没有字段映射");
        }

        DataSourceEntity sourceDs = requireDataSource(str(source, "dataSourceId"), "源");
        DataSourceEntity targetDs = requireDataSource(str(target, "dataSourceId"), "目标");

        List<String> sourceColumns = List.copyOf(mappings.keySet());
        List<String> targetColumns = sourceColumns.stream().map(mappings::get).toList();

        String selectSql = buildSelect(sourceDs, source, sourceColumns);
        String insertSql = buildInsert(targetDs, target, targetColumns);
        int batchSize = intValue(target.get("batchSize"), 1000);

        log.info("离线同步开始 execution={} {} -> {} 字段{}个 批次{}",
                context.executionId(), str(source, "table"), str(target, "table"),
                sourceColumns.size(), batchSize);

        try (Connection readConn = open(sourceDs);
             Connection writeConn = open(targetDs)) {

            // 读连接设只读:同步任务不该有任何写源库的可能,即使计划被篡改。
            // 这与 ReadOnlySqlGuard 是同一个思路 —— 纵深防御,不指望单点。
            readConn.setReadOnly(true);
            writeConn.setAutoCommit(false);

            if ("OVERWRITE".equals(str(target, "writeMode"))) {
                truncateTarget(writeConn, targetDs, target);
            }

            return copy(context, readConn, writeConn, selectSql, insertSql,
                    sourceColumns.size(), batchSize);
        }
    }

    /**
     * 逐批读写。
     *
     * <p>三件事必须在这个循环里做对:
     * <ul>
     *   <li><b>流式读取</b> —— {@code setFetchSize} 加上 PostgreSQL 需要的
     *       autoCommit=false;否则驱动会把整张表拉进内存,一千万行直接 OOM</li>
     *   <li><b>定期查取消旗</b> —— 线程中断对阻塞在 JDBC 上的调用基本无效,
     *       主动退出是唯一可靠的路径</li>
     *   <li><b>批量提交</b> —— 每行一次 commit 会让同步慢一个数量级</li>
     * </ul>
     */
    private RunResult copy(RunContext context, Connection readConn, Connection writeConn,
                           String selectSql, String insertSql, int columnCount, int batchSize)
            throws SQLException, InterruptedException {

        long rowsRead = 0;
        long rowsWritten = 0;
        long pendingInBatch = 0;

        try (PreparedStatement read = readConn.prepareStatement(selectSql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement write = writeConn.prepareStatement(insertSql)) {

            read.setFetchSize(batchSize);

            try (ResultSet rs = read.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                if (meta.getColumnCount() != columnCount) {
                    throw new IllegalStateException(
                            "源表返回 %d 列,与映射的 %d 列不符".formatted(
                                    meta.getColumnCount(), columnCount));
                }

                while (rs.next()) {
                    // 每行都查一次取消旗。这个检查本身极廉价(读一个 volatile),
                    // 而它换来的是"点了取消真的会停"。
                    context.throwIfCanceled();

                    for (int i = 1; i <= columnCount; i++) {
                        write.setObject(i, rs.getObject(i));
                    }
                    write.addBatch();
                    rowsRead++;
                    pendingInBatch++;

                    if (pendingInBatch >= batchSize) {
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
            // 固有代价,不假装能做到全或无。用户要原子性就该用 OVERWRITE。
            rollbackQuietly(writeConn);
            throw e;
        }

        log.info("离线同步完成 execution={} 读{}行 写{}行",
                context.executionId(), rowsRead, rowsWritten);
        return RunResult.of(rowsRead, rowsWritten, 0);
    }

    private long flush(PreparedStatement write, Connection writeConn) throws SQLException {
        int[] results = write.executeBatch();
        writeConn.commit();
        write.clearBatch();
        long written = 0;
        for (int result : results) {
            // SUCCESS_NO_INFO(-2)表示成功但驱动不报行数 —— 按 1 计,
            // 当成 0 会让"写了多少行"这个指标在某些驱动上恒为 0
            written += result >= 0 ? result : (result == Statement.SUCCESS_NO_INFO ? 1 : 0);
        }
        return written;
    }

    private void truncateTarget(Connection writeConn, DataSourceEntity targetDs,
                                Map<String, Object> target) throws SQLException {
        String table = qualified(targetDs, str(target, "database"), str(target, "schema"),
                str(target, "table"));
        // DELETE 而不是 TRUNCATE:TRUNCATE 在多数库上是 DDL,会隐式提交,
        // 让后续失败时的回滚失去意义。慢一些,但语义是对的。
        try (Statement statement = writeConn.createStatement()) {
            int deleted = statement.executeUpdate("DELETE FROM " + table);
            writeConn.commit();
            log.info("OVERWRITE 模式已清空目标表 {},删除 {} 行", table, deleted);
        }
    }

    // ── SQL 构造 ────────────────────────────────────────────────────────

    private String buildSelect(DataSourceEntity ds, Map<String, Object> source,
                               List<String> columns) {
        String table = qualified(ds, str(source, "database"), str(source, "schema"),
                str(source, "table"));
        String columnList = columns.stream().map(c -> quote(ds, c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();

        StringBuilder sql = new StringBuilder("SELECT ").append(columnList)
                .append(" FROM ").append(table);
        String where = str(source, "whereClause");
        if (where != null && !where.isBlank()) {
            // where 由用户填写,已经过编译期的结构校验但不是参数化的。
            // 源连接是只读的(见 run 里的 setReadOnly),所以最坏情况是读到
            // 不该读的数据,不会写坏源库 —— 但这仍是一个已知的注入面,
            // 收敛它需要一个表达式 DSL,属于 P3 的范围。
            sql.append(" WHERE ").append(where);
        }
        return sql.toString();
    }

    private String buildInsert(DataSourceEntity ds, Map<String, Object> target,
                               List<String> columns) {
        String table = qualified(ds, str(target, "database"), str(target, "schema"),
                str(target, "table"));
        String columnList = columns.stream().map(c -> quote(ds, c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(table, columnList, placeholders);
    }

    /**
     * 限定表名。
     *
     * <p>各方言的库/模式层级不同:MySQL/Doris 只有库,PostgreSQL 库+模式,
     * 达梦以模式为主。这里按<b>实际拿到的段</b>拼,而不是按类型分支 —— 后者
     * 每加一种数据库就要回来改一次。
     */
    private String qualified(DataSourceEntity ds, String database, String schema, String table) {
        List<String> parts = new ArrayList<>(3);
        if (schema != null && !schema.isBlank()) {
            parts.add(quote(ds, schema));
        } else if (database != null && !database.isBlank()) {
            parts.add(quote(ds, database));
        }
        parts.add(quote(ds, table));
        return String.join(".", parts);
    }

    /** 标识符引用符按方言取。用错会让带保留字的表名(如 order)整批失败。 */
    private String quote(DataSourceEntity ds, String identifier) {
        DataSourceType type = ds.getType();
        if (type == DataSourceType.MYSQL || type == DataSourceType.DORIS
                || type == DataSourceType.STARROCKS) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // ── 连接 ────────────────────────────────────────────────────────────

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
        props.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));

        String url = config.jdbcUrlOverride() != null && !config.jdbcUrlOverride().isBlank()
                ? config.jdbcUrlOverride()
                : jdbcUrl(entity, config);
        return DriverManager.getConnection(url, props);
    }

    /**
     * 拼 JDBC URL。
     *
     * <p>与 {@code AbstractJdbcConnector.buildJdbcUrl} 逻辑重复,这是有意的
     * 折中:复用它需要 dg-app 依赖 dg-data-connectors 的内部实现(那些方法是
     * protected 的,为连接器子类而设),而把它们提成公共 API 会让连接器的
     * 内部结构变成对外契约。等 P3 接入 Flink 时这段会整体消失。
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
                    "离线同步不支持数据源类型 " + entity.getType());
        };
    }

    private DataSourceEntity requireDataSource(String id, String label) {
        DataSourceEntity entity = id == null ? null : dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("%s数据源不存在: %s".formatted(label, id));
        }
        return entity;
    }

    @Override
    public String classify(Exception e) {
        // 区分"目标端的问题"与"平台的问题":值班的人第一件事就是判断
        // 该找 DBA 还是找开发
        return e instanceof SQLException ? "DAT_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(RunContext context, String key) {
        Object value = context.plan().get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("物理计划缺少 " + key + " 段");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) ->
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return result;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
