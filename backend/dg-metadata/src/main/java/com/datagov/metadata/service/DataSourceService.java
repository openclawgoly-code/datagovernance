package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.ConnectionRequirements;
import com.datagov.metadata.domain.DataSourceLifecycle;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import com.datagov.metadata.dto.DataSourceView;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.DataSourceVersionEntity;
import com.datagov.metadata.event.DataSourceEvents;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.mapper.DataSourceVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 数据源定义服务(功能 1-5、8)。
 *
 * <p><b>空间隔离</b>:所有读写一律以 {@link WorkspaceContext#requireWorkspaceId()} 为过滤条件,
 * 没有例外。这是 P1 完成判据「按空间隔离」的落点,也是为什么每个方法都从
 * 上下文取 workspaceId 而不是从参数接收 —— 参数可以被调用方伪造,上下文不能。
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceVersionMapper versionMapper;
    private final DataAccessGateway gateway;
    private final ConnectionConfigAssembler assembler;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public DataSourceService(DataSourceMapper dataSourceMapper,
                             DataSourceVersionMapper versionMapper,
                             DataAccessGateway gateway,
                             ConnectionConfigAssembler assembler,
                             ApplicationEventPublisher events,
                             ObjectMapper objectMapper) {
        this.dataSourceMapper = dataSourceMapper;
        this.versionMapper = versionMapper;
        this.gateway = gateway;
        this.assembler = assembler;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public DataSourceView get(String id) {
        DataSourceEntity entity = requireInWorkspace(id);
        return toView(entity);
    }

    /** 供内部编排使用(如 CatalogService),不做 DTO 转换。 */
    DataSourceEntity requireInWorkspace(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        DataSourceEntity entity = dataSourceMapper.selectOne(
                new LambdaQueryWrapper<DataSourceEntity>()
                        .eq(DataSourceEntity::getId, id)
                        .eq(DataSourceEntity::getWorkspaceId, workspaceId));
        if (entity == null) {
            // 刻意不区分"不存在"与"在别的空间" —— 后者会泄露其他空间存在该 ID 的事实
            throw BizException.notFound(ErrorCode.MTD_DATASOURCE_NOT_FOUND, id);
        }
        return entity;
    }

    public PageResult<DataSourceView> list(long page, long size, DataSourceType type, String keyword) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();

        LambdaQueryWrapper<DataSourceEntity> query = new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getWorkspaceId, workspaceId)
                .eq(type != null, DataSourceEntity::getType, type)
                .like(keyword != null && !keyword.isBlank(), DataSourceEntity::getName, keyword)
                .orderByDesc(DataSourceEntity::getUpdatedAt);

        Page<DataSourceEntity> result = dataSourceMapper.selectPage(new Page<>(page, size), query);
        List<DataSourceView> views = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    public List<DataSourceVersionEntity> listVersions(String id) {
        requireInWorkspace(id);   // 越权校验
        return versionMapper.selectList(new LambdaQueryWrapper<DataSourceVersionEntity>()
                .eq(DataSourceVersionEntity::getDatasourceId, id)
                .orderByDesc(DataSourceVersionEntity::getVersion));
    }

    // ── 命令 ────────────────────────────────────────────────────────────

    /** RegisterDataSource */
    @Transactional
    public DataSourceView create(DataSourceUpsertCommand command) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        ConnectionRequirements.validate(command);
        requireNameAvailable(workspaceId, command.name(), null);

        Instant now = Instant.now();
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(Ids.of("ds"));
        entity.setWorkspaceId(workspaceId);
        entity.setStatus(DataSourceLifecycle.MACHINE.initial());
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);
        entity.setDeleted(false);
        applyCommand(entity, command);

        dataSourceMapper.insert(entity);
        recordVersion(entity, "CREATED", "新建数据源", operator);

        events.publishEvent(new DataSourceEvents.DataSourceRegistered(
                workspaceId, entity.getId(), entity.getName(), entity.getType().name(), now));

        log.info("数据源已注册 workspace={} id={} name={} type={}",
                workspaceId, entity.getId(), entity.getName(), entity.getType());
        return toView(entity);
    }

    /**
     * UpdateDataSource。
     *
     * <p>改了连接参数会把状态打回 {@link DataSourceStatus#DRAFT} —— 上一次验证的结论
     * 对新参数不再成立。只改名称或描述则不动状态,免得为了改个错别字重测一遍连接。
     */
    @Transactional
    public DataSourceView update(String id, DataSourceUpsertCommand command) {
        DataSourceEntity entity = requireInWorkspace(id);
        String workspaceId = entity.getWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        if (entity.getStatus() == DataSourceStatus.ARCHIVED) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "已归档的数据源不可修改");
        }
        if (command.type() != entity.getType()) {
            // 换类型等于换了一个东西:连接参数语义、能力、类型映射全变。
            // 允许改会让版本历史里出现"同一个 ID 前后是两种数据源"的荒谬记录。
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "数据源类型不可变更,请新建数据源。当前 %s,请求 %s"
                            .formatted(entity.getType(), command.type()));
        }

        ConnectionRequirements.validate(command);
        requireNameAvailable(workspaceId, command.name(), id);

        String newPropertiesJson = assembler.writeProperties(command.properties());
        boolean connectionChanged = ConnectionRequirements.connectionChanged(
                command, entity.getHost(), entity.getPort(), entity.getDatabaseName(),
                entity.getUsername(), entity.getPropertiesJson(), entity.getJdbcUrlOverride(),
                entity.getBaseUrl(), entity.getCredentialId(), newPropertiesJson);

        applyCommand(entity, command);
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(operator);

        if (connectionChanged && entity.getStatus() != DataSourceStatus.DRAFT
                && entity.getStatus() != DataSourceStatus.DISABLED) {
            transitionTo(entity, DataSourceStatus.DRAFT, "UpdateConnection");
            // 上次测试结论对新配置无效,一并清掉,免得界面上显示一个属于旧配置的"连接成功"
            entity.setLastTestAt(null);
            entity.setLastTestSuccess(null);
            entity.setLastTestMessage(null);
            entity.setLastTestLatencyMs(null);
        }

        dataSourceMapper.updateById(entity);
        recordVersion(entity, "UPDATED",
                connectionChanged ? "修改连接参数,状态重置为待验证" : "修改基本信息", operator);

        events.publishEvent(new DataSourceEvents.DataSourceUpdated(
                workspaceId, entity.getId(), entity.getVersion(),
                connectionChanged, entity.getStatus(), Instant.now()));

        return toView(entity);
    }

    /**
     * TestConnectivity —— 功能 1-4 的「测试连接」。
     *
     * @param probe true 表示来自周期探测(功能 6),false 表示用户手工触发。
     *              两者失败后的落点不同:探测失败进 UNREACHABLE(本来是好的),
     *              手工失败回 DRAFT(配置可能就没配对)。
     */
    @Transactional
    public ConnectivityResult testConnection(String id, boolean probe) {
        DataSourceEntity entity = requireInWorkspace(id);
        String workspaceId = entity.getWorkspaceId();

        if (entity.getStatus() == DataSourceStatus.ARCHIVED) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE, "已归档的数据源不可测试");
        }

        DataSourceStatus before = entity.getStatus();
        // 手工测试要经过 TESTING 中间态,让并发的查看者知道"正在测";
        // 周期探测是后台行为,不该让用户在列表里看到状态来回跳。
        if (!probe && DataSourceLifecycle.MACHINE.canTransition(before, DataSourceStatus.TESTING)) {
            transitionTo(entity, DataSourceStatus.TESTING, "TestConnectivity");
            dataSourceMapper.updateById(entity);
        }

        ConnectionConfig config = assembler.assemble(entity, workspaceId);
        ConnectivityResult result = gateway.testConnection(entity.getType(), config);

        DataSourceStatus target = DataSourceLifecycle.afterConnectivityResult(
                entity.getStatus(), result.success(), probe);
        applyTestOutcome(entity, result, target,
                result.success() ? "ConnectivitySucceeded" : (probe ? "ProbeFailed" : "ConnectivityFailed"));

        dataSourceMapper.updateById(entity);
        publishOutcomeEvent(entity, before, result, probe);
        return result;
    }

    /** 未保存时的试连:直接用传入的配置,不落库、不改状态。 */
    public ConnectivityResult testTransient(DataSourceUpsertCommand command) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        ConnectionRequirements.validate(command);

        DataSourceEntity probe = new DataSourceEntity();
        probe.setWorkspaceId(workspaceId);
        applyCommand(probe, command);

        return gateway.testConnection(command.type(), assembler.assemble(probe, workspaceId));
    }

    /** DisableDataSource */
    @Transactional
    public DataSourceView disable(String id) {
        DataSourceEntity entity = requireInWorkspace(id);
        String operator = WorkspaceContext.require().userId();

        transitionTo(entity, DataSourceStatus.DISABLED, "DisableDataSource");
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(operator);
        dataSourceMapper.updateById(entity);
        recordVersion(entity, "DISABLED", "停用数据源", operator);

        events.publishEvent(new DataSourceEvents.DataSourceDisabled(
                entity.getWorkspaceId(), entity.getId(), operator, Instant.now()));
        return toView(entity);
    }

    /**
     * EnableDataSource —— 回到 DRAFT 而非直接可用。
     *
     * <p>停用期间目标端可能已经变了(库被删、账号被回收、防火墙改了),
     * 直接恢复成 AVAILABLE 等于宣称一个从未验证过的结论。
     */
    @Transactional
    public DataSourceView enable(String id) {
        DataSourceEntity entity = requireInWorkspace(id);
        String operator = WorkspaceContext.require().userId();

        transitionTo(entity, DataSourceStatus.DRAFT, "EnableDataSource");
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(operator);
        dataSourceMapper.updateById(entity);
        recordVersion(entity, "STATUS_CHANGED", "重新启用,需重新验证连通性", operator);
        return toView(entity);
    }

    /** Archive —— 终态。 */
    @Transactional
    public DataSourceView archive(String id) {
        DataSourceEntity entity = requireInWorkspace(id);
        String operator = WorkspaceContext.require().userId();

        transitionTo(entity, DataSourceStatus.ARCHIVED, "Archive");
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(operator);
        dataSourceMapper.updateById(entity);
        recordVersion(entity, "STATUS_CHANGED", "归档", operator);
        return toView(entity);
    }

    /**
     * 删除。
     *
     * <p>{@code SPACE-MODEL.md} E.1:「被任何 JobDef 引用的数据源不允许删除,
     * 只允许 DISABLED」。P1 还没有 JobDef,引用检查是空的 —— 但检查点必须现在就留下,
     * 否则 P2 加任务定义时很容易忘了补,而那时故障表现是"删掉数据源后任务全挂"。
     */
    @Transactional
    public void delete(String id) {
        DataSourceEntity entity = requireInWorkspace(id);
        assertNotReferenced(entity);
        dataSourceMapper.deleteById(entity.getId());
        log.info("数据源已删除 workspace={} id={}", entity.getWorkspaceId(), id);
    }

    /**
     * 引用检查占位。P2 引入 JobDef 后在此查询引用关系(序号 8 的关联信息)。
     */
    private void assertNotReferenced(DataSourceEntity entity) {
        // P1 无 JobDef 表,恒为无引用。P2 实现:
        //   若存在引用 → throw BizException(MTD_DATASOURCE_IN_USE, "被 N 个任务引用,请改为停用")
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void applyCommand(DataSourceEntity entity, DataSourceUpsertCommand command) {
        entity.setName(command.name().trim());
        entity.setType(command.type());
        entity.setFamily(command.type().family());
        entity.setDescription(command.description());
        entity.setHost(command.host());
        entity.setPort(command.port());
        entity.setDatabaseName(command.databaseName());
        entity.setUsername(command.username());
        entity.setPropertiesJson(assembler.writeProperties(command.properties()));
        entity.setJdbcUrlOverride(command.jdbcUrlOverride());
        entity.setBaseUrl(command.baseUrl());
        entity.setCredentialId(command.credentialId());
        entity.setConnectTimeoutMs(command.connectTimeoutMs());
        entity.setReadTimeoutMs(command.readTimeoutMs());
    }

    /** 所有状态变更的唯一入口 —— 非法迁移在这里被拦下,而不是散落在各处 if。 */
    private void transitionTo(DataSourceEntity entity, DataSourceStatus target, String trigger) {
        DataSourceLifecycle.MACHINE.checkTransition(entity.getStatus(), target);
        log.debug("数据源状态迁移 id={} {} -> {} ({})",
                entity.getId(), entity.getStatus(), target, trigger);
        entity.setStatus(target);
    }

    private void applyTestOutcome(DataSourceEntity entity, ConnectivityResult result,
                                  DataSourceStatus target, String trigger) {
        if (entity.getStatus() != target) {
            transitionTo(entity, target, trigger);
        }
        entity.setLastTestAt(Instant.now());
        entity.setLastTestSuccess(result.success());
        entity.setLastTestMessage(truncate(result.message(), 1024));
        entity.setLastTestLatencyMs(result.latencyMillis());
        entity.setUpdatedAt(Instant.now());
    }

    private void publishOutcomeEvent(DataSourceEntity entity, DataSourceStatus before,
                                     ConnectivityResult result, boolean probe) {
        Instant now = Instant.now();
        if (result.success()) {
            if (before != DataSourceStatus.AVAILABLE) {
                events.publishEvent(new DataSourceEvents.DataSourceBecameAvailable(
                        entity.getWorkspaceId(), entity.getId(), result.latencyMillis(), now));
            }
            return;
        }
        // 只有周期探测把一个原本可用的数据源打成不可达,才值得 Governance 考虑告警;
        // 用户手工测一个还没配好的草稿失败了,不该触发任何人的电话。
        if (probe && entity.getStatus() == DataSourceStatus.UNREACHABLE) {
            events.publishEvent(new DataSourceEvents.DataSourceUnreachable(
                    entity.getWorkspaceId(), entity.getId(), entity.getName(),
                    result.message(), now));
        }
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = dataSourceMapper.selectCount(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getWorkspaceId, workspaceId)
                .eq(DataSourceEntity::getName, name.trim())
                .ne(excludeId != null, DataSourceEntity::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.MTD_DATASOURCE_NAME_DUPLICATED, name);
        }
    }

    private void recordVersion(DataSourceEntity entity, String changeType,
                               String summary, String operator) {
        DataSourceVersionEntity version = new DataSourceVersionEntity();
        version.setId(Ids.of("dsv"));
        version.setDatasourceId(entity.getId());
        version.setWorkspaceId(entity.getWorkspaceId());
        version.setVersion(entity.getVersion());
        version.setChangeType(changeType);
        version.setChangeSummary(summary);
        version.setChangedAt(Instant.now());
        version.setChangedBy(operator);
        try {
            // 快照存的是对外视图,不是实体 —— 视图天然不含凭据,不需要额外脱敏
            version.setSnapshotJson(objectMapper.writeValueAsString(toView(entity)));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "版本快照序列化失败", e.getMessage(), e);
        }
        versionMapper.insert(version);
    }

    private DataSourceView toView(DataSourceEntity entity) {
        Map<String, String> properties = assembler.parseProperties(entity.getPropertiesJson());
        return DataSourceView.from(entity, properties);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
