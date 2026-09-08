package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.catalog.CatalogModel;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogPath;
import com.datagov.metadata.config.CatalogProperties;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogSnapshotEntity;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.CatalogSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库表结构浏览(功能 5)。
 *
 * <p>读路径是「先看快照,过期或强刷才打目标库」。这么做不只是省一次往返:
 * 结构浏览是个界面上一点就触发的动作,如果每次展开树节点都直连生产库,
 * 一个用户手快点几下就能给对方 DBA 制造一串元数据查询。
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogSnapshotMapper snapshotMapper;
    private final DataSourceService dataSourceService;
    private final DataAccessGateway gateway;
    private final ConnectionConfigAssembler assembler;
    private final CatalogProperties properties;
    private final ObjectMapper objectMapper;

    public CatalogService(CatalogSnapshotMapper snapshotMapper,
                          DataSourceService dataSourceService,
                          DataAccessGateway gateway,
                          ConnectionConfigAssembler assembler,
                          CatalogProperties properties,
                          ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.dataSourceService = dataSourceService;
        this.gateway = gateway;
        this.assembler = assembler;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 按路径下钻一层。
     *
     * @param refresh true 时跳过快照,强制重新探测目标端
     */
    @Transactional
    public CatalogPage browse(String dataSourceId, CatalogPath path, boolean refresh) {
        DataSourceEntity entity = dataSourceService.requireInWorkspace(dataSourceId);
        CatalogPath effectivePath = path == null ? CatalogPath.root() : path;

        requireUsable(entity);

        if (!refresh) {
            CatalogPage cached = readFreshSnapshot(entity, effectivePath);
            if (cached != null) {
                return cached;
            }
        }

        ConnectionConfig config = assembler.assemble(entity, entity.getWorkspaceId());
        CatalogPage page = gateway.browse(entity.getType(), config, effectivePath);
        saveSnapshot(entity, effectivePath, page);
        return page;
    }

    /**
     * 从<b>快照</b>读一张表的字段,不触碰目标端。
     *
     * <p>给 Control 的编译器用(功能 9/11 的字段映射校验)。刻意与 {@link #browse}
     * 分开而不是加一个 {@code snapshotOnly} 参数,理由是两者的失败语义相反:
     * browse 拿不到快照就去探测,这里拿不到快照就<b>返回空</b>,由调用方判成
     * 一条"请先浏览该表结构"的编译诊断。
     *
     * <p>编译一次不该连上生产库 —— 一个二十节点的工作流编译会变成二十次连库,
     * 而用户可能只是在编辑器里点了个保存。
     *
     * <p>不走 TTL 判定:过期的快照对编译仍然有用(总比没有强),而强制刷新
     * 恰恰是这里要避免的事。快照太旧导致的误判,由 UI 上的"结构采集于 X 时间"
     * 提示交给用户判断。
     *
     * @param workspaceId 显式传入而非取 ThreadLocal —— 调度器线程上没有它
     * @return 字段名 → CanonicalType 的 name();无快照时空 Map
     */
    public Map<String, String> snapshotColumns(String workspaceId, String dataSourceId,
                                               String database, String schema, String table) {
        CatalogPath path = CatalogPath.ofTable(database, schema, table);
        CatalogSnapshotEntity snapshot = snapshotMapper.selectOne(
                pathQuery(dataSourceId, path).eq(CatalogSnapshotEntity::getWorkspaceId, workspaceId));
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return Map.of();
        }
        try {
            CatalogPage page = objectMapper.readValue(snapshot.getPayloadJson(), CatalogPage.class);
            Map<String, String> columns = new LinkedHashMap<>();
            for (CatalogModel.ColumnInfo column : page.columns()) {
                columns.put(column.name(),
                        column.canonicalType() == null ? null : column.canonicalType().name());
            }
            return columns;
        } catch (Exception e) {
            log.warn("表结构快照反序列化失败 datasource={} {}.{}.{}",
                    dataSourceId, database, schema, table, e);
            return Map.of();
        }
    }

    /** 该数据源类型的能力声明 —— 前端据此决定画几级树。 */
    public ConnectorCapabilities capabilities(String dataSourceId) {
        DataSourceEntity entity = dataSourceService.requireInWorkspace(dataSourceId);
        return gateway.capabilities(entity.getType());
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 只有验证通过的数据源才允许浏览结构。
     *
     * <p>没验证过连通性就去打生产库是不负责任的:配置可能指向一台完全无关的机器,
     * 而结构浏览会真的连上去执行元数据查询。
     */
    private void requireUsable(DataSourceEntity entity) {
        if (!entity.getStatus().isUsable()) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "数据源当前状态为 %s,需先通过连通性测试才能浏览结构"
                            .formatted(entity.getStatus()));
        }
    }

    private CatalogPage readFreshSnapshot(DataSourceEntity entity, CatalogPath path) {
        int ttlMinutes = properties.getSnapshotTtlMinutes();
        if (ttlMinutes <= 0) {
            return null;
        }
        CatalogSnapshotEntity snapshot = snapshotMapper.selectOne(pathQuery(entity.getId(), path));
        if (snapshot == null || snapshot.getCapturedAt() == null) {
            return null;
        }
        Duration age = Duration.between(snapshot.getCapturedAt(), Instant.now());
        if (age.toMinutes() >= ttlMinutes) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getPayloadJson(), CatalogPage.class);
        } catch (Exception e) {
            // 快照读坏了不该让功能不可用 —— 退化成实时探测即可,顺手记一笔便于排查
            log.warn("目录快照反序列化失败,改为实时探测 datasource={} path={}",
                    entity.getId(), path.display(), e);
            return null;
        }
    }

    private void saveSnapshot(DataSourceEntity entity, CatalogPath path, CatalogPage page) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(page);
        } catch (Exception e) {
            // 探测已经成功,快照存不下不该让用户拿不到结果
            log.warn("目录快照序列化失败,本次不落快照 datasource={}", entity.getId(), e);
            return;
        }

        CatalogSnapshotEntity existing = snapshotMapper.selectOne(pathQuery(entity.getId(), path));
        CatalogSnapshotEntity snapshot = existing == null ? new CatalogSnapshotEntity() : existing;

        if (existing == null) {
            snapshot.setId(Ids.of("cat"));
            snapshot.setWorkspaceId(entity.getWorkspaceId());
            snapshot.setDatasourceId(entity.getId());
            snapshot.setLevel(path.level().name());
            snapshot.setPathDatabase(path.database());
            snapshot.setPathSchema(path.schema());
            snapshot.setPathTable(path.table());
            snapshot.setPathLiteral(path.path());
        }
        snapshot.setPayloadJson(payload);
        snapshot.setItemCount(countItems(page));
        snapshot.setCapturedAt(Instant.now());
        snapshot.setCapturedBy(WorkspaceContext.require().userId());

        if (existing == null) {
            snapshotMapper.insert(snapshot);
        } else {
            snapshotMapper.updateById(snapshot);
        }
    }

    /**
     * 同一路径只保留最新一份快照。
     *
     * <p>路径的各段可能为 null,而 SQL 里 {@code null = null} 不成立,
     * 所以必须用 {@code isNull} 而不是 {@code eq(null)} —— 后者会被
     * MyBatis-Plus 当作"不加这个条件",于是不同层级的快照会互相命中。
     */
    private LambdaQueryWrapper<CatalogSnapshotEntity> pathQuery(String dataSourceId, CatalogPath path) {
        LambdaQueryWrapper<CatalogSnapshotEntity> query = new LambdaQueryWrapper<CatalogSnapshotEntity>()
                .eq(CatalogSnapshotEntity::getDatasourceId, dataSourceId)
                .eq(CatalogSnapshotEntity::getLevel, path.level().name());
        applyNullableEq(query, CatalogSnapshotEntity::getPathDatabase, path.database());
        applyNullableEq(query, CatalogSnapshotEntity::getPathSchema, path.schema());
        applyNullableEq(query, CatalogSnapshotEntity::getPathTable, path.table());
        applyNullableEq(query, CatalogSnapshotEntity::getPathLiteral, path.path());
        return query;
    }

    private static void applyNullableEq(
            LambdaQueryWrapper<CatalogSnapshotEntity> query,
            com.baomidou.mybatisplus.core.toolkit.support.SFunction<CatalogSnapshotEntity, ?> column,
            String value) {
        if (value == null) {
            query.isNull(column);
        } else {
            query.eq(column, value);
        }
    }

    private static int countItems(CatalogPage page) {
        return page.databases().size() + page.schemas().size() + page.tables().size()
                + page.columns().size() + page.files().size();
    }
}
