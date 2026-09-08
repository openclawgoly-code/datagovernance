package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.RegistryEntities.RegistryArtifact;
import com.datagov.metadata.entity.RegistryEntities.RegistryArtifactVersion;
import com.datagov.metadata.entity.RegistryEntities.RelationEdge;
import com.datagov.metadata.mapper.RegistryArtifactMapper;
import com.datagov.metadata.mapper.RegistryArtifactVersionMapper;
import com.datagov.metadata.mapper.RelationEdgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 数据集 / 模型注册中心与语义映射(序号 34 的契约第 3、4 条)。
 *
 * <p><b>这不是 Intelligence 的实现,是它与平台之间的接口。</b> 序号 34 已确认
 * 独立立项(架构风险 R1);这个类存在的意义是:当那个平台建起来时,它有一个
 * 确定的、已经在跑的地方去注册产物,而不是自己再建一个注册中心。
 *
 * <p>「不建第二注册中心」是契约的原话。它的实际后果是:平台的血缘与影响分析
 * 能看见数据集与模型 —— 如果 Intelligence 自己存一份,那两份迟早不一致,
 * 而"这个模型用了哪些表"就再也没有一个可信的答案。
 */
@Service
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

    /** 注册项的种类。ONTOLOGY 也在这里 —— 本体文件同样是"标识在平台、内容在对象存储" */
    static final Set<String> KINDS = Set.of("DATASET", "MODEL", "ONTOLOGY");

    /** 本期只有一种关系。加新关系时这里加一个值,而不是新开一张表 */
    static final Set<String> RELATIONS = Set.of("MAPS_TO", "DERIVED_FROM", "TRAINED_ON");

    private final RegistryArtifactMapper artifactMapper;
    private final RegistryArtifactVersionMapper versionMapper;
    private final RelationEdgeMapper edgeMapper;

    public RegistryService(RegistryArtifactMapper artifactMapper,
                           RegistryArtifactVersionMapper versionMapper,
                           RelationEdgeMapper edgeMapper) {
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.edgeMapper = edgeMapper;
    }

    // ── 视图 ────────────────────────────────────────────────────────────

    public record ArtifactView(
            String id,
            String kind,
            String name,
            String description,
            Integer latestVersion,
            String producedByExecutionId,
            Instant createdAt,
            String createdBy,
            Instant updatedAt
    ) {

        static ArtifactView from(RegistryArtifact a) {
            return new ArtifactView(a.getId(), a.getKind(), a.getName(), a.getDescription(),
                    a.getLatestVersion(), a.getProducedByExecutionId(),
                    a.getCreatedAt(), a.getCreatedBy(), a.getUpdatedAt());
        }
    }

    public record VersionView(
            String id,
            String artifactId,
            int version,
            String contentUri,
            Long sizeBytes,
            String checksumSha256,
            Long itemCount,
            String producedByExecutionId,
            String derivedFromVersionId,
            String metadataJson,
            Instant createdAt,
            String createdBy
    ) {

        static VersionView from(RegistryArtifactVersion v) {
            return new VersionView(v.getId(), v.getArtifactId(), v.getVersion(),
                    v.getContentUri(), v.getSizeBytes(), v.getChecksumSha256(),
                    v.getItemCount(), v.getProducedByExecutionId(),
                    v.getDerivedFromVersionId(), v.getMetadataJson(),
                    v.getCreatedAt(), v.getCreatedBy());
        }
    }

    public record EdgeView(
            String id,
            String fromType,
            String fromId,
            String relation,
            String toType,
            String toId,
            String toLabel,
            Double confidence,
            String origin,
            Instant createdAt,
            String createdBy
    ) {

        static EdgeView from(RelationEdge e) {
            return new EdgeView(e.getId(), e.getFromType(), e.getFromId(), e.getRelation(),
                    e.getToType(), e.getToId(), e.getToLabel(), e.getConfidence(),
                    e.getOrigin(), e.getCreatedAt(), e.getCreatedBy());
        }
    }

    public record RegisterRequest(
            String kind,
            String name,
            String description,
            String producedByExecutionId
    ) {
    }

    public record PublishVersionRequest(
            String contentUri,
            Long sizeBytes,
            String checksumSha256,
            Long itemCount,
            String producedByExecutionId,
            String derivedFromVersionId,
            String metadataJson
    ) {
    }

    public record EdgeRequest(
            String fromType,
            String fromId,
            String relation,
            String toType,
            String toId,
            String toLabel,
            Double confidence,
            String origin
    ) {
    }

    // ── 注册项 ──────────────────────────────────────────────────────────

    public PageResult<ArtifactView> list(long page, long size, String kind, String keyword) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Page<RegistryArtifact> result = artifactMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<RegistryArtifact>()
                        .eq(RegistryArtifact::getWorkspaceId, workspaceId)
                        .eq(kind != null && !kind.isBlank(), RegistryArtifact::getKind, kind)
                        .like(keyword != null && !keyword.isBlank(),
                                RegistryArtifact::getName, keyword)
                        .orderByDesc(RegistryArtifact::getUpdatedAt));
        return PageResult.of(result.getRecords().stream().map(ArtifactView::from).toList(),
                result.getTotal(), page, size);
    }

    public ArtifactView get(String id) {
        return ArtifactView.from(requireArtifact(id));
    }

    @Transactional
    public ArtifactView register(RegisterRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        String kind = request.kind() == null ? "" : request.kind().trim().toUpperCase();
        if (!KINDS.contains(kind)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的注册项种类: " + request.kind(),
                    "可选:" + String.join(" / ", KINDS));
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "名称不能为空");
        }
        requireNameAvailable(workspaceId, kind, request.name(), null);

        Instant now = Instant.now();
        RegistryArtifact artifact = new RegistryArtifact();
        artifact.setId(Ids.of("reg"));
        artifact.setWorkspaceId(workspaceId);
        artifact.setKind(kind);
        artifact.setName(request.name().trim());
        artifact.setDescription(request.description());
        artifact.setProducedByExecutionId(request.producedByExecutionId());
        // 注册时还没有版本。0 而不是 1 —— "注册了但还没有内容"是一个真实的
        // 中间状态,把它记成 1 会让人以为已经有一版可用了
        artifact.setLatestVersion(0);
        artifact.setCreatedAt(now);
        artifact.setCreatedBy(operator);
        artifact.setUpdatedAt(now);
        artifact.setUpdatedBy(operator);
        artifact.setDeleted(false);
        artifactMapper.insert(artifact);

        log.info("注册项已创建 workspace={} id={} kind={} name={}",
                workspaceId, artifact.getId(), kind, artifact.getName());
        return ArtifactView.from(artifact);
    }

    @Transactional
    public void delete(String id) {
        RegistryArtifact artifact = requireArtifact(id);
        // 只逻辑删除,版本记录保留:历史执行记录里还指着这些版本,
        // 而"这个模型是用哪版数据训的"这个问题在数据集被删之后仍然要能回答
        artifactMapper.deleteById(id);
        log.info("注册项已删除(版本记录保留)id={} name={}", id, artifact.getName());
    }

    // ── 版本 ────────────────────────────────────────────────────────────

    public List<VersionView> versions(String artifactId) {
        requireArtifact(artifactId);
        return versionMapper.selectList(new LambdaQueryWrapper<RegistryArtifactVersion>()
                        .eq(RegistryArtifactVersion::getArtifactId, artifactId)
                        .orderByDesc(RegistryArtifactVersion::getVersion)).stream()
                .map(VersionView::from)
                .toList();
    }

    /**
     * 发布一个新版本。
     *
     * <p>版本号由平台分配而不是调用方指定 —— 调用方指定会带来"版本 3 发布了
     * 但版本 2 不存在"这种空洞,而空洞会让"上一版是哪一版"变成一个要猜的问题。
     *
     * <p>已发布的版本<b>不可修改</b>:这个类没有 updateVersion。
     */
    @Transactional
    public VersionView publishVersion(String artifactId, PublishVersionRequest request) {
        RegistryArtifact artifact = requireArtifact(artifactId);
        String operator = WorkspaceContext.require().userId();

        if (request.contentUri() == null || request.contentUri().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "必须给出内容地址(contentUri)",
                    "平台只登记标识与版本,内容存对象存储 —— 这是契约第 3 条");
        }
        // 内容不能存在平台里:一个 data: URI 或本地路径意味着有人想把几十 GB
        // 的影像塞进元数据库。明确拦住,而不是等它撑爆数据库才发现
        String uri = request.contentUri().trim();
        if (uri.startsWith("data:")) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "内容地址不能是内联数据",
                    "影像、标注文件与模型权重存对象存储,平台只登记它的地址");
        }

        int next = (artifact.getLatestVersion() == null ? 0 : artifact.getLatestVersion()) + 1;
        Instant now = Instant.now();

        RegistryArtifactVersion version = new RegistryArtifactVersion();
        version.setId(Ids.of("rgv"));
        version.setArtifactId(artifactId);
        version.setWorkspaceId(artifact.getWorkspaceId());
        version.setVersion(next);
        version.setContentUri(uri);
        version.setSizeBytes(request.sizeBytes());
        version.setChecksumSha256(request.checksumSha256());
        version.setItemCount(request.itemCount());
        version.setProducedByExecutionId(request.producedByExecutionId());
        version.setDerivedFromVersionId(request.derivedFromVersionId());
        version.setMetadataJson(request.metadataJson());
        version.setCreatedAt(now);
        version.setCreatedBy(operator);
        versionMapper.insert(version);

        artifact.setLatestVersion(next);
        artifact.setUpdatedAt(now);
        artifact.setUpdatedBy(operator);
        if (request.producedByExecutionId() != null) {
            artifact.setProducedByExecutionId(request.producedByExecutionId());
        }
        artifactMapper.updateById(artifact);

        log.info("注册项发布新版本 id={} name={} v{}", artifactId, artifact.getName(), next);
        return VersionView.from(version);
    }

    // ── 语义映射(契约第 4 条)──────────────────────────────────────────

    /**
     * 查一个实体出发的关系边。
     *
     * @param fromId 起点标识;为空表示查全部(用于列出所有映射)
     */
    public List<EdgeView> edges(String fromType, String fromId, String relation) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return edgeMapper.selectList(new LambdaQueryWrapper<RelationEdge>()
                        .eq(RelationEdge::getWorkspaceId, workspaceId)
                        .eq(fromType != null && !fromType.isBlank(),
                                RelationEdge::getFromType, fromType)
                        .eq(fromId != null && !fromId.isBlank(), RelationEdge::getFromId, fromId)
                        .eq(relation != null && !relation.isBlank(),
                                RelationEdge::getRelation, relation)
                        .orderByDesc(RelationEdge::getCreatedAt)).stream()
                .map(EdgeView::from)
                .toList();
    }

    /**
     * 写一条关系边。
     *
     * <p>本期的唯一用途是 {@code Column ──MapsTo──> Concept}:让医学语义能被
     * 平台的血缘与影响分析看见。Concept 的定义归 Intelligence,平台只记
     * 这条边的两端标识与一个可读名。
     */
    @Transactional
    public EdgeView addEdge(EdgeRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        String relation = request.relation() == null ? "" : request.relation().trim().toUpperCase();
        if (!RELATIONS.contains(relation)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的关系类型: " + request.relation(),
                    "可选:" + String.join(" / ", RELATIONS));
        }
        if (isBlank(request.fromType()) || isBlank(request.fromId())
                || isBlank(request.toType()) || isBlank(request.toId())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "关系边的两端都必须给出类型与标识");
        }

        Long dup = edgeMapper.selectCount(new LambdaQueryWrapper<RelationEdge>()
                .eq(RelationEdge::getWorkspaceId, workspaceId)
                .eq(RelationEdge::getFromType, request.fromType())
                .eq(RelationEdge::getFromId, request.fromId())
                .eq(RelationEdge::getRelation, relation)
                .eq(RelationEdge::getToId, request.toId()));
        if (dup != null && dup > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT,
                    "这条映射已经存在: %s -> %s".formatted(request.fromId(), request.toId()));
        }

        RelationEdge edge = new RelationEdge();
        edge.setId(Ids.of("edg"));
        edge.setWorkspaceId(workspaceId);
        edge.setFromType(request.fromType().trim().toUpperCase());
        edge.setFromId(request.fromId().trim());
        edge.setRelation(relation);
        edge.setToType(request.toType().trim().toUpperCase());
        edge.setToId(request.toId().trim());
        edge.setToLabel(request.toLabel());
        // 人工建立的映射默认 1.0;自动抽取的必须显式给出置信度,
        // 否则它会与人工确认过的混在一起,而复核时无从区分
        edge.setConfidence(request.confidence() == null ? 1.0 : request.confidence());
        edge.setOrigin(request.origin() == null ? "MANUAL" : request.origin().toUpperCase());
        edge.setCreatedAt(Instant.now());
        edge.setCreatedBy(operator);
        edge.setDeleted(false);
        edgeMapper.insert(edge);

        return EdgeView.from(edge);
    }

    @Transactional
    public void removeEdge(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        RelationEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<RelationEdge>()
                .eq(RelationEdge::getId, id)
                .eq(RelationEdge::getWorkspaceId, workspaceId));
        if (edge == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "关系边 " + id);
        }
        edgeMapper.deleteById(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void requireNameAvailable(String workspaceId, String kind, String name,
                                      String excludeId) {
        Long count = artifactMapper.selectCount(new LambdaQueryWrapper<RegistryArtifact>()
                .eq(RegistryArtifact::getWorkspaceId, workspaceId)
                .eq(RegistryArtifact::getKind, kind)
                .eq(RegistryArtifact::getName, name.trim())
                .ne(excludeId != null, RegistryArtifact::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT,
                    "%s「%s」已注册".formatted(kind, name));
        }
    }

    private RegistryArtifact requireArtifact(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        RegistryArtifact artifact = artifactMapper.selectOne(
                new LambdaQueryWrapper<RegistryArtifact>()
                        .eq(RegistryArtifact::getId, id)
                        .eq(RegistryArtifact::getWorkspaceId, workspaceId));
        if (artifact == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "注册项 " + id);
        }
        return artifact;
    }
}
