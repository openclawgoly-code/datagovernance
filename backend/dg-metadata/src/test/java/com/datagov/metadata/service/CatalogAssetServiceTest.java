package com.datagov.metadata.service;

import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogModel.TableKind;
import com.datagov.data.spi.catalog.CatalogGateway;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogNamespace;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogPartition;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSchemaVersion;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSnapshotRun;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogNamespaceMapper;
import com.datagov.metadata.mapper.CatalogPartitionMapper;
import com.datagov.metadata.mapper.CatalogSchemaVersionMapper;
import com.datagov.metadata.mapper.CatalogSnapshotRunMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.CatalogAssetService.DatasetDetail;
import com.datagov.metadata.service.CatalogAssetService.RefreshResult;
import com.datagov.metadata.service.CatalogAssetService.SourceRequest;
import com.datagov.metadata.service.CatalogAssetService.SourceView;
import com.datagov.metadata.spi.CredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("数据目录采集")
class CatalogAssetServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String OTHER_WORKSPACE = "ws_beta";
    private static final String USER = "usr_1";

    private CatalogSourceMapper sourceMapper;
    private CatalogNamespaceMapper namespaceMapper;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private CatalogSchemaVersionMapper schemaVersionMapper;
    private CatalogPartitionMapper partitionMapper;
    private CatalogSnapshotRunMapper runMapper;
    private DataSourceService dataSourceService;
    private DataAccessGateway gateway;
    private CatalogGateway catalogGateway;
    private ConnectionConfigAssembler assembler;
    private CredentialResolver credentialResolver;
    private CatalogAssetService service;

    @BeforeEach
    void setUp() {
        sourceMapper = mock(CatalogSourceMapper.class);
        namespaceMapper = mock(CatalogNamespaceMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        schemaVersionMapper = mock(CatalogSchemaVersionMapper.class);
        partitionMapper = mock(CatalogPartitionMapper.class);
        runMapper = mock(CatalogSnapshotRunMapper.class);
        dataSourceService = mock(DataSourceService.class);
        gateway = mock(DataAccessGateway.class);
        catalogGateway = mock(CatalogGateway.class);
        assembler = mock(ConnectionConfigAssembler.class);
        credentialResolver = mock(CredentialResolver.class);

        service = new CatalogAssetService(sourceMapper, namespaceMapper, datasetMapper,
                columnMapper, schemaVersionMapper, partitionMapper, runMapper, dataSourceService,
                gateway, catalogGateway, assembler, credentialResolver, new ObjectMapper());

        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(assembler.assemble(any(), eq(WORKSPACE))).thenReturn(
                ConnectionConfig.builder().host("db.internal").database("ecology").build());
        when(namespaceMapper.insert(any(CatalogNamespace.class))).thenReturn(1);
        when(datasetMapper.insert(any(CatalogDataset.class))).thenReturn(1);
        when(columnMapper.insert(any(CatalogColumn.class))).thenReturn(1);
        when(columnMapper.delete(any())).thenReturn(0);
        when(columnMapper.selectList(any())).thenReturn(List.of());
        when(schemaVersionMapper.insert(any(CatalogSchemaVersion.class))).thenReturn(1);
        when(partitionMapper.insert(any(CatalogPartition.class))).thenReturn(1);
        when(partitionMapper.delete(any())).thenReturn(0);
        when(partitionMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.insert(any(CatalogSnapshotRun.class))).thenReturn(1);
        when(runMapper.updateById(any(CatalogSnapshotRun.class))).thenReturn(1);
        when(sourceMapper.updateById(any(CatalogSource.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("来源")
    class Sources {

        @Test
        @DisplayName("JDBC 来源必须指向当前空间的数据源")
        void createsJdbcSource() {
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(mysqlDataSource());
            when(sourceMapper.selectCount(any())).thenReturn(0L);

            SourceView view = service.createSource(
                    new SourceRequest("泛微 OA 目录", "JDBC", "ds_1", null));

            assertThat(view.id()).startsWith("cat_");
            assertThat(view.sourceType()).isEqualTo("JDBC");
            assertThat(view.dataSourceId()).isEqualTo("ds_1");
            verify(sourceMapper).insert(any(CatalogSource.class));
        }

        @Test
        @DisplayName("同空间重名 —— 409")
        void duplicateNameRejected() {
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(mysqlDataSource());
            when(sourceMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.createSource(
                    new SourceRequest("重复", "JDBC", "ds_1", null)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_CATALOG_NAME_DUPLICATED));
            verify(sourceMapper, never()).insert(any(CatalogSource.class));
        }

        @Test
        @DisplayName("文件/HTTP 类型不能作为 JDBC 目录来源")
        void nonRelationalDataSourceRejected() {
            DataSourceEntity ftp = mysqlDataSource();
            ftp.setType(DataSourceType.FTP);
            ftp.setFamily(DataSourceType.Family.FILE);
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(ftp);

            assertThatThrownBy(() -> service.createSource(
                    new SourceRequest("FTP 目录", "JDBC", "ds_1", null)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_CONFIG_INVALID));
        }

        @Test
        @DisplayName("未知来源类型被拒")
        void unsupportedSourceTypeRejected() {
            assertThatThrownBy(() -> service.createSource(
                    new SourceRequest("Iceberg", "ICEBERG_REST", "ds_1", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("暂不支持的数据目录来源");
        }

        @Test
        @DisplayName("HMS 来源不需要 dataSourceId，配置 uris 后创建成功")
        void createsHmsSource() {
            when(sourceMapper.selectCount(any())).thenReturn(0L);

            SourceView view = service.createSource(new SourceRequest("HMS 湖",
                    "HIVE_METASTORE", null,
                    "{\"uris\":[\"thrift://hms.internal:9083\"]}", null));

            assertThat(view.sourceType()).isEqualTo("HIVE_METASTORE");
            assertThat(view.dataSourceId()).isNull();
            verify(sourceMapper).insert(any(CatalogSource.class));
        }

        @Test
        @DisplayName("HMS 缺少 uris —— 400")
        void hmsRequiresUris() {
            assertThatThrownBy(() -> service.createSource(new SourceRequest("HMS 湖",
                    "HIVE_METASTORE", null, "{}", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("uris");
            verify(sourceMapper, never()).insert(any(CatalogSource.class));
        }

        @Test
        @DisplayName("HMS 提交 dataSourceId —— 400")
        void externalRejectsDataSourceId() {
            assertThatThrownBy(() -> service.createSource(new SourceRequest("HMS 湖",
                    "HIVE_METASTORE", "ds_1",
                    "{\"uris\":[\"thrift://hms:9083\"]}", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不能指定 dataSourceId");
        }

        @Test
        @DisplayName("Glue 配置不能包含 secretKey")
        void glueRejectsInlineSecret() {
            assertThatThrownBy(() -> service.createSource(new SourceRequest("Glue",
                    "GLUE", null,
                    "{\"region\":\"cn-north-1\",\"secretKey\":\"x\"}", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("平台凭据");
        }

        @Test
        @DisplayName("Parquet 目录必须指向对象存储/本地目录")
        void parquetRequiresObjectStorage() {
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(mysqlDataSource());

            assertThatThrownBy(() -> service.createSource(
                    new SourceRequest("Parquet", "PARQUET_DIRECTORY", "ds_1", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("只能指向对象存储/本地目录");
        }
    }

    @Nested
    @DisplayName("采集")
    class Refresh {

        @Test
        @DisplayName("MySQL 两级树:库 → 表 → 字段全部落入规范化表")
        void collectsMysqlCatalog() {
            CatalogSource source = source();
            DataSourceEntity dataSource = mysqlDataSource();
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(dataSource);
            when(gateway.capabilities(DataSourceType.MYSQL))
                    .thenReturn(ConnectorCapabilities.relationalWithoutSchema());
            when(gateway.browse(eq(DataSourceType.MYSQL), any(), any(CatalogPath.class)))
                    .thenAnswer(invocation -> catalogPage(invocation.getArgument(2)));
            when(namespaceMapper.selectOne(any())).thenReturn(null);
            when(datasetMapper.selectOne(any())).thenReturn(null);

            RefreshResult result = service.refresh("cat_1");

            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.namespaces()).isEqualTo(1);
            assertThat(result.datasets()).isEqualTo(1);
            assertThat(result.columns()).isEqualTo(2);
            assertThat(source.getLastRefreshedAt()).isNotNull();

            verify(namespaceMapper).insert(any(CatalogNamespace.class));
            verify(datasetMapper).insert(any(CatalogDataset.class));
            verify(columnMapper, times(2)).insert(any(CatalogColumn.class));
            verify(schemaVersionMapper).insert(any(CatalogSchemaVersion.class));
            verify(runMapper).insert(any(CatalogSnapshotRun.class));
            verify(runMapper).updateById(any(CatalogSnapshotRun.class));
        }

        @Test
        @DisplayName("Parquet 目录:数据集/字段/分区落入规范化表")
        void collectsParquetCatalog() {
            CatalogSource source = source();
            source.setSourceType("PARQUET_DIRECTORY");
            source.setDataSourceId("ds_pq");
            source.setConfigJson("{\"rootPath\":\"/lake\"}");
            DataSourceEntity dataSource = parquetDataSource();
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(dataSourceService.requireInWorkspace("ds_pq")).thenReturn(dataSource);
            when(catalogGateway.readCatalog(
                    eq(CatalogAssets.CatalogSourceType.PARQUET_DIRECTORY),
                    eq(DataSourceType.LOCAL), any(), eq("/lake")))
                    .thenReturn(parquetSnapshot());
            when(namespaceMapper.selectOne(any())).thenReturn(null);
            when(datasetMapper.selectOne(any())).thenReturn(null);

            RefreshResult result = service.refresh("cat_1");

            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.datasets()).isEqualTo(1);
            assertThat(result.columns()).isEqualTo(2);
            verify(datasetMapper).insert(any(CatalogDataset.class));
            verify(columnMapper, times(2)).insert(any(CatalogColumn.class));
            verify(partitionMapper).insert(any(CatalogPartition.class));
            verify(schemaVersionMapper).insert(any(CatalogSchemaVersion.class));
        }

        @Test
        @DisplayName("数据源未通过连通性测试 —— 拒绝采集")
        void unavailableDataSourceRejected() {
            CatalogSource source = source();
            DataSourceEntity dataSource = mysqlDataSource();
            dataSource.setStatus(DataSourceStatus.DRAFT);
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(dataSource);

            assertThatThrownBy(() -> service.refresh("cat_1"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE));
        }

        @Test
        @DisplayName("刷新时保持列 ID 稳定，不删除被敏感/术语/质量引用的列")
        void refreshPreservesColumnIds() {
            CatalogSource source = source();
            DataSourceEntity dataSource = mysqlDataSource();
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(dataSource);
            when(gateway.capabilities(DataSourceType.MYSQL))
                    .thenReturn(ConnectorCapabilities.relationalWithoutSchema());
            when(gateway.browse(eq(DataSourceType.MYSQL), any(), any(CatalogPath.class)))
                    .thenAnswer(invocation -> catalogPage(invocation.getArgument(2)));
            when(namespaceMapper.selectOne(any())).thenReturn(null);
            when(datasetMapper.selectOne(any())).thenReturn(null);
            CatalogColumn existingId = column();
            CatalogColumn existingName = new CatalogColumn();
            existingName.setId("catcol_name");
            existingName.setDatasetId("catds_1");
            existingName.setName("name");
            when(columnMapper.selectList(any())).thenReturn(List.of(existingId, existingName));

            service.refresh("cat_1");

            verify(columnMapper, never()).insert(any(CatalogColumn.class));
            verify(columnMapper, times(2)).updateById(any(CatalogColumn.class));
            verify(columnMapper, never()).deleteById(any(String.class));
        }

        @Test
        @DisplayName("HMS 采集 database/table/column/partition，并带回 warnings")
        void collectsHmsCatalog() {
            CatalogSource source = hmsSource();
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(catalogGateway.readExternalCatalog(any())).thenReturn(
                    new CatalogAssets.Snapshot(
                            List.of(new CatalogAssets.Namespace("dwd", "dwd", "DATABASE", null)),
                            List.of(new CatalogAssets.Dataset("dwd", "orders",
                                    "dwd.orders", "EXTERNAL_TABLE", "HIVE",
                                    "s3://lake/dwd/orders", null, null, null,
                                    List.of("dt"),
                                    List.of(new CatalogAssets.Column("id", 1, "bigint",
                                                    CanonicalType.BIGINT, null, null,
                                                    true, false, false, null),
                                            new CatalogAssets.Column("dt", 2, "string",
                                                    CanonicalType.TEXT, null, null,
                                                    true, false, true, null)),
                                    List.of(new CatalogAssets.Partition(
                                            "s3://lake/dwd/orders/dt=2026-10-30",
                                            "{\"dt\":\"2026-10-30\"}", 1L, 100L, null)))),
                            List.of("读取表 dwd.broken 失败")));
            when(namespaceMapper.selectOne(any())).thenReturn(null);
            when(datasetMapper.selectOne(any())).thenReturn(null);

            RefreshResult result = service.refresh("cat_hms");

            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.datasets()).isEqualTo(1);
            assertThat(result.columns()).isEqualTo(2);
            assertThat(result.warnings()).hasSize(1);
            verify(partitionMapper).insert(any(CatalogPartition.class));
        }

        @Test
        @DisplayName("HMS 认证失败 —— refresh 失败且不写任何资产")
        void hmsAuthFailureKeepsOldSnapshot() {
            CatalogSource source = hmsSource();
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(catalogGateway.readExternalCatalog(any()))
                    .thenThrow(new BizException(ErrorCode.DAT_AUTH_FAILED,
                            "Hive Metastore 认证失败"));

            assertThatThrownBy(() -> service.refresh("cat_hms"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.DAT_AUTH_FAILED));
            verify(namespaceMapper, never()).insert(any(CatalogNamespace.class));
            verify(datasetMapper, never()).insert(any(CatalogDataset.class));
            verify(runMapper).updateById(any(CatalogSnapshotRun.class));
        }

        @Test
        @DisplayName("Glue 配置了凭据但 accessKey/secretKey 为空 —— 明确失败，不回退匿名")
        void glueEmptyCredentialFails() {
            CatalogSource source = glueSource();
            source.setCredentialId("cred_empty");
            when(sourceMapper.selectOne(any())).thenReturn(source);
            when(credentialResolver.resolve(WORKSPACE, "cred_empty"))
                    .thenReturn(new com.datagov.metadata.spi.ResolvedSecret(
                            com.datagov.metadata.spi.ResolvedSecret.AuthType.BASIC,
                            null, null));

            assertThatThrownBy(() -> service.refresh("cat_glue"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.DAT_AUTH_FAILED));
            verify(catalogGateway, never()).readExternalCatalog(any());
        }
    }

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        @DisplayName("跨空间按 id 查数据集 —— 404")
        void crossWorkspaceDatasetNotFound() {
            WorkspaceContext.set(new Caller(USER, "alice", OTHER_WORKSPACE, false, Set.of()));
            when(datasetMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.getDataset("catds_1"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND));
        }

        @Test
        @DisplayName("数据集详情带字段")
        void datasetDetailIncludesColumns() {
            CatalogDataset dataset = dataset();
            CatalogColumn column = column();
            when(datasetMapper.selectOne(any())).thenReturn(dataset);
            when(columnMapper.selectList(any())).thenReturn(List.of(column));

            DatasetDetail detail = service.getDataset("catds_1");

            assertThat(detail.dataset().name()).isEqualTo("oa_user");
            assertThat(detail.columns()).hasSize(1);
            assertThat(detail.columns().get(0).name()).isEqualTo("id");
        }

        @Test
        @DisplayName("分页查询只返回当前空间的数据集")
        void listDatasetsScopedToWorkspace() {
            when(sourceMapper.selectOne(any())).thenReturn(source());
            var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                    com.datagov.metadata.entity.CatalogDatasetGovernanceRow>(1, 20);
            var row = new com.datagov.metadata.entity.CatalogDatasetGovernanceRow();
            row.setId("catds_1");
            row.setWorkspaceId(WORKSPACE);
            row.setSourceId("cat_1");
            row.setName("oa_user");
            row.setQualifiedName("ecology.oa_user");
            row.setKind("TABLE");
            row.setFormat("JDBC");
            row.setCertificationStatus("NONE");
            page.setRecords(List.of(row));
            page.setTotal(1);
            when(datasetMapper.selectGovernancePage(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            PageResult<CatalogAssetService.DatasetView> result =
                    service.listDatasets("cat_1", 1, 20, "oa");

            assertThat(result.total()).isEqualTo(1);
            assertThat(result.records()).hasSize(1);
            assertThat(result.records().get(0).certificationStatus()).isEqualTo("NONE");
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static CatalogSource source() {
        CatalogSource source = new CatalogSource();
        source.setId("cat_1");
        source.setWorkspaceId(WORKSPACE);
        source.setName("泛微 OA 目录");
        source.setSourceType("JDBC");
        source.setDataSourceId("ds_1");
        source.setStatus("ACTIVE");
        source.setCreatedAt(Instant.now());
        source.setCreatedBy(USER);
        source.setUpdatedAt(Instant.now());
        source.setUpdatedBy(USER);
        source.setDeleted(false);
        return source;
    }

    private static CatalogSource hmsSource() {
        CatalogSource source = new CatalogSource();
        source.setId("cat_hms");
        source.setWorkspaceId(WORKSPACE);
        source.setName("HMS 湖");
        source.setSourceType("HIVE_METASTORE");
        source.setConfigJson("{\"uris\":[\"thrift://hms:9083\"]}");
        source.setStatus("ACTIVE");
        source.setCreatedAt(Instant.now());
        source.setCreatedBy(USER);
        source.setUpdatedAt(Instant.now());
        source.setUpdatedBy(USER);
        source.setDeleted(false);
        return source;
    }

    private static CatalogSource glueSource() {
        CatalogSource source = new CatalogSource();
        source.setId("cat_glue");
        source.setWorkspaceId(WORKSPACE);
        source.setName("Glue 目录");
        source.setSourceType("GLUE");
        source.setConfigJson("{\"region\":\"cn-north-1\"}");
        source.setStatus("ACTIVE");
        source.setCreatedAt(Instant.now());
        source.setCreatedBy(USER);
        source.setUpdatedAt(Instant.now());
        source.setUpdatedBy(USER);
        source.setDeleted(false);
        return source;
    }

    private static DataSourceEntity mysqlDataSource() {
        DataSourceEntity dataSource = new DataSourceEntity();
        dataSource.setId("ds_1");
        dataSource.setWorkspaceId(WORKSPACE);
        dataSource.setName("泛微 e-cology");
        dataSource.setType(DataSourceType.MYSQL);
        dataSource.setFamily(DataSourceType.Family.RELATIONAL);
        dataSource.setStatus(DataSourceStatus.AVAILABLE);
        dataSource.setDatabaseName("ecology");
        return dataSource;
    }

    private static CatalogPage catalogPage(CatalogPath path) {
        return switch (path.level()) {
            case ROOT -> CatalogPage.ofDatabases(List.of(
                    new DatabaseInfo("ecology", null, null, null)));
            case DATABASE -> CatalogPage.ofTables(List.of(
                    new TableInfo("oa_user", TableKind.TABLE, "用户表", null, null, null)));
            case TABLE -> CatalogPage.ofColumns(List.of(
                    new ColumnInfo("id", "BIGINT", CanonicalType.BIGINT, null, null,
                            false, true, null, "主键", 1),
                    new ColumnInfo("name", "VARCHAR(64)", CanonicalType.VARCHAR, 64, null,
                            true, false, null, "姓名", 2)));
            default -> throw new AssertionError("unexpected path " + path);
        };
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setSourceId("cat_1");
        dataset.setName("oa_user");
        dataset.setQualifiedName("ecology.oa_user");
        dataset.setKind("TABLE");
        dataset.setFormat("JDBC");
        dataset.setSchemaHash("a".repeat(64));
        dataset.setColumnCount(1);
        dataset.setUpdatedAt(Instant.now());
        dataset.setDeleted(false);
        return dataset;
    }

    private static CatalogColumn column() {
        CatalogColumn column = new CatalogColumn();
        column.setId("catcol_1");
        column.setDatasetId("catds_1");
        column.setName("id");
        column.setOrdinal(1);
        column.setRawType("BIGINT");
        column.setCanonicalType("BIGINT");
        column.setNullable(false);
        column.setPrimaryKey(true);
        return column;
    }

    private static DataSourceEntity parquetDataSource() {
        DataSourceEntity dataSource = new DataSourceEntity();
        dataSource.setId("ds_pq");
        dataSource.setWorkspaceId(WORKSPACE);
        dataSource.setName("Parquet 湖");
        dataSource.setType(DataSourceType.LOCAL);
        dataSource.setFamily(DataSourceType.Family.OBJECT_STORAGE);
        dataSource.setStatus(DataSourceStatus.AVAILABLE);
        dataSource.setDatabaseName("/lake");
        return dataSource;
    }

    private static CatalogAssets.Snapshot parquetSnapshot() {
        CatalogAssets.Namespace namespace = new CatalogAssets.Namespace(
                "/lake", "lake", "DIRECTORY", null);
        CatalogAssets.Column id = new CatalogAssets.Column(
                "id", 1, "BIGINT", CanonicalType.BIGINT, null, null,
                false, false, false, null);
        CatalogAssets.Column dt = new CatalogAssets.Column(
                "dt", 2, "DATE", CanonicalType.DATE, null, null,
                true, false, true, null);
        CatalogAssets.Partition partition = new CatalogAssets.Partition(
                "/lake/orders/dt=2026-10-27", "{\"dt\":\"2026-10-27\"}",
                1L, 100L, null);
        CatalogAssets.Dataset dataset = new CatalogAssets.Dataset(
                "/lake", "orders", "orders", "PARQUET_DATASET", "PARQUET",
                "/lake/orders", 10L, 100L, null, List.of("dt"),
                List.of(id, dt), List.of(partition));
        return new CatalogAssets.Snapshot(List.of(namespace), List.of(dataset));
    }
}
