package com.datagov.data.connector;

import com.datagov.common.error.BizException;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.data.spi.RelationalCatalogReader;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.FileEntry;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableKind;
import com.datagov.data.spi.catalog.CatalogPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关的分派逻辑。
 *
 * <p>这里验证的是「三级树与两级树的差别只存在于网关这一个 switch 里」这个设计:
 * 加一种新引擎时只要正确声明 {@code hasSchemaLevel},下钻逻辑无需改动。
 */
@DisplayName("DataAccessGateway 分派")
class DefaultDataAccessGatewayTest {

    @Test
    @DisplayName("三级树:ROOT→库,DATABASE→模式,SCHEMA→表,TABLE→列")
    void threeLevelDrillDown() {
        RecordingRelationalConnector connector =
                new RecordingRelationalConnector(Set.of(DataSourceType.POSTGRESQL), true);
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(List.of(connector));
        ConnectionConfig config = anyConfig();

        assertThat(gateway.browse(DataSourceType.POSTGRESQL, config, CatalogPath.root())
                .databases()).hasSize(1);
        assertThat(gateway.browse(DataSourceType.POSTGRESQL, config, CatalogPath.ofDatabase("db"))
                .schemas()).hasSize(1);
        assertThat(gateway.browse(DataSourceType.POSTGRESQL, config, CatalogPath.ofSchema("db", "s"))
                .tables()).hasSize(1);
        assertThat(gateway.browse(DataSourceType.POSTGRESQL, config, CatalogPath.ofTable("db", "s", "t"))
                .columns()).hasSize(1);

        assertThat(connector.calls)
                .containsExactly("listDatabases", "listSchemas", "listTables", "listColumns");
    }

    @Test
    @DisplayName("两级树:DATABASE 直接返回表,跳过模式层")
    void twoLevelDrillDownSkipsSchema() {
        RecordingRelationalConnector connector =
                new RecordingRelationalConnector(Set.of(DataSourceType.MYSQL), false);
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(List.of(connector));

        CatalogPage page = gateway.browse(DataSourceType.MYSQL, anyConfig(), CatalogPath.ofDatabase("db"));

        assertThat(page.tables()).hasSize(1);
        assertThat(page.schemas()).isEmpty();
        // 关键:没有模式层的引擎不应该被调用 listSchemas
        assertThat(connector.calls).containsExactly("listTables");
    }

    @Test
    @DisplayName("文件型数据源按路径返回文件条目")
    void fileBasedBrowsing() {
        StubFileConnector connector = new StubFileConnector();
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(List.of(connector));

        CatalogPage page = gateway.browse(DataSourceType.FTP, anyConfig(), CatalogPath.ofPath("/data"));

        assertThat(page.files()).extracting(FileEntry::name).containsExactly("a.csv");
    }

    @Test
    @DisplayName("关系型数据源按文件路径浏览应被拒绝")
    void relationalRejectsPathBrowsing() {
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(
                List.of(new RecordingRelationalConnector(Set.of(DataSourceType.POSTGRESQL), true)));

        assertThatThrownBy(() -> gateway.browse(
                DataSourceType.POSTGRESQL, anyConfig(), CatalogPath.ofPath("/tmp")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能按文件路径浏览");
    }

    @Test
    @DisplayName("不支持结构浏览的类型(RestAPI)给出明确错误")
    void nonBrowsableTypeGivesClearError() {
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(List.of(new StubHttpConnector()));

        assertThatThrownBy(() -> gateway.browse(
                DataSourceType.REST_API, anyConfig(), CatalogPath.root()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持结构浏览");
    }

    @Test
    @DisplayName("同一类型注册了两个实现时启动即失败,而不是静默取其一")
    void duplicateRegistrationFailsFast() {
        // 静默取第一个会让「我明明改了连接器却没生效」变成极难排查的问题
        List<DataSourceConnector> duplicates = List.of(
                new RecordingRelationalConnector(Set.of(DataSourceType.MYSQL), false),
                new RecordingRelationalConnector(Set.of(DataSourceType.MYSQL), false));

        assertThatThrownBy(() -> new DefaultDataAccessGateway(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存在多个连接器实现");
    }

    @Test
    @DisplayName("未注册的类型给出明确错误")
    void unregisteredTypeGivesClearError() {
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(
                List.of(new RecordingRelationalConnector(Set.of(DataSourceType.MYSQL), false)));

        assertThatThrownBy(() -> gateway.testConnection(DataSourceType.ORACLE, anyConfig()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有匹配的连接器实现");
    }

    @Test
    @DisplayName("availableTypes 只包含驱动就绪的类型")
    void availableTypesReflectDriverPresence() {
        DefaultDataAccessGateway gateway = new DefaultDataAccessGateway(List.of(
                new RecordingRelationalConnector(Set.of(DataSourceType.MYSQL), false),
                new StubFileConnector()));

        // MySQL 驱动在 classpath 上,FTP 无驱动概念 —— 两者都应可用
        assertThat(gateway.availableTypes())
                .contains(DataSourceType.MYSQL, DataSourceType.FTP);
        assertThat(gateway.registeredTypes())
                .contains(DataSourceType.MYSQL, DataSourceType.FTP);
    }

    private static ConnectionConfig anyConfig() {
        return ConnectionConfig.builder().host("h").port(1).database("db").build();
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /** 记录被调用了哪些方法,用于断言分派路径而不只是返回值。 */
    private static final class RecordingRelationalConnector implements RelationalCatalogReader {
        private final Set<DataSourceType> types;
        private final boolean hasSchemaLevel;
        private final List<String> calls = new ArrayList<>();

        RecordingRelationalConnector(Set<DataSourceType> types, boolean hasSchemaLevel) {
            this.types = types;
            this.hasSchemaLevel = hasSchemaLevel;
        }

        @Override public Set<DataSourceType> supportedTypes() { return types; }

        @Override public ConnectorCapabilities capabilities(DataSourceType type) {
            return hasSchemaLevel
                    ? ConnectorCapabilities.relationalWithSchema()
                    : ConnectorCapabilities.relationalWithoutSchema();
        }

        @Override public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
            return ConnectivityResult.success(1L, "stub");
        }

        @Override public List<DatabaseInfo> listDatabases(DataSourceType t, ConnectionConfig c) {
            calls.add("listDatabases");
            return List.of(new DatabaseInfo("db", null, null, null));
        }

        @Override public List<SchemaInfo> listSchemas(DataSourceType t, ConnectionConfig c, CatalogPath p) {
            calls.add("listSchemas");
            return List.of(new SchemaInfo("s", null, null));
        }

        @Override public List<TableInfo> listTables(DataSourceType t, ConnectionConfig c, CatalogPath p) {
            calls.add("listTables");
            return List.of(new TableInfo("t", TableKind.TABLE, null, null, null, null));
        }

        @Override public List<ColumnInfo> listColumns(DataSourceType t, ConnectionConfig c, CatalogPath p) {
            calls.add("listColumns");
            return List.of(new ColumnInfo("c", "int", null, null, null, true, false, null, null, 1));
        }
    }

    private static final class StubFileConnector implements FileCatalogReader {
        @Override public Set<DataSourceType> supportedTypes() { return Set.of(DataSourceType.FTP); }

        @Override public ConnectorCapabilities capabilities(DataSourceType type) {
            return ConnectorCapabilities.fileBased();
        }

        @Override public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
            return ConnectivityResult.success(1L, "stub-ftp");
        }

        @Override public List<FileEntry> listEntries(DataSourceType t, ConnectionConfig c, String path) {
            return List.of(new FileEntry("a.csv", "/data/a.csv", false, 10L, null));
        }

        @Override public java.io.InputStream openFile(DataSourceType t, ConnectionConfig c, String path) {
            return new java.io.ByteArrayInputStream("id,name\n1,x\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class StubHttpConnector implements DataSourceConnector {
        @Override public Set<DataSourceType> supportedTypes() { return Set.of(DataSourceType.REST_API); }

        @Override public ConnectorCapabilities capabilities(DataSourceType type) {
            return ConnectorCapabilities.httpBased();
        }

        @Override public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
            return ConnectivityResult.success(1L, "HTTP 200");
        }
    }
}
