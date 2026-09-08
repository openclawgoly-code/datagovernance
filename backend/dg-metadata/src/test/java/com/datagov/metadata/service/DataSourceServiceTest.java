package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import com.datagov.metadata.dto.DataSourceView;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.DataSourceVersionEntity;
import com.datagov.metadata.event.DataSourceEvents;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.mapper.DataSourceVersionMapper;
import com.datagov.metadata.spi.CredentialResolver;
import com.datagov.metadata.spi.ResolvedSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("数据源服务")
class DataSourceServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String OTHER_WORKSPACE = "ws_beta";
    private static final String USER = "usr_1";

    private DataSourceMapper dataSourceMapper;
    private DataSourceVersionMapper versionMapper;
    private DataAccessGateway gateway;
    private RecordingPublisher publisher;
    private DataSourceService service;

    @BeforeEach
    void setUp() {
        dataSourceMapper = mock(DataSourceMapper.class);
        versionMapper = mock(DataSourceVersionMapper.class);
        gateway = mock(DataAccessGateway.class);
        publisher = new RecordingPublisher();

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // 用真实的 Assembler(只把凭据解析打桩),这样 properties 的序列化往返
        // 与 ConnectionConfig 的组装逻辑也一并被测到
        CredentialResolver credentialResolver = (workspaceId, credentialId) ->
                credentialId == null
                        ? ResolvedSecret.none()
                        : new ResolvedSecret(ResolvedSecret.AuthType.PASSWORD, "dbuser", "s3cret");
        ConnectionConfigAssembler assembler = new ConnectionConfigAssembler(credentialResolver, objectMapper);

        service = new DataSourceService(dataSourceMapper, versionMapper, gateway,
                assembler, publisher, objectMapper);

        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, java.util.Set.of()));
        when(dataSourceMapper.selectCount(any())).thenReturn(0L);
        when(dataSourceMapper.insert((DataSourceEntity) any())).thenReturn(1);
        when(dataSourceMapper.updateById((DataSourceEntity) any())).thenReturn(1);
        when(versionMapper.insert((DataSourceVersionEntity) any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    // ── 新建 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("新建后处于 DRAFT,版本为 1,并发布 DataSourceRegistered")
    void createStartsInDraft() {
        DataSourceView view = service.create(mysqlCommand("订单库"));

        assertThat(view.status()).isEqualTo(DataSourceStatus.DRAFT);
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.id()).startsWith("ds_");
        assertThat(view.family()).isEqualTo(DataSourceType.Family.RELATIONAL);

        assertThat(publisher.eventsOfType(DataSourceEvents.DataSourceRegistered.class)).hasSize(1);
        verify(versionMapper).insert((DataSourceVersionEntity) any());
    }

    @Test
    @DisplayName("同空间下名称重复被拒")
    void duplicateNameRejected() {
        when(dataSourceMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(mysqlCommand("订单库")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_DATASOURCE_NAME_DUPLICATED"));
    }

    @Test
    @DisplayName("缺少必填连接参数在保存前就被拒,而不是等到测连接")
    void missingRequiredFieldsRejectedBeforePersist() {
        DataSourceUpsertCommand noHost = new DataSourceUpsertCommand(
                "坏配置", DataSourceType.MYSQL, null, null,
                null, 3306, "db", "u", Map.of(), null, null, null, null, null);

        assertThatThrownBy(() -> service.create(noHost))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主机地址");

        // 关键:不合法的配置根本不该落库,否则它会以 DRAFT 状态一直躺在列表里
        verify(dataSourceMapper, never()).insert((DataSourceEntity) any());
    }

    @Test
    @DisplayName("RestAPI 类型必须有 baseUrl")
    void restApiRequiresBaseUrl() {
        DataSourceUpsertCommand noBaseUrl = new DataSourceUpsertCommand(
                "接口", DataSourceType.REST_API, null, null,
                null, null, null, null, Map.of(), null, null, null, null, null);

        assertThatThrownBy(() -> service.create(noBaseUrl))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("baseUrl");
    }

    // ── 空间隔离 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("查不到别的空间的数据源,且不泄露它是否存在")
    void workspaceIsolationOnRead() {
        // 服务层查询恒带 workspace_id 过滤,别的空间的记录 selectOne 返回 null
        when(dataSourceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get("ds_belongs_to_beta"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_DATASOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("未设置空间时直接拒绝,不会退化成全表操作")
    void missingWorkspaceIsRejected() {
        WorkspaceContext.set(new Caller(USER, "alice", null, false, java.util.Set.of()));

        assertThatThrownBy(() -> service.create(mysqlCommand("x")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_MISSING_WORKSPACE"));
    }

    // ── 修改 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("改连接参数把 AVAILABLE 打回 DRAFT,并清空上次测试结论")
    void changingConnectionResetsToDraft() {
        DataSourceEntity existing = availableEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(existing);

        DataSourceUpsertCommand changed = new DataSourceUpsertCommand(
                existing.getName(), DataSourceType.MYSQL, null, null,
                "new-host", 3306, "db", "u", Map.of(), null, null, null, null, null);

        DataSourceView view = service.update(existing.getId(), changed);

        assertThat(view.status()).isEqualTo(DataSourceStatus.DRAFT);
        assertThat(view.version()).isEqualTo(2);
        // 上次的"连接成功"属于旧配置,留着会误导用户
        assertThat(view.lastTestAt()).isNull();
        assertThat(view.lastTestSuccess()).isNull();
    }

    @Test
    @DisplayName("只改名称或描述不影响已验证状态")
    void renamingKeepsVerifiedStatus() {
        DataSourceEntity existing = availableEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(existing);

        DataSourceUpsertCommand renamed = new DataSourceUpsertCommand(
                "改个名字", DataSourceType.MYSQL, "补个描述", null,
                existing.getHost(), existing.getPort(), existing.getDatabaseName(),
                existing.getUsername(), Map.of(), null, null, null, null, null);

        DataSourceView view = service.update(existing.getId(), renamed);

        // 为了改个错别字就要重测一遍连接,是没有道理的
        assertThat(view.status()).isEqualTo(DataSourceStatus.AVAILABLE);
        assertThat(view.lastTestSuccess()).isTrue();
    }

    @Test
    @DisplayName("不允许变更数据源类型")
    void typeChangeRejected() {
        DataSourceEntity existing = availableEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(existing);

        DataSourceUpsertCommand retyped = new DataSourceUpsertCommand(
                existing.getName(), DataSourceType.POSTGRESQL, null, null,
                existing.getHost(), 5432, "db", "u", Map.of(), null, null, null, null, null);

        assertThatThrownBy(() -> service.update(existing.getId(), retyped))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类型不可变更");
    }

    // ── 连通性测试 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("测试成功 → AVAILABLE,并写回耗时与版本信息")
    void successfulTestMakesAvailable() {
        DataSourceEntity draft = draftEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(draft);
        when(gateway.testConnection(eq(DataSourceType.MYSQL), any(ConnectionConfig.class)))
                .thenReturn(ConnectivityResult.success(42L, "MySQL 8.0.35"));

        ConnectivityResult result = service.testConnection(draft.getId(), false);

        assertThat(result.success()).isTrue();
        assertThat(draft.getStatus()).isEqualTo(DataSourceStatus.AVAILABLE);
        assertThat(draft.getLastTestSuccess()).isTrue();
        assertThat(draft.getLastTestLatencyMs()).isEqualTo(42L);
        assertThat(publisher.eventsOfType(DataSourceEvents.DataSourceBecameAvailable.class)).hasSize(1);
    }

    @Test
    @DisplayName("手工测试失败 → 回 DRAFT,且不发不可达事件")
    void manualFailureGoesBackToDraft() {
        DataSourceEntity draft = draftEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(draft);
        when(gateway.testConnection(any(), any())).thenReturn(
                ConnectivityResult.failure(com.datagov.common.error.ErrorCode.DAT_AUTH_FAILED,
                        10L, "认证失败", "SQLState=28000"));

        service.testConnection(draft.getId(), false);

        assertThat(draft.getStatus()).isEqualTo(DataSourceStatus.DRAFT);
        // 用户手工测一个还没配好的草稿失败了,不该触发任何人的电话
        assertThat(publisher.eventsOfType(DataSourceEvents.DataSourceUnreachable.class)).isEmpty();
    }

    @Test
    @DisplayName("周期探测失败把 AVAILABLE 打成 UNREACHABLE,并发出不可达事件")
    void probeFailureRaisesUnreachableEvent() {
        DataSourceEntity available = availableEntity();
        when(dataSourceMapper.selectOne(any())).thenReturn(available);
        when(gateway.testConnection(any(), any())).thenReturn(
                ConnectivityResult.failure(com.datagov.common.error.ErrorCode.DAT_CONNECT_FAILED,
                        3000L, "无法连接", "connection refused"));

        service.testConnection(available.getId(), true);

        assertThat(available.getStatus()).isEqualTo(DataSourceStatus.UNREACHABLE);
        // 告警与否由 Governance 按 AlertRule 决定,Metadata 只负责陈述事实
        assertThat(publisher.eventsOfType(DataSourceEvents.DataSourceUnreachable.class)).hasSize(1);
    }

    @Test
    @DisplayName("已归档的数据源不可测试、不可修改")
    void archivedIsImmutable() {
        DataSourceEntity archived = draftEntity();
        archived.setStatus(DataSourceStatus.ARCHIVED);
        when(dataSourceMapper.selectOne(any())).thenReturn(archived);

        assertThatThrownBy(() -> service.testConnection(archived.getId(), false))
                .isInstanceOf(BizException.class).hasMessageContaining("已归档");
        assertThatThrownBy(() -> service.update(archived.getId(), mysqlCommand("x")))
                .isInstanceOf(BizException.class).hasMessageContaining("已归档");
    }

    // ── 凭据边界 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("对外视图与版本快照都不含任何口令字段")
    void neitherViewNorVersionSnapshotCarriesSecrets() throws Exception {
        DataSourceUpsertCommand withCredential = new DataSourceUpsertCommand(
                "带凭据的库", DataSourceType.MYSQL, null, null,
                "h", 3306, "db", "appuser", Map.of("useSSL", "false"),
                null, null, "cred_123", null, null);

        service.create(withCredential);

        // 版本快照存的是对外视图。数据源实体本身就没有口令字段,
        // 所以这份快照天然安全 —— 不需要额外的脱敏步骤。
        var captor = org.mockito.ArgumentCaptor.forClass(DataSourceVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        String snapshot = captor.getValue().getSnapshotJson();

        assertThat(snapshot).doesNotContain("s3cret");
        assertThat(snapshot).doesNotContainIgnoringCase("password");
        assertThat(snapshot).contains("cred_123");   // 只有引用
    }

    @Test
    @DisplayName("未保存试连不落库、不改状态")
    void transientTestPersistsNothing() {
        when(gateway.testConnection(any(), any()))
                .thenReturn(ConnectivityResult.success(5L, "MySQL 8"));

        ConnectivityResult result = service.testTransient(mysqlCommand("临时"));

        assertThat(result.success()).isTrue();
        verify(dataSourceMapper, never()).insert((DataSourceEntity) any());
        verify(dataSourceMapper, never()).updateById((DataSourceEntity) any());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static DataSourceUpsertCommand mysqlCommand(String name) {
        return new DataSourceUpsertCommand(name, DataSourceType.MYSQL, "测试用", null,
                "10.0.0.1", 3306, "appdb", "appuser", Map.of(),
                null, null, null, null, null);
    }

    private static DataSourceEntity draftEntity() {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId("ds_1");
        entity.setWorkspaceId(WORKSPACE);
        entity.setName("订单库");
        entity.setType(DataSourceType.MYSQL);
        entity.setFamily(DataSourceType.Family.RELATIONAL);
        entity.setStatus(DataSourceStatus.DRAFT);
        entity.setHost("10.0.0.1");
        entity.setPort(3306);
        entity.setDatabaseName("appdb");
        entity.setUsername("appuser");
        entity.setVersion(1);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeleted(false);
        return entity;
    }

    private static DataSourceEntity availableEntity() {
        DataSourceEntity entity = draftEntity();
        entity.setStatus(DataSourceStatus.AVAILABLE);
        entity.setLastTestAt(Instant.now());
        entity.setLastTestSuccess(true);
        entity.setLastTestMessage("连接成功");
        entity.setLastTestLatencyMs(20L);
        return entity;
    }

    /** 记录发布过的事件,比 mock 更容易做"发了什么/没发什么"的断言。 */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        <T> List<T> eventsOfType(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
