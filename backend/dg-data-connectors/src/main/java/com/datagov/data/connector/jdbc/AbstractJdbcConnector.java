package com.datagov.data.connector.jdbc;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.connector.ConnectorExceptions;
import com.datagov.data.connector.mapping.TypeMapper;
import com.datagov.data.connector.ddl.DialectDdl;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.RelationalCatalogReader;
import com.datagov.data.spi.SqlQueryExecutor;
import com.datagov.data.spi.ddl.DdlGenerator;
import com.datagov.data.spi.ddl.TableDdl;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableKind;
import com.datagov.data.spi.catalog.CatalogPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * JDBC 连接器共性实现。
 *
 * <p>结构探测统一走 {@link DatabaseMetaData} 而非各家系统表:标准接口在所有
 * 驱动上都能用,方言差异收敛成几个可覆写的钩子。代价是拿不到引擎特有的
 * 元信息(如 Doris 的分区模型),但那属于 P2 建表时才需要的东西 ——
 * P1 的目标是"能浏览库表结构",标准接口完全够用。
 *
 * <p><b>无状态</b>:本类及子类不持有任何连接或配置。每个方法自己开连接、
 * 自己关闭。{@link ConnectionConfig} 里有明文口令,缓存它等于把口令留在堆上。
 */
public abstract class AbstractJdbcConnector
        implements RelationalCatalogReader, SqlQueryExecutor, DdlGenerator {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcConnector.class);

    /**
     * 只探测表与视图。系统表、索引、别名等对用户没有意义,列出来只会淹没真正要找的表。
     *
     * <p>{@code PARTITIONED TABLE} 必须在列 —— pgjdbc 用它表示分区父表。漏掉它的后果
     * 不是"少一张表",而是<b>恰好反过来</b>:用户要找的 {@code payment} 被滤掉了,
     * 而它的几十个子分区因为报的是普通 {@code TABLE} 反倒全列了出来。
     * 子分区的隐藏见 {@link #hiddenTableNames}。
     */
    private static final String[] BROWSABLE_TABLE_TYPES = {
            "TABLE", "VIEW", "MATERIALIZED VIEW", "EXTERNAL TABLE", "PARTITIONED TABLE"
    };

    // ── 子类必须提供 ────────────────────────────────────────────────────

    protected abstract String buildJdbcUrl(DataSourceType type, ConnectionConfig config);

    protected abstract TypeMapper typeMapper(DataSourceType type);

    // ── 子类可覆写的方言钩子 ────────────────────────────────────────────

    /**
     * 把超时写进驱动属性。
     *
     * <p>每家驱动的参数名都不一样,而且<b>必须</b>设 —— 没有超时的探测会在
     * 目标库不可达时一直挂着,几次点击就能把 HTTP 线程池占满。
     */
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // 默认用 JDBC 标准的 loginTimeout(秒)。子类应覆写为自家参数以获得更精确的控制。
        props.setProperty("loginTimeout", String.valueOf(Math.max(1, config.connectTimeoutMillis() / 1000)));
    }

    /**
     * 列库用的 SQL。返回 null 表示改用 {@link DatabaseMetaData#getCatalogs()}。
     *
     * <p>Oracle / 达梦这类"没有独立库概念"的引擎应返回 null 并覆写
     * {@link #listDatabases} 的行为(见下面的默认实现如何处理空结果)。
     */
    protected String listDatabasesSql() {
        return null;
    }

    /** {@link DatabaseMetaData} 调用中 catalog 参数取什么。 */
    protected String metadataCatalog(ConnectionConfig config, CatalogPath path) {
        return path.database() != null ? path.database() : config.database();
    }

    /** {@link DatabaseMetaData} 调用中 schema 参数取什么。 */
    protected String metadataSchema(ConnectionConfig config, CatalogPath path) {
        return path.schema();
    }

    /**
     * 为下钻到某个库而调整连接配置。
     *
     * <p>PostgreSQL 一个连接只能看见一个库,要列 X 库的表就必须连到 X 库上;
     * MySQL 则一个连接可以跨库。默认不调整,由 PostgreSQL 子类覆写。
     */
    protected ConnectionConfig configForPath(ConnectionConfig config, CatalogPath path) {
        return config;
    }

    /**
     * 该模式下不该出现在表清单里的表名。
     *
     * <p>目前唯一的用途是分区子表。<b>分区是父表的存储细节,不是用户要浏览的对象</b>:
     * 按月分区的表两年就是二十几个子分区,它们会把用户真正要找的表挤到几屏之外,
     * 而对着某一个月度分区做同步或迁移几乎总是配错了。要看分区布局,走自定义查询。
     *
     * <p>JDBC 的 {@code getTables} 不提供"这张表是不是分区"的信息,所以只能由方言
     * 自己回答。默认不隐藏任何表。
     */
    protected Set<String> hiddenTableNames(Connection connection, String catalog, String schema)
            throws SQLException {
        return Set.of();
    }

    // ── SPI 实现 ────────────────────────────────────────────────────────

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        long startedAt = System.nanoTime();
        try (Connection connection = open(type, config)) {
            // isValid 是 JDBC 标准的连通性检查,免去各方言 "SELECT 1" / "SELECT 1 FROM DUAL" 的差异
            int timeoutSeconds = Math.max(1, config.readTimeoutMillis() / 1000);
            if (!connection.isValid(timeoutSeconds)) {
                return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, elapsedMillis(startedAt),
                        ConnectorExceptions.userMessage(ErrorCode.DAT_CONNECT_FAILED, config.host(), config.port()),
                        "连接已建立但 Connection.isValid 返回 false");
            }
            DatabaseMetaData metaData = connection.getMetaData();
            String version = "%s %s".formatted(
                    metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
            return ConnectivityResult.success(elapsedMillis(startedAt), version);

        } catch (SQLException ex) {
            ErrorCode code = ConnectorExceptions.classify(ex);
            // 这里刻意不打堆栈:连接失败是高频的正常业务结果,打堆栈会淹没日志
            log.debug("连通性测试失败 type={} target={} code={}", type, config.masked(), code.code());
            return ConnectivityResult.failure(code, elapsedMillis(startedAt),
                    ConnectorExceptions.userMessage(code, config.host(), config.port()),
                    "SQLState=%s vendorCode=%d %s".formatted(
                            ex.getSQLState(), ex.getErrorCode(), ex.getMessage()));

        } catch (BizException ex) {
            // 驱动缺失走这条路 —— 同样不该抛给调用方,而是作为失败结果返回
            return ConnectivityResult.failure(ex.errorCode(), elapsedMillis(startedAt),
                    ex.getMessage(), ex.detail());

        } catch (RuntimeException ex) {
            log.warn("连通性测试出现未预期异常 type={} target={}", type, config.masked(), ex);
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, elapsedMillis(startedAt),
                    ConnectorExceptions.userMessage(ErrorCode.DAT_CONNECT_FAILED, config.host(), config.port()),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Override
    public List<DatabaseInfo> listDatabases(DataSourceType type, ConnectionConfig config) {
        try (Connection connection = open(type, config)) {
            String sql = listDatabasesSql();
            List<DatabaseInfo> databases = sql != null
                    ? queryDatabaseNames(connection, sql, config)
                    : readCatalogs(connection);

            // 引擎没有独立的库概念(Oracle / 达梦),或驱动没报出来:
            // 用连接配置里的库名占位,让 UI 的层级结构保持一致。
            if (databases.isEmpty() && config.database() != null) {
                databases = List.of(new DatabaseInfo(config.database(), null, null, null));
            }
            return databases;
        } catch (SQLException ex) {
            throw ConnectorExceptions.introspectFailed("列出库列表", ex);
        }
    }

    @Override
    public List<SchemaInfo> listSchemas(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        ConnectionConfig effective = configForPath(config, path);
        try (Connection connection = open(type, effective)) {
            String catalog = metadataCatalog(effective, path);
            List<SchemaInfo> schemas = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getSchemas(catalog, null)) {
                while (rs.next()) {
                    schemas.add(new SchemaInfo(rs.getString("TABLE_SCHEM"), null, null));
                }
            }
            return schemas;
        } catch (SQLException ex) {
            throw ConnectorExceptions.introspectFailed("列出模式 " + path.display(), ex);
        }
    }

    @Override
    public List<TableInfo> listTables(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        ConnectionConfig effective = configForPath(config, path);
        try (Connection connection = open(type, effective)) {
            String catalog = metadataCatalog(effective, path);
            String schema = metadataSchema(effective, path);

            Set<String> hidden = hiddenTableNames(connection, catalog, schema);

            List<TableInfo> tables = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData()
                    .getTables(catalog, schema, "%", BROWSABLE_TABLE_TYPES)) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (hidden.contains(name)) {
                        continue;
                    }
                    tables.add(new TableInfo(
                            name,
                            toTableKind(rs.getString("TABLE_TYPE")),
                            rs.getString("REMARKS"),
                            null,   // 行数与体积需要引擎特有的统计表,P1 不采集
                            null,
                            null));
                }
            }
            return tables;
        } catch (SQLException ex) {
            throw ConnectorExceptions.introspectFailed("列出表 " + path.display(), ex);
        }
    }

    @Override
    public List<ColumnInfo> listColumns(DataSourceType type, ConnectionConfig config, CatalogPath path) {
        if (path.table() == null) {
            throw new BizException(ErrorCode.DAT_INTROSPECT_FAILED, "未指定表名,无法列出字段");
        }
        ConnectionConfig effective = configForPath(config, path);
        try (Connection connection = open(type, effective)) {
            String catalog = metadataCatalog(effective, path);
            String schema = metadataSchema(effective, path);
            DatabaseMetaData metaData = connection.getMetaData();

            Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, schema, path.table());
            TypeMapper mapper = typeMapper(type);

            List<ColumnInfo> columns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(catalog, schema, path.table(), "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String rawType = rs.getString("TYPE_NAME");
                    int jdbcType = rs.getInt("DATA_TYPE");
                    Integer precision = nullableInt(rs, "COLUMN_SIZE");
                    Integer scale = nullableInt(rs, "DECIMAL_DIGITS");

                    CanonicalType canonical = mapper.map(rawType, jdbcType, precision, scale);
                    if (canonical == CanonicalType.UNKNOWN) {
                        // 记下来,便于事后补全映射表。这是 R7 的运行期反馈回路:
                        // 生产上真实出现过的未知类型,比拍脑袋想出来的映射规则更值得实现。
                        log.info("未能规范化的类型 type={} column={} rawType={} jdbcType={}",
                                type, name, rawType, jdbcType);
                    }

                    columns.add(new ColumnInfo(
                            name,
                            rawType,
                            canonical,
                            precision,
                            scale,
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            primaryKeys.contains(name),
                            rs.getString("COLUMN_DEF"),
                            rs.getString("REMARKS"),
                            rs.getInt("ORDINAL_POSITION")));
                }
            }
            return columns;
        } catch (SQLException ex) {
            throw ConnectorExceptions.introspectFailed("列出字段 " + path.display(), ex);
        }
    }

    /**
     * 执行自定义查询(功能 7)。
     *
     * <p>三道护栏缺一不可:只读校验、行数上限、查询超时。前者防误操作与注入,
     * 后两者防"一条查询拖垮别人生产库" —— 平台连的是业务方的库,
     * 它不该有能力对那些库做任何无界的事。
     */
    @Override
    public SqlQuery.Result executeQuery(DataSourceType type, ConnectionConfig config,
                                        SqlQuery.Request request) {
        String sql = ReadOnlySqlGuard.requireReadOnly(request.sql());
        long startedAt = System.nanoTime();

        try (Connection connection = open(type, config)) {
            // 能设只读就设:多一层由数据库自己强制的保障,比语法白名单可靠得多
            trySetReadOnly(connection);

            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(request.timeoutSeconds());
                // 多取一行用来判断是否被截断 —— 否则恰好等于上限时无法区分
                // "刚好这么多"和"还有更多"
                statement.setMaxRows(request.maxRows() + 1);
                statement.setFetchSize(Math.min(request.maxRows() + 1, 500));

                try (ResultSet rs = statement.executeQuery(sql)) {
                    return readResult(rs, type, request.maxRows(), elapsedMillis(startedAt));
                }
            }
        } catch (SQLException ex) {
            ErrorCode code = ConnectorExceptions.classify(ex);
            // 查询失败最常见的原因是 SQL 写错了,把数据库的原话带给用户
            // 比一句"执行失败"有用得多
            throw new BizException(ErrorCode.DAT_QUERY_FAILED,
                    "查询执行失败: " + ex.getMessage(),
                    "SQLState=%s vendorCode=%d classified=%s".formatted(
                            ex.getSQLState(), ex.getErrorCode(), code.code()), ex);
        }
    }

    private SqlQuery.Result readResult(ResultSet rs, DataSourceType type,
                                       int maxRows, long elapsedMillis) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        TypeMapper mapper = typeMapper(type);

        List<SqlQuery.Column> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String rawType = meta.getColumnTypeName(i);
            columns.add(new SqlQuery.Column(
                    meta.getColumnLabel(i),
                    rawType,
                    mapper.map(rawType, meta.getColumnType(i),
                            meta.getPrecision(i), meta.getScale(i))));
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;   // 多取的那一行证明后面还有
                break;
            }
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(renderCell(rs, i));
            }
            rows.add(row);
        }
        return new SqlQuery.Result(columns, rows, rows.size(), truncated, elapsedMillis);
    }

    /**
     * 单元格一律转成字符串。
     *
     * <p>平台只负责展示,不承担把目标端类型映射成 Java 类型的责任 ——
     * 那会引入一堆只在特定驱动上才出现的转换异常(Oracle 的 TIMESTAMP WITH
     * LOCAL TIME ZONE、PostgreSQL 的自定义域类型等),而展示层根本不需要。
     * 二进制列只报长度,不把几 MB 的 BLOB 塞进 JSON 响应。
     */
    private static String renderCell(ResultSet rs, int index) throws SQLException {
        int sqlType = rs.getMetaData().getColumnType(index);
        if (sqlType == java.sql.Types.BLOB || sqlType == java.sql.Types.LONGVARBINARY
                || sqlType == java.sql.Types.VARBINARY || sqlType == java.sql.Types.BINARY) {
            byte[] bytes = rs.getBytes(index);
            return bytes == null ? null : "<binary %d bytes>".formatted(bytes.length);
        }
        String value = rs.getString(index);
        return rs.wasNull() ? null : value;
    }

    /** 并非所有驱动都支持只读连接;不支持时静默跳过,语法护栏仍然生效。 */
    private void trySetReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException | UnsupportedOperationException e) {
            log.debug("目标驱动不支持只读连接,依赖语法护栏", e);
        }
    }

    // ── 建表(功能 9)────────────────────────────────────────────────────

    /**
     * 生成建表语句。
     *
     * <p>渲染逻辑集中在 {@link DialectDdl},不在各连接器子类里 —— 那些差异
     * ("Doris 没有 TEXT""Oracle 的 VARCHAR2 上限 4000 字节")放在一起才看得出
     * 规律,分散到六个类之后没有人会去对照着读。
     */
    @Override
    public TableDdl.GeneratedDdl generateCreateTable(DataSourceType type,
                                                     TableDdl.CreateTableSpec spec) {
        return DialectDdl.generate(type, spec);
    }

    /**
     * 执行 DDL。
     *
     * <p>逐条执行,不开事务:多数数据库的 DDL 本来就是隐式提交的,包一层事务
     * 只会制造"看起来能回滚"的错觉。中途失败时前面已建的表留在目标端 ——
     * 这是 DDL 的固有性质,由调用方在执行记录里如实反映,而不是假装原子。
     */
    @Override
    public void executeDdl(DataSourceType type, ConnectionConfig config,
                           List<String> statements) throws SQLException {
        try (Connection connection = open(type, config);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                if (sql != null && !sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    /**
     * 开一个连接。
     *
     * <p>没有用连接池: P1 的探测是低频、短时的交互式操作,池化带来的连接复用
     * 收益不足以抵消"池要按数据源维度管理生命周期"的复杂度。P2 的同步任务
     * 是持续高频读写,那时才需要池 —— 而那属于 Runtime Space,不在这里。
     */
    protected Connection open(DataSourceType type, ConnectionConfig config) throws SQLException {
        String driverClass = type.driverClassName();
        if (driverClass != null) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw ConnectorExceptions.driverMissing(driverClass, e);
            }
        }

        Properties props = new Properties();
        if (config.username() != null) {
            props.setProperty("user", config.username());
        }
        if (config.password() != null) {
            props.setProperty("password", config.password());
        }
        config.properties().forEach(props::setProperty);
        applyTimeouts(props, config);

        // 直填 URL 优先于按 host/port/database 拼装。信创与内网环境里经常需要
        // 挂 failover 地址、Kerberos 参数或专有连接属性,拼装逻辑覆盖不完,
        // 留一个直填口子比不断给拼装函数加分支更实际。
        String override = config.jdbcUrlOverride();
        String url = (override != null && !override.isBlank())
                ? override.trim()
                : buildJdbcUrl(type, config);

        return DriverManager.getConnection(url, props);
    }

    private List<DatabaseInfo> queryDatabaseNames(Connection connection, String sql,
                                                  ConnectionConfig config) throws SQLException {
        List<DatabaseInfo> databases = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, config.readTimeoutMillis() / 1000));
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    databases.add(new DatabaseInfo(rs.getString(1), null, null, null));
                }
            }
        }
        return databases;
    }

    private List<DatabaseInfo> readCatalogs(Connection connection) throws SQLException {
        List<DatabaseInfo> databases = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getCatalogs()) {
            while (rs.next()) {
                databases.add(new DatabaseInfo(rs.getString("TABLE_CAT"), null, null, null));
            }
        }
        return databases;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metaData, String catalog,
                                        String schema, String table) {
        Set<String> keys = new LinkedHashSet<>();
        // 主键读取失败不该让整个字段列表失败 —— 少一个主键标记,总比什么都看不到强
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException ex) {
            log.debug("读取主键失败 table={},按无主键处理", table, ex);
            return new HashSet<>();
        }
        return keys;
    }

    private static TableKind toTableKind(String jdbcTableType) {
        if (jdbcTableType == null) {
            return TableKind.OTHER;
        }
        return switch (jdbcTableType.toUpperCase()) {
            // 分区父表归为普通表:对下游的每一个消费方(浏览、查询、同步、迁移、建表)
            // 它的行为与一张表完全一致。单独立一个枚举值,等于逼所有消费方去学一个
            // 不改变任何处理方式的区别。
            case "TABLE", "BASE TABLE", "PARTITIONED TABLE" -> TableKind.TABLE;
            case "VIEW" -> TableKind.VIEW;
            case "MATERIALIZED VIEW" -> TableKind.MATERIALIZED_VIEW;
            case "EXTERNAL TABLE" -> TableKind.EXTERNAL_TABLE;
            default -> TableKind.OTHER;
        };
    }

    /** {@code getInt} 对 NULL 返回 0,这会把"未知精度"和"精度为 0"混为一谈,所以要显式判空。 */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 端口:优先用配置的,没配就用该类型的默认端口。 */
    protected static int portOrDefault(DataSourceType type, ConnectionConfig config) {
        return config.port() != null && config.port() > 0 ? config.port() : type.defaultPort();
    }
}
