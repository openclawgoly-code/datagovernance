package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.CatalogPage;
import com.datagov.data.spi.catalog.CatalogModel.ColumnInfo;
import com.datagov.data.spi.catalog.CatalogModel.DatabaseInfo;
import com.datagov.data.spi.catalog.CatalogModel.SchemaInfo;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogModel.TableInfo;
import com.datagov.data.spi.catalog.CatalogGateway;
import com.datagov.data.spi.catalog.CatalogPath;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog 采集与查询(P10.1)。
 *
 * <p>首期只接 JDBC 来源:复用现有的 {@link DataAccessGateway} 逐层探测,
 * 把“库/模式/表/字段”写进规范化表。Parquet/Iceberg/HMS 在 P10.2 接
 * {@code CatalogProvider},这里不抢跑。
 *
 * <p><b>不缓存连接配置</b>:每次 refresh 通过 {@link ConnectionConfigAssembler}
 * 重新组装,口令只在调用栈上停留。
 */
@Service
public class CatalogAssetService {

    private static final Logger log = LoggerFactory.getLogger(CatalogAssetService.class);

    private static final String SOURCE_JDBC = "JDBC";
    private static final String SOURCE_PARQUET = "PARQUET_DIRECTORY";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CatalogSourceMapper sourceMapper;
    private final CatalogNamespaceMapper namespaceMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final CatalogSchemaVersionMapper schemaVersionMapper;
    private final CatalogPartitionMapper partitionMapper;
    private final CatalogSnapshotRunMapper runMapper;
    private final DataSourceService dataSourceService;
    private final DataAccessGateway gateway;
    private final CatalogGateway catalogGateway;
    private final ConnectionConfigAssembler assembler;
    private final ObjectMapper objectMapper;

    public CatalogAssetService(CatalogSourceMapper sourceMapper,
                               CatalogNamespaceMapper namespaceMapper,
                               CatalogDatasetMapper datasetMapper,
                               CatalogColumnMapper columnMapper,
                               CatalogSchemaVersionMapper schemaVersionMapper,
                               CatalogPartitionMapper partitionMapper,
                               CatalogSnapshotRunMapper runMapper,
                               DataSourceService dataSourceService,
                               DataAccessGateway gateway,
                               CatalogGateway catalogGateway,
                               ConnectionConfigAssembler assembler,
                               ObjectMapper objectMapper) {
        this.sourceMapper = sourceMapper;
        this.namespaceMapper = namespaceMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.schemaVersionMapper = schemaVersionMapper;
        this.partitionMapper = partitionMapper;
        this.runMapper = runMapper;
        this.dataSourceService = dataSourceService;
        this.gateway = gateway;
        this.catalogGateway = catalogGateway;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
    }

    // ── DTO ─────────────────────────────────────────────────────────────

    public record SourceRequest(String name, String sourceType, String dataSourceId,
                                String configJson) {
    }

    public record SourceView(String id, String name, String sourceType, String dataSourceId,
                             String status, Instant lastRefreshedAt, Instant createdAt,
                             String createdBy) {
        static SourceView from(CatalogSource source) {
            return new SourceView(source.getId(), source.getName(), source.getSourceType(),
                    source.getDataSourceId(), source.getStatus(), source.getLastRefreshedAt(),
                    source.getCreatedAt(), source.getCreatedBy());
        }
    }

    public record DatasetView(String id, String sourceId, String namespaceId, String name,
                              String qualifiedName, String kind, String format,
                              String comment, String schemaHash, Integer columnCount,
                              Long rowCount, Long sizeBytes, String location,
                              String propertiesJson, Instant lastModifiedAt, Instant updatedAt) {
        static DatasetView from(CatalogDataset dataset) {
            return new DatasetView(dataset.getId(), dataset.getSourceId(),
                    dataset.getNamespaceId(), dataset.getName(), dataset.getQualifiedName(),
                    dataset.getKind(), dataset.getFormat(), dataset.getComment(),
                    dataset.getSchemaHash(), dataset.getColumnCount(), dataset.getRowCount(),
                    dataset.getSizeBytes(), dataset.getLocation(), dataset.getPropertiesJson(),
                    dataset.getLastModifiedAt(), dataset.getUpdatedAt());
        }
    }

    public record ColumnView(String id, String name, Integer ordinal, String rawType,
                             String canonicalType, Integer precision, Integer scale,
                             Boolean nullable, Boolean primaryKey, Boolean partitionKey,
                             String comment) {
        static ColumnView from(CatalogColumn column) {
            return new ColumnView(column.getId(), column.getName(), column.getOrdinal(),
                    column.getRawType(), column.getCanonicalType(), column.getPrecision(),
                    column.getScale(), column.getNullable(), column.getPrimaryKey(),
                    column.getPartitionKey(), column.getComment());
        }
    }

    public record PartitionView(String id, String path, String valuesJson,
                                Long fileCount, Long sizeBytes, Long rowCount,
                                Instant lastModifiedAt) {
        static PartitionView from(CatalogPartition partition) {
            return new PartitionView(partition.getId(), partition.getPartitionPath(),
                    partition.getPartitionValuesJson(), partition.getFileCount(),
                    partition.getSizeBytes(), partition.getRowCount(),
                    partition.getLastModifiedAt());
        }
    }

    public record DatasetDetail(DatasetView dataset, List<ColumnView> columns,
                                List<PartitionView> partitions) {
    }

    public record RefreshResult(String runId, String status, int namespaces, int datasets,
                                int columns, long durationMillis) {
    }

    // ── 来源管理 ────────────────────────────────────────────────────────

    @Transactional
    public SourceView createSource(SourceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "数据目录名称不能为空");
        }
        String sourceType = request.sourceType() == null || request.sourceType().isBlank()
                ? SOURCE_JDBC
                : request.sourceType().trim().toUpperCase();
        if (!SOURCE_JDBC.equals(sourceType) && !SOURCE_PARQUET.equals(sourceType)) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "暂不支持的数据目录来源: " + sourceType
                            + ";当前支持 JDBC / PARQUET_DIRECTORY");
        }
        if (request.dataSourceId() == null || request.dataSourceId().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "数据目录必须指定数据源");
        }

        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        // 越权校验:数据源必须属于当前空间
        DataSourceEntity dataSource = dataSourceService.requireInWorkspace(request.dataSourceId());
        if (SOURCE_JDBC.equals(sourceType)
                && dataSource.getFamily() != DataSourceType.Family.RELATIONAL
                && dataSource.getFamily() != DataSourceType.Family.MPP) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "JDBC 数据目录只能指向关系型/MPP 数据源,当前为 %s"
                            .formatted(dataSource.getFamily()));
        }
        if (SOURCE_PARQUET.equals(sourceType)
                && dataSource.getFamily() != DataSourceType.Family.OBJECT_STORAGE) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Parquet 数据目录只能指向对象存储/本地目录,当前为 %s"
                            .formatted(dataSource.getFamily()));
        }

        String name = request.name().trim();
        Long count = sourceMapper.selectCount(new LambdaQueryWrapper<CatalogSource>()
                .eq(CatalogSource::getWorkspaceId, workspaceId)
                .eq(CatalogSource::getName, name));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.MTD_CATALOG_NAME_DUPLICATED, name);
        }

        Instant now = Instant.now();
        CatalogSource source = new CatalogSource();
        source.setId(Ids.of("cat"));
        source.setWorkspaceId(workspaceId);
        source.setName(name);
        source.setSourceType(sourceType);
        source.setDataSourceId(request.dataSourceId());
        source.setConfigJson(request.configJson() == null || request.configJson().isBlank()
                ? "{}" : request.configJson());
        source.setStatus(STATUS_ACTIVE);
        source.setCreatedAt(now);
        source.setCreatedBy(operator);
        source.setUpdatedAt(now);
        source.setUpdatedBy(operator);
        source.setDeleted(false);
        sourceMapper.insert(source);
        return SourceView.from(source);
    }

    public List<SourceView> listSources() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return sourceMapper.selectList(new LambdaQueryWrapper<CatalogSource>()
                        .eq(CatalogSource::getWorkspaceId, workspaceId)
                        .orderByDesc(CatalogSource::getUpdatedAt)).stream()
                .map(SourceView::from)
                .toList();
    }

    public SourceView getSource(String id) {
        return SourceView.from(requireSource(id));
    }

    @Transactional
    public void deleteSource(String id) {
        CatalogSource source = requireSource(id);
        sourceMapper.deleteById(source.getId());
    }

    // ── 采集 ────────────────────────────────────────────────────────────

    /**
     * 采集一次目录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>:1000 张表可能跑几分钟,把整次
     * 采集包在一个事务里会长时间占住数据库连接,而且失败时把已采集的部分全部
     * 回滚。采集是可重入的 upsert,失败后重跑即可;运行记录单独记 FAILED。
     */
    public RefreshResult refresh(String sourceId) {
        CatalogSource source = requireSource(sourceId);
        if (!STATUS_ACTIVE.equals(source.getStatus())) {
            throw new BizException(ErrorCode.MTD_CATALOG_REFRESH_FAILED, "数据目录已停用");
        }
        if (!SOURCE_JDBC.equals(source.getSourceType())
                && !SOURCE_PARQUET.equals(source.getSourceType())) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "暂不支持的数据目录来源: " + source.getSourceType());
        }

        DataSourceEntity dataSource = dataSourceService.requireInWorkspace(source.getDataSourceId());
        if (dataSource.getStatus() != DataSourceStatus.AVAILABLE) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "数据源当前状态为 %s,需先通过连通性测试才能采集目录"
                            .formatted(dataSource.getStatus()));
        }
        if (SOURCE_PARQUET.equals(source.getSourceType())
                && dataSource.getFamily() != DataSourceType.Family.OBJECT_STORAGE) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Parquet 数据目录只能指向对象存储/本地目录,当前为 %s"
                            .formatted(dataSource.getFamily()));
        }

        Instant startedAt = Instant.now();
        CatalogSnapshotRun run = new CatalogSnapshotRun();
        run.setId(Ids.of("catrun"));
        run.setSourceId(source.getId());
        run.setStartedAt(startedAt);
        run.setStatus("RUNNING");
        runMapper.insert(run);

        try {
            Counts counts = SOURCE_JDBC.equals(source.getSourceType())
                    ? collectJdbc(source, dataSource)
                    : collectParquet(source, dataSource);
            Instant finishedAt = Instant.now();
            run.setFinishedAt(finishedAt);
            run.setStatus("SUCCESS");
            run.setStatsJson(writeJson(Map.of(
                    "namespaces", counts.namespaces,
                    "datasets", counts.datasets,
                    "columns", counts.columns)));
            runMapper.updateById(run);

            source.setLastRefreshedAt(finishedAt);
            source.setUpdatedAt(finishedAt);
            source.setUpdatedBy(WorkspaceContext.require().userId());
            sourceMapper.updateById(source);

            long duration = Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
            return new RefreshResult(run.getId(), "SUCCESS", counts.namespaces,
                    counts.datasets, counts.columns, duration);
        } catch (RuntimeException ex) {
            run.setFinishedAt(Instant.now());
            run.setStatus("FAILED");
            run.setErrorMessage(truncate(ex.getMessage()));
            runMapper.updateById(run);
            log.warn("数据目录采集失败 source={} datasource={}", source.getId(),
                    source.getDataSourceId(), ex);
            if (ex instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.MTD_CATALOG_REFRESH_FAILED,
                    "数据目录采集失败: " + ex.getMessage(), null, ex);
        }
    }

    private Counts collectJdbc(CatalogSource source, DataSourceEntity dataSource) {
        String workspaceId = source.getWorkspaceId();
        ConnectionConfig config = assembler.assemble(dataSource, workspaceId);
        DataSourceType type = dataSource.getType();
        ConnectorCapabilities capabilities = gateway.capabilities(type);

        CatalogPage root = gateway.browse(type, config, CatalogPath.root());
        List<DatabaseInfo> databases = root.databases();
        if (databases.isEmpty() && dataSource.getDatabaseName() != null
                && !dataSource.getDatabaseName().isBlank()) {
            // 没有独立库概念的引擎(Oracle/达梦)或驱动没报 catalogs:
            // 用连接配置里的库名占位,保证目录树有根。
            databases = List.of(new DatabaseInfo(dataSource.getDatabaseName(),
                    null, null, null));
        }

        Counts counts = new Counts();
        int dbOrdinal = 0;
        for (DatabaseInfo database : databases) {
            String dbName = database.name() == null || database.name().isBlank()
                    ? dataSource.getDatabaseName()
                    : database.name();
            if (dbName == null || dbName.isBlank()) {
                log.warn("跳过没有名字的库 source={}", source.getId());
                continue;
            }
            CatalogNamespace dbNamespace = upsertNamespace(source, null, dbName,
                    "DATABASE", dbName, database.comment(), dbOrdinal++);
            counts.namespaces++;

            if (capabilities.hasSchemaLevel()) {
                CatalogPage schemaPage = gateway.browse(type, config,
                        CatalogPath.ofDatabase(dbName));
                int schemaOrdinal = 0;
                for (SchemaInfo schema : schemaPage.schemas()) {
                    String schemaName = schema.name() == null || schema.name().isBlank()
                            ? "(default)" : schema.name();
                    CatalogNamespace schemaNamespace = upsertNamespace(source, dbNamespace.getId(),
                            schemaName, "SCHEMA",
                            dbName + "." + schemaName, schema.comment(),
                            schemaOrdinal++);
                    counts.namespaces++;
                    CatalogPage tablePage = gateway.browse(type, config,
                            CatalogPath.ofSchema(dbName, schemaName));
                    collectTables(source, dataSource, config, type, tablePage,
                            dbName, schemaName, schemaNamespace, counts);
                }
            } else {
                CatalogPage tablePage = gateway.browse(type, config,
                        CatalogPath.ofDatabase(dbName));
                collectTables(source, dataSource, config, type, tablePage,
                        dbName, null, dbNamespace, counts);
            }
        }
        return counts;
    }

    private Counts collectParquet(CatalogSource source, DataSourceEntity dataSource) {
        ConnectionConfig config = assembler.assemble(dataSource, source.getWorkspaceId());
        String rootPath = parquetRootPath(source);
        CatalogAssets.Snapshot snapshot = catalogGateway.readCatalog(
                CatalogAssets.CatalogSourceType.PARQUET_DIRECTORY,
                dataSource.getType(), config, rootPath);
        return persistSnapshot(source, snapshot);
    }

    private Counts persistSnapshot(CatalogSource source, CatalogAssets.Snapshot snapshot) {
        Counts counts = new Counts();
        Map<String, CatalogNamespace> namespaces = new LinkedHashMap<>();
        int ordinal = 0;
        for (CatalogAssets.Namespace namespace : snapshot.namespaces()) {
            CatalogNamespace entity = upsertNamespace(source, null, namespace.name(),
                    namespace.kind(), namespace.path(), namespace.comment(), ordinal++);
            namespaces.put(namespace.path(), entity);
            counts.namespaces++;
        }

        CatalogNamespace fallback = namespaces.values().stream().findFirst().orElse(null);
        for (CatalogAssets.Dataset dataset : snapshot.datasets()) {
            CatalogNamespace namespace = namespaces.getOrDefault(
                    dataset.namespacePath(), fallback);
            if (namespace == null) {
                // 没有 namespace 的 snapshot 不符合契约;为免整次失败,补一个根
                namespace = upsertNamespace(source, null, "root", "DIRECTORY",
                        dataset.namespacePath(), null, 0);
                namespaces.put(dataset.namespacePath(), namespace);
                counts.namespaces++;
            }
            upsertParquetDataset(source, namespace, dataset);
            counts.datasets++;
            counts.columns += dataset.columns().size();
        }
        return counts;
    }

    private String parquetRootPath(CatalogSource source) {
        if (source.getConfigJson() == null || source.getConfigJson().isBlank()
                || "{}".equals(source.getConfigJson().trim())) {
            return null;
        }
        try {
            Map<?, ?> config = objectMapper.readValue(source.getConfigJson(), Map.class);
            Object root = config.get("rootPath");
            return root == null ? null : root.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "数据目录 configJson 解析失败", "source=" + source.getId(), e);
        }
    }

    private void collectTables(CatalogSource source, DataSourceEntity dataSource,
                               ConnectionConfig config, DataSourceType type,
                               CatalogPage tablePage, String database, String schema,
                               CatalogNamespace namespace, Counts counts) {
        for (TableInfo table : tablePage.tables()) {
            CatalogPage columnPage = gateway.browse(type, config,
                    CatalogPath.ofTable(database, schema, table.name()));
            List<ColumnInfo> columns = columnPage.columns();
            upsertDataset(source, namespace, table, columns, database, schema);
            counts.datasets++;
            counts.columns += columns.size();
        }
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public PageResult<DatasetView> listDatasets(String sourceId, long page, long size,
                                                String keyword) {
        requireSource(sourceId);
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<CatalogDataset> query = new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getWorkspaceId, workspaceId)
                .eq(CatalogDataset::getSourceId, sourceId)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(CatalogDataset::getName, keyword.trim())
                                .or().like(CatalogDataset::getQualifiedName, keyword.trim()))
                .orderByAsc(CatalogDataset::getQualifiedName);
        Page<CatalogDataset> result = datasetMapper.selectPage(Page.of(page, size), query);
        return PageResult.of(result.getRecords().stream().map(DatasetView::from).toList(),
                result.getTotal(), page, size);
    }

    public DatasetDetail getDataset(String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }
        List<ColumnView> columns = columnMapper.selectList(
                        new LambdaQueryWrapper<CatalogColumn>()
                                .eq(CatalogColumn::getDatasetId, datasetId)
                                .orderByAsc(CatalogColumn::getOrdinal)).stream()
                .map(ColumnView::from)
                .toList();
        List<PartitionView> partitions = partitionMapper.selectList(
                        new LambdaQueryWrapper<CatalogPartition>()
                                .eq(CatalogPartition::getDatasetId, datasetId)
                                .orderByAsc(CatalogPartition::getPartitionPath)).stream()
                .map(PartitionView::from)
                .toList();
        return new DatasetDetail(DatasetView.from(dataset), columns, partitions);
    }

    public List<ColumnView> listColumns(String datasetId) {
        return getDataset(datasetId).columns();
    }

    public List<PartitionView> listPartitions(String datasetId) {
        return getDataset(datasetId).partitions();
    }

    // ── 内部:upsert ─────────────────────────────────────────────────────

    private CatalogNamespace upsertNamespace(CatalogSource source, String parentId, String name,
                                             String kind, String path, String comment,
                                             int ordinal) {
        String normalized = name == null || name.isBlank() ? "(default)" : name.trim();
        LambdaQueryWrapper<CatalogNamespace> query = new LambdaQueryWrapper<CatalogNamespace>()
                .eq(CatalogNamespace::getSourceId, source.getId())
                .eq(CatalogNamespace::getName, normalized);
        if (parentId == null) {
            query.isNull(CatalogNamespace::getParentId);
        } else {
            query.eq(CatalogNamespace::getParentId, parentId);
        }
        CatalogNamespace existing = namespaceMapper.selectOne(query);
        Instant now = Instant.now();
        CatalogNamespace entity = existing == null ? new CatalogNamespace() : existing;
        if (existing == null) {
            entity.setId(Ids.of("catns"));
            entity.setSourceId(source.getId());
            entity.setParentId(parentId);
            entity.setCreatedAt(now);
            entity.setDeleted(false);
        }
        entity.setName(normalized);
        entity.setKind(kind);
        entity.setPath(path);
        entity.setComment(comment);
        entity.setOrdinal(ordinal);
        entity.setUpdatedAt(now);
        if (existing == null) {
            namespaceMapper.insert(entity);
        } else {
            namespaceMapper.updateById(entity);
        }
        return entity;
    }

    private CatalogDataset upsertDataset(CatalogSource source, CatalogNamespace namespace,
                                         TableInfo table, List<ColumnInfo> columns,
                                         String database, String schema) {
        String qualifiedName = schema == null
                ? database + "." + table.name()
                : database + "." + schema + "." + table.name();
        CatalogDataset existing = datasetMapper.selectOne(
                new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getSourceId, source.getId())
                        .eq(CatalogDataset::getQualifiedName, qualifiedName));
        String schemaHash = schemaHash(columns);
        String oldHash = existing == null ? null : existing.getSchemaHash();

        Instant now = Instant.now();
        CatalogDataset entity = existing == null ? new CatalogDataset() : existing;
        if (existing == null) {
            entity.setId(Ids.of("catds"));
            entity.setWorkspaceId(source.getWorkspaceId());
            entity.setSourceId(source.getId());
            entity.setCreatedAt(now);
            entity.setDeleted(false);
        }
        entity.setNamespaceId(namespace.getId());
        entity.setName(table.name());
        entity.setQualifiedName(qualifiedName);
        entity.setKind(table.kind() == null ? "TABLE" : table.kind().name());
        entity.setFormat("JDBC");
        entity.setLocation(null);
        entity.setComment(table.comment());
        entity.setSchemaHash(schemaHash);
        entity.setColumnCount(columns.size());
        entity.setRowCount(table.approximateRowCount());
        entity.setSizeBytes(table.sizeInBytes());
        entity.setLastModifiedAt(table.createdAt());
        entity.setUpdatedAt(now);
        if (existing == null) {
            datasetMapper.insert(entity);
        } else {
            datasetMapper.updateById(entity);
        }

        replaceColumns(entity.getId(), columns);
        recordSchemaVersion(entity.getId(), schemaHash, columns, oldHash);
        return entity;
    }

    private void upsertParquetDataset(CatalogSource source, CatalogNamespace namespace,
                                       CatalogAssets.Dataset dataset) {
        CatalogDataset existing = datasetMapper.selectOne(
                new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getSourceId, source.getId())
                        .eq(CatalogDataset::getQualifiedName, dataset.qualifiedName()));
        String schemaHash = parquetSchemaHash(dataset.columns());
        String oldHash = existing == null ? null : existing.getSchemaHash();

        Instant now = Instant.now();
        CatalogDataset entity = existing == null ? new CatalogDataset() : existing;
        if (existing == null) {
            entity.setId(Ids.of("catds"));
            entity.setWorkspaceId(source.getWorkspaceId());
            entity.setSourceId(source.getId());
            entity.setCreatedAt(now);
            entity.setDeleted(false);
        }
        entity.setNamespaceId(namespace.getId());
        entity.setName(dataset.name());
        entity.setQualifiedName(dataset.qualifiedName());
        entity.setKind(dataset.kind());
        entity.setFormat(dataset.format());
        entity.setLocation(dataset.location());
        entity.setComment(null);
        entity.setSchemaHash(schemaHash);
        entity.setColumnCount(dataset.columns().size());
        entity.setRowCount(dataset.rowCount());
        entity.setSizeBytes(dataset.sizeBytes());
        entity.setLastModifiedAt(dataset.lastModifiedAt());
        entity.setPartitionColumnsJson(writeJson(dataset.partitionColumns()));
        entity.setUpdatedAt(now);
        if (existing == null) {
            datasetMapper.insert(entity);
        } else {
            datasetMapper.updateById(entity);
        }

        replaceParquetColumns(entity.getId(), dataset.columns());
        replacePartitions(entity.getId(), dataset.partitions());
        recordParquetSchemaVersion(entity.getId(), schemaHash, dataset.columns(), oldHash);
    }

    private void replaceParquetColumns(String datasetId, List<CatalogAssets.Column> columns) {
        columnMapper.delete(new LambdaQueryWrapper<CatalogColumn>()
                .eq(CatalogColumn::getDatasetId, datasetId));
        Instant now = Instant.now();
        for (CatalogAssets.Column column : columns) {
            CatalogColumn entity = new CatalogColumn();
            entity.setId(Ids.of("catcol"));
            entity.setDatasetId(datasetId);
            entity.setName(column.name());
            entity.setOrdinal(column.ordinal());
            entity.setRawType(column.rawType());
            entity.setCanonicalType(column.canonicalType() == null
                    ? null : column.canonicalType().name());
            entity.setPrecision(column.precision());
            entity.setScale(column.scale());
            entity.setNullable(column.nullable());
            entity.setPrimaryKey(column.primaryKey());
            entity.setPartitionKey(column.partitionKey());
            entity.setDefaultValue(null);
            entity.setComment(column.comment());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            columnMapper.insert(entity);
        }
    }

    private void replacePartitions(String datasetId, List<CatalogAssets.Partition> partitions) {
        partitionMapper.delete(new LambdaQueryWrapper<CatalogPartition>()
                .eq(CatalogPartition::getDatasetId, datasetId));
        Instant now = Instant.now();
        for (CatalogAssets.Partition partition : partitions) {
            CatalogPartition entity = new CatalogPartition();
            entity.setId(Ids.of("catpt"));
            entity.setDatasetId(datasetId);
            entity.setPartitionPath(partition.path());
            entity.setPartitionValuesJson(partition.valuesJson());
            entity.setFileCount(partition.fileCount());
            entity.setSizeBytes(partition.sizeBytes());
            entity.setLastModifiedAt(partition.lastModifiedAt());
            entity.setCreatedAt(now);
            partitionMapper.insert(entity);
        }
    }

    private void recordParquetSchemaVersion(String datasetId, String schemaHash,
                                            List<CatalogAssets.Column> columns,
                                            String oldHash) {
        if (schemaHash.equals(oldHash)) {
            return;
        }
        CatalogSchemaVersion version = new CatalogSchemaVersion();
        version.setId(Ids.of("catsv"));
        version.setDatasetId(datasetId);
        version.setSchemaHash(schemaHash);
        version.setColumnsJson(writeJson(columns));
        version.setChangeType(oldHash == null ? "INITIAL" : "CHANGED");
        version.setCapturedAt(Instant.now());
        schemaVersionMapper.insert(version);
    }

    private void replaceColumns(String datasetId, List<ColumnInfo> columns) {
        columnMapper.delete(new LambdaQueryWrapper<CatalogColumn>()
                .eq(CatalogColumn::getDatasetId, datasetId));
        Instant now = Instant.now();
        for (ColumnInfo column : columns) {
            CatalogColumn entity = new CatalogColumn();
            entity.setId(Ids.of("catcol"));
            entity.setDatasetId(datasetId);
            entity.setName(column.name());
            entity.setOrdinal(column.ordinalPosition());
            entity.setRawType(column.rawType());
            entity.setCanonicalType(column.canonicalType() == null
                    ? null : column.canonicalType().name());
            entity.setPrecision(column.precision());
            entity.setScale(column.scale());
            entity.setNullable(column.nullable());
            entity.setPrimaryKey(column.primaryKey());
            entity.setPartitionKey(false);
            entity.setDefaultValue(column.defaultValue());
            entity.setComment(column.comment());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            columnMapper.insert(entity);
        }
    }

    private void recordSchemaVersion(String datasetId, String schemaHash,
                                     List<ColumnInfo> columns, String oldHash) {
        if (schemaHash.equals(oldHash)) {
            return;
        }
        CatalogSchemaVersion version = new CatalogSchemaVersion();
        version.setId(Ids.of("catsv"));
        version.setDatasetId(datasetId);
        version.setSchemaHash(schemaHash);
        version.setColumnsJson(writeJson(columns));
        version.setChangeType(oldHash == null ? "INITIAL" : "CHANGED");
        version.setCapturedAt(Instant.now());
        schemaVersionMapper.insert(version);
    }

    // ── 内部:工具 ───────────────────────────────────────────────────────

    private CatalogSource requireSource(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogSource source = sourceMapper.selectOne(new LambdaQueryWrapper<CatalogSource>()
                .eq(CatalogSource::getId, id)
                .eq(CatalogSource::getWorkspaceId, workspaceId));
        if (source == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, id);
        }
        return source;
    }

    private String parquetSchemaHash(List<CatalogAssets.Column> columns) {
        StringBuilder canonical = new StringBuilder();
        for (CatalogAssets.Column column : columns) {
            canonical.append(column.name()).append('|')
                    .append(column.rawType()).append('|')
                    .append(column.canonicalType()).append('|')
                    .append(column.partitionKey()).append('\n');
        }
        return sha256(canonical.toString());
    }

    private String schemaHash(List<ColumnInfo> columns) {
        StringBuilder canonical = new StringBuilder();
        for (ColumnInfo column : columns) {
            canonical.append(column.name()).append('|')
                    .append(column.rawType()).append('|')
                    .append(column.canonicalType()).append('|')
                    .append(column.precision()).append('|')
                    .append(column.scale()).append('|')
                    .append(column.nullable()).append('|')
                    .append(column.primaryKey()).append('\n');
        }
        return sha256(canonical.toString());
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("schema hash 计算失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "catalog JSON 序列化失败", null, e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "…";
    }

    private static final class Counts {
        private int namespaces;
        private int datasets;
        private int columns;
    }
}
