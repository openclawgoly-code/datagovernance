package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.metadata.config.CatalogProperties;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogSnapshotEntity;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.CatalogSnapshotMapper;
import com.datagov.metadata.spi.CredentialResolver;
import com.datagov.metadata.spi.ResolvedSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("库表结构浏览")
class CatalogServiceTest {

    private static final String WORKSPACE = "ws_alpha";

    private CatalogSnapshotMapper snapshotMapper;
    private DataSourceService dataSourceService;
    private DataAccessGateway gateway;
    private CatalogProperties properties;
    private CatalogService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(CatalogSnapshotMapper.class);
        dataSourceService = mock(DataSourceService.class);
        gateway = mock(DataAccessGateway.class);
        properties = new CatalogProperties();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        CredentialResolver resolver = (ws, id) -> ResolvedSecret.none();
        ConnectionConfigAssembler assembler = new ConnectionConfigAssembler(resolver, objectMapper);

        service = new CatalogService(snapshotMapper, dataSourceService, gateway,
                assembler, properties, objectMapper);

        WorkspaceContext.set(new Caller("usr_1", "alice", WORKSPACE, false, Set.of()));
        when(snapshotMapper.insert((CatalogSnapshotEntity) any())).thenReturn(1);
        when(snapshotMapper.updateById((CatalogSnapshotEntity) any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("未通过连通性验证的数据源不允许浏览结构")
    void rejectsBrowsingUnverifiedDataSource() {
        // 没验证过就去打生产库是不负责任的:配置可能指向一台完全无关的机器,
        // 而结构浏览会真的连上去执行元数据查询。
        for (DataSourceStatus status : List.of(DataSourceStatus.DRAFT, DataSourceStatus.TESTING,
                DataSourceStatus.UNREACHABLE, DataSourceStatus.DISABLED, DataSourceStatus.ARCHIVED)) {

            DataSourceEntity entity = entity(status);
            when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity);

            assertThatThrownBy(() -> service.browse("ds_1", CatalogPath.root(), false))
                    .as("状态 %s 应被拒绝", status)
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo("MTD_DATASOURCE_NOT_ACTIVE"));
        }

        verify(gateway, never()).browse(any(), any(), any());
    }

    @Test
    @DisplayName("AVAILABLE 状态可浏览,首次访问打目标库并落快照")
    void firstBrowseProbesAndSnapshots() {
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(gateway.browse(any(), any(), any())).thenReturn(page("appdb"));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), false);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("appdb");
        verify(gateway, times(1)).browse(any(), any(), any());
        verify(snapshotMapper).insert((CatalogSnapshotEntity) any());
    }

    @Test
    @DisplayName("TTL 内的快照直接返回,不再打目标库")
    void freshSnapshotShortCircuitsProbe() {
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any())).thenReturn(snapshot(Instant.now(), page("cached_db")));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), false);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("cached_db");
        // 界面上展开一次树节点就打一次生产库,用户手快点几下就是一串元数据查询
        verify(gateway, never()).browse(any(), any(), any());
    }

    @Test
    @DisplayName("快照过期后重新探测")
    void staleSnapshotTriggersReprobe() {
        properties.setSnapshotTtlMinutes(30);
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any()))
                .thenReturn(snapshot(Instant.now().minus(31, ChronoUnit.MINUTES), page("stale_db")));
        when(gateway.browse(any(), any(), any())).thenReturn(page("fresh_db"));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), false);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("fresh_db");
        verify(gateway, times(1)).browse(any(), any(), any());
        // 同一路径覆盖而非新增,否则快照表会无限增长
        verify(snapshotMapper).updateById((CatalogSnapshotEntity) any());
    }

    @Test
    @DisplayName("refresh=true 跳过未过期的快照,强制重新探测")
    void refreshBypassesCache() {
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any())).thenReturn(snapshot(Instant.now(), page("cached_db")));
        when(gateway.browse(any(), any(), any())).thenReturn(page("fresh_db"));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), true);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("fresh_db");
        verify(gateway, times(1)).browse(any(), any(), any());
    }

    @Test
    @DisplayName("TTL 设为 0 表示不缓存,每次实时探测")
    void zeroTtlDisablesCaching() {
        properties.setSnapshotTtlMinutes(0);
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any())).thenReturn(snapshot(Instant.now(), page("cached_db")));
        when(gateway.browse(any(), any(), any())).thenReturn(page("live_db"));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), false);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("live_db");
    }

    @Test
    @DisplayName("快照内容损坏时退化为实时探测,而不是让功能不可用")
    void corruptSnapshotFallsBackToLiveProbe() {
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        CatalogSnapshotEntity corrupt = snapshot(Instant.now(), page("x"));
        corrupt.setPayloadJson("{ 这不是合法 JSON");
        when(snapshotMapper.selectOne(any())).thenReturn(corrupt);
        when(gateway.browse(any(), any(), any())).thenReturn(page("live_db"));

        CatalogPage result = service.browse("ds_1", CatalogPath.root(), false);

        assertThat(result.databases()).extracting(DatabaseInfo::name).containsExactly("live_db");
    }

    @Test
    @DisplayName("path 为 null 时按根路径处理,不抛空指针")
    void nullPathTreatedAsRoot() {
        when(dataSourceService.requireInWorkspace("ds_1")).thenReturn(entity(DataSourceStatus.AVAILABLE));
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(gateway.browse(any(), any(), any())).thenReturn(page("appdb"));

        assertThat(service.browse("ds_1", null, false).databases()).hasSize(1);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static DataSourceEntity entity(DataSourceStatus status) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId("ds_1");
        entity.setWorkspaceId(WORKSPACE);
        entity.setName("订单库");
        entity.setType(DataSourceType.MYSQL);
        entity.setStatus(status);
        entity.setHost("10.0.0.1");
        entity.setPort(3306);
        entity.setDatabaseName("appdb");
        return entity;
    }

    private static CatalogPage page(String databaseName) {
        return CatalogPage.ofDatabases(List.of(new DatabaseInfo(databaseName, null, null, null)));
    }

    private CatalogSnapshotEntity snapshot(Instant capturedAt, CatalogPage page) {
        CatalogSnapshotEntity entity = new CatalogSnapshotEntity();
        entity.setId("cat_1");
        entity.setWorkspaceId(WORKSPACE);
        entity.setDatasourceId("ds_1");
        entity.setLevel(CatalogPath.Level.ROOT.name());
        entity.setCapturedAt(capturedAt);
        try {
            entity.setPayloadJson(objectMapper.writeValueAsString(page));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }
}
