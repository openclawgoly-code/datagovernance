package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字段级血缘(P10.4)。
 *
 * <p>边是“谁从谁派生”的事实,不删除;SQL 变化时旧边标 SUPERSEDED。
 * 同一作业的同一组 from/to/relation 复用时原地更新,避免撞唯一索引。
 *
 * <p>1000 张表的规模用 BFS + PG 邻接表足够;不引入图数据库。
 */
@Service
public class LineageService {

    private static final int MAX_DEPTH = 5;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final LineageNodeMapper nodeMapper;
    private final LineageJobMapper jobMapper;
    private final LineageEdgeMapper edgeMapper;
    private final CatalogDatasetMapper datasetMapper;

    public LineageService(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                          LineageEdgeMapper edgeMapper, CatalogDatasetMapper datasetMapper) {
        this.nodeMapper = nodeMapper;
        this.jobMapper = jobMapper;
        this.edgeMapper = edgeMapper;
        this.datasetMapper = datasetMapper;
    }

    public record NodeRef(String nodeType, String qualifiedName,
                          String catalogDatasetId, String catalogColumnId,
                          String displayName) {
    }

    public record EdgeInput(NodeRef from, NodeRef to, String relation,
                            String transformExpr, Double confidence, String origin) {
    }

    public record JobRequest(String name, String jobType, String sourceRef,
                             String sqlHash, List<EdgeInput> edges) {
    }

    public record JobResult(String jobId, int edges) {
    }

    public record NodeView(String id, String nodeType, String qualifiedName,
                           String catalogDatasetId, String catalogColumnId,
                           String displayName) {
        static NodeView from(LineageNode node) {
            return new NodeView(node.getId(), node.getNodeType(), node.getQualifiedName(),
                    node.getCatalogDatasetId(), node.getCatalogColumnId(), node.getDisplayName());
        }
    }

    public record EdgeView(String id, String fromNodeId, String toNodeId, String relation,
                           String transformExpr, Double confidence, String origin, String status) {
        static EdgeView from(LineageEdge edge) {
            return new EdgeView(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    edge.getRelation(), edge.getTransformExpr(), edge.getConfidence(),
                    edge.getOrigin(), edge.getStatus());
        }
    }

    public record GraphView(List<NodeView> nodes, List<EdgeView> edges,
                            int unattributedEdges) {
        public GraphView(List<NodeView> nodes, List<EdgeView> edges) {
            this(nodes, edges, 0);
        }
    }

    // ── 写入 ────────────────────────────────────────────────────────────

    /**
     * 记录一次作业的列级血缘。重复上报同一作业时,旧边会被复用或标 SUPERSEDED,
     * 不会产生重复边。
     */
    @Transactional
    public JobResult recordJob(JobRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘作业名称不能为空");
        }
        if (request.edges() == null || request.edges().isEmpty()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边不能为空");
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String jobType = request.jobType() == null || request.jobType().isBlank()
                ? "MANUAL" : request.jobType().trim().toUpperCase();
        LineageJob job = upsertJob(workspaceId, request, jobType);

        Map<String, LineageEdge> existing = new HashMap<>();
        for (LineageEdge edge : edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getJobId, job.getId()))) {
            existing.put(key(edge.getFromNodeId(), edge.getToNodeId(), edge.getRelation()), edge);
        }

        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (EdgeInput input : request.edges()) {
            validateEdge(input);
            LineageNode from = upsertNode(workspaceId, input.from());
            LineageNode to = upsertNode(workspaceId, input.to());
            String relation = relation(input.relation());
            String edgeKey = key(from.getId(), to.getId(), relation);
            if (!seen.add(edgeKey)) {
                continue;
            }
            LineageEdge edge = existing.remove(edgeKey);
            if (edge == null) {
                edge = new LineageEdge();
                edge.setId(Ids.of("line"));
                edge.setWorkspaceId(workspaceId);
                edge.setFromNodeId(from.getId());
                edge.setToNodeId(to.getId());
                edge.setRelation(relation);
                edge.setJobId(job.getId());
                edge.setDiscoveredAt(Instant.now());
                applyEdge(edge, input, request.sqlHash());
                edgeMapper.insert(edge);
            } else {
                applyEdge(edge, input, request.sqlHash());
                edgeMapper.updateById(edge);
            }
            count++;
        }

        for (LineageEdge stale : existing.values()) {
            if (STATUS_ACTIVE.equals(stale.getStatus())) {
                stale.setStatus(STATUS_SUPERSEDED);
                edgeMapper.updateById(stale);
            }
        }
        return new JobResult(job.getId(), count);
    }

    /** 人工补录一条边。同一 from/to/relation 复用时原地更新。 */
    @Transactional
    public EdgeView recordManualEdge(EdgeInput input) {
        validateEdge(input);
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageNode from = upsertNode(workspaceId, input.from());
        LineageNode to = upsertNode(workspaceId, input.to());
        String relation = relation(input.relation());

        LineageEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getFromNodeId, from.getId())
                .eq(LineageEdge::getToNodeId, to.getId())
                .eq(LineageEdge::getRelation, relation)
                .isNull(LineageEdge::getJobId));
        if (edge == null) {
            edge = new LineageEdge();
            edge.setId(Ids.of("line"));
            edge.setWorkspaceId(workspaceId);
            edge.setFromNodeId(from.getId());
            edge.setToNodeId(to.getId());
            edge.setRelation(relation);
            edge.setDiscoveredAt(Instant.now());
            applyEdge(edge, input, null);
            edge.setOrigin("MANUAL");
            edgeMapper.insert(edge);
        } else {
            applyEdge(edge, input, null);
            edge.setOrigin("MANUAL");
            edgeMapper.updateById(edge);
        }
        return EdgeView.from(edge);
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public GraphView upstream(String nodeId, int depth) {
        return graph(nodeId, true, depth);
    }

    public GraphView downstream(String nodeId, int depth) {
        return graph(nodeId, false, depth);
    }

    /** 影响分析就是下游遍历;单独命名让调用方读得懂意图。 */
    public GraphView impact(String nodeId, int depth) {
        return downstream(nodeId, depth);
    }

    /**
     * 表级血缘：列级事实按 catalogDatasetId 聚合。
     *
     * <p>表级视图不落新表：起点数据集的列节点先做 BFS，访问到的列级节点
     * 再按数据集分组，列级边按 fromDataset→toDataset 计数。缺少数据集归属
     * 的历史边会被跳过，避免表级图出现悬空节点。
     */
    public GraphView datasetGraph(String datasetId, String direction, int depth) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }

        String dir = direction == null || direction.isBlank()
                ? "both" : direction.trim().toLowerCase();
        boolean both = "both".equals(dir);
        boolean upstream = "upstream".equals(dir);
        boolean downstream = "downstream".equals(dir);
        if (!both && !upstream && !downstream) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "表级血缘方向只能是 upstream / downstream / both");
        }
        int levels = Math.max(1, Math.min(depth, MAX_DEPTH));

        List<LineageNode> seeds = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, "COLUMN")
                .eq(LineageNode::getCatalogDatasetId, datasetId));
        if (seeds.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }

        Set<String> seedIds = seeds.stream().map(LineageNode::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> nodeIds = new LinkedHashSet<>(seedIds);
        Map<String, LineageEdge> columnEdges = new LinkedHashMap<>();
        if (upstream || both) {
            collectColumnEdges(workspaceId, seedIds, true, levels,
                    new LinkedHashSet<>(seedIds), nodeIds, columnEdges);
        }
        if (downstream || both) {
            collectColumnEdges(workspaceId, seedIds, false, levels,
                    new LinkedHashSet<>(seedIds), nodeIds, columnEdges);
        }
        if (columnEdges.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }

        Map<String, LineageNode> nodesById = nodeMapper.selectList(
                        new LambdaQueryWrapper<LineageNode>()
                                .eq(LineageNode::getWorkspaceId, workspaceId)
                                .in(LineageNode::getId, nodeIds)).stream()
                .collect(java.util.stream.Collectors.toMap(LineageNode::getId, node -> node));

        Set<String> datasetIds = new LinkedHashSet<>();
        datasetIds.add(datasetId);
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();
        int unattributedEdges = 0;
        for (LineageEdge edge : columnEdges.values()) {
            LineageNode from = nodesById.get(edge.getFromNodeId());
            LineageNode to = nodesById.get(edge.getToNodeId());
            if (from == null || to == null) {
                unattributedEdges++;
                continue;
            }
            String fromDatasetId = from.getCatalogDatasetId();
            String toDatasetId = to.getCatalogDatasetId();
            if (fromDatasetId == null || fromDatasetId.isBlank()
                    || toDatasetId == null || toDatasetId.isBlank()) {
                unattributedEdges++;
                continue;
            }
            if (fromDatasetId.equals(toDatasetId)) {
                continue;
            }
            datasetIds.add(fromDatasetId);
            datasetIds.add(toDatasetId);
            edgeCounts.merge(fromDatasetId + "\u0000" + toDatasetId, 1, Integer::sum);
        }
        if (edgeCounts.isEmpty()) {
            return new GraphView(List.of(), List.of(), unattributedEdges);
        }

        Map<String, CatalogDataset> datasetsById = datasetMapper.selectList(
                        new LambdaQueryWrapper<CatalogDataset>()
                                .eq(CatalogDataset::getWorkspaceId, workspaceId)
                                .in(CatalogDataset::getId, datasetIds)).stream()
                .collect(java.util.stream.Collectors.toMap(CatalogDataset::getId, value -> value));

        List<NodeView> resultNodes = datasetIds.stream()
                .map(datasetsById::get)
                .filter(Objects::nonNull)
                .map(value -> new NodeView(value.getId(), "DATASET", value.getQualifiedName(),
                        value.getId(), null, value.getName()))
                .toList();

        List<EdgeView> resultEdges = edgeCounts.entrySet().stream()
                .filter(entry -> datasetsById.containsKey(entry.getKey().split("\u0000", 2)[0])
                        && datasetsById.containsKey(entry.getKey().split("\u0000", 2)[1]))
                .map(entry -> {
                    String[] pair = entry.getKey().split("\u0000", 2);
                    return new EdgeView("dsedge:" + pair[0] + "->" + pair[1],
                            pair[0], pair[1], "DERIVE",
                            entry.getValue() + " 条列级血缘", null, "AGGREGATED", STATUS_ACTIVE);
                })
                .toList();

        return new GraphView(resultNodes, resultEdges, unattributedEdges);
    }

    private void collectColumnEdges(String workspaceId, Set<String> seedIds,
                                    boolean upstream, int levels,
                                    Set<String> visited, Set<String> nodeIds,
                                    Map<String, LineageEdge> edges) {
        Set<String> frontier = new LinkedHashSet<>(seedIds);
        for (int level = 0; level < levels && !frontier.isEmpty(); level++) {
            List<LineageEdge> found = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                    .eq(LineageEdge::getWorkspaceId, workspaceId)
                    .eq(LineageEdge::getStatus, STATUS_ACTIVE)
                    .in(upstream ? LineageEdge::getToNodeId : LineageEdge::getFromNodeId,
                            frontier));
            Set<String> next = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                edges.putIfAbsent(edge.getId(), edge);
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                nodeIds.add(other);
                if (visited.add(other)) {
                    next.add(other);
                }
            }
            frontier = next;
        }
    }

    public NodeView getNode(String nodeId) {
        // 与图查询一致：调用方可能持有 catalogColumnId，而不一定是血缘节点 ID。
        LineageNode node = findNode(WorkspaceContext.requireWorkspaceId(), nodeId);
        if (node == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, nodeId);
        }
        return NodeView.from(node);
    }

    private GraphView graph(String nodeId, boolean upstream, int depth) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        // 调用方通常持有 catalogColumnId(资产详情页就是如此);血缘节点 ID 只在
        // 血缘内部使用。先按任一标识解析,找不到说明该字段还没有血缘,返回空图。
        LineageNode start = findNode(workspaceId, nodeId);
        if (start == null) {
            return new GraphView(List.of(), List.of());
        }
        nodeId = start.getId();
        int levels = Math.max(1, Math.min(depth, MAX_DEPTH));

        Set<String> visitedNodes = new LinkedHashSet<>();
        visitedNodes.add(nodeId);
        Set<String> frontier = Set.of(nodeId);
        Map<String, LineageEdge> edges = new LinkedHashMap<>();

        for (int level = 0; level < levels && !frontier.isEmpty(); level++) {
            List<LineageEdge> found = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                    .eq(LineageEdge::getWorkspaceId, workspaceId)
                    .eq(LineageEdge::getStatus, STATUS_ACTIVE)
                    .in(upstream ? LineageEdge::getToNodeId : LineageEdge::getFromNodeId,
                            frontier));
            Set<String> next = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                edges.put(edge.getId(), edge);
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                if (visitedNodes.add(other)) {
                    next.add(other);
                }
            }
            frontier = next;
        }

        List<LineageNode> nodes = visitedNodes.isEmpty() ? List.of()
                : nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .in(LineageNode::getId, visitedNodes));
        return new GraphView(nodes.stream().map(NodeView::from).toList(),
                edges.values().stream().map(EdgeView::from).toList());
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private LineageJob upsertJob(String workspaceId, JobRequest request, String jobType) {
        LineageJob job = jobMapper.selectOne(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getWorkspaceId, workspaceId)
                .eq(LineageJob::getJobType, jobType)
                .eq(LineageJob::getName, request.name().trim()));
        Instant now = Instant.now();
        if (job == null) {
            job = new LineageJob();
            job.setId(Ids.of("linj"));
            job.setWorkspaceId(workspaceId);
            job.setJobType(jobType);
            job.setName(request.name().trim());
            job.setCreatedAt(now);
            job.setLastSeenAt(now);
            job.setSourceRef(request.sourceRef());
            job.setSqlHash(request.sqlHash());
            jobMapper.insert(job);
        } else {
            job.setLastSeenAt(now);
            job.setSourceRef(request.sourceRef());
            job.setSqlHash(request.sqlHash());
            jobMapper.updateById(job);
        }
        return job;
    }

    private LineageNode upsertNode(String workspaceId, NodeRef ref) {
        if (ref == null || ref.qualifiedName() == null || ref.qualifiedName().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘节点 qualifiedName 不能为空");
        }
        String type = ref.nodeType() == null || ref.nodeType().isBlank()
                ? "COLUMN" : ref.nodeType().trim().toUpperCase();
        String qualifiedName = ref.qualifiedName().trim();
        LineageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, type)
                .eq(LineageNode::getQualifiedName, qualifiedName));
        if (node == null) {
            node = new LineageNode();
            node.setId(Ids.of("lin"));
            node.setWorkspaceId(workspaceId);
            node.setNodeType(type);
            node.setQualifiedName(qualifiedName);
            node.setCreatedAt(Instant.now());
            node.setCatalogDatasetId(ref.catalogDatasetId());
            node.setCatalogColumnId(ref.catalogColumnId());
            node.setDisplayName(ref.displayName() == null ? qualifiedName : ref.displayName());
            nodeMapper.insert(node);
        } else {
            boolean changed = false;
            if (ref.catalogDatasetId() != null
                    && !Objects.equals(ref.catalogDatasetId(), node.getCatalogDatasetId())) {
                node.setCatalogDatasetId(ref.catalogDatasetId());
                changed = true;
            }
            if (ref.catalogColumnId() != null
                    && !Objects.equals(ref.catalogColumnId(), node.getCatalogColumnId())) {
                node.setCatalogColumnId(ref.catalogColumnId());
                changed = true;
            }
            if (ref.displayName() != null && !Objects.equals(ref.displayName(), node.getDisplayName())) {
                node.setDisplayName(ref.displayName());
                changed = true;
            }
            if (changed) {
                nodeMapper.updateById(node);
            }
        }
        return node;
    }

    private LineageNode findNode(String workspaceId, String id) {
        // 历史数据里同一个 catalogColumnId 可能对应多条节点（例如字段被重命名后
        // 作业又上报了一次）。selectOne 会在这种数据上直接抛 TooManyResults，
        // 导致整个血缘页 500；这里明确优先精确的节点 ID，再退到字段 ID。
        List<LineageNode> matches = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .and(w -> w.eq(LineageNode::getId, id)
                        .or().eq(LineageNode::getCatalogColumnId, id)));
        return matches.stream()
                .filter(node -> id.equals(node.getId()))
                .findFirst()
                .or(() -> matches.stream()
                        .filter(node -> id.equals(node.getCatalogColumnId()))
                        .findFirst())
                .orElse(null);
    }

    private LineageNode requireNode(String nodeId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getId, nodeId)
                .eq(LineageNode::getWorkspaceId, workspaceId));
        if (node == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, nodeId);
        }
        return node;
    }

    private void validateEdge(EdgeInput input) {
        if (input == null || input.from() == null || input.to() == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边必须同时有 from 与 to");
        }
        String from = input.from().qualifiedName();
        String to = input.to().qualifiedName();
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘节点的 qualifiedName 不能为空");
        }
        if (from.trim().equals(to.trim())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边的 from 与 to 不能相同");
        }
    }

    private static void applyEdge(LineageEdge edge, EdgeInput input, String sqlHash) {
        edge.setTransformExpr(input.transformExpr());
        edge.setConfidence(input.confidence() == null ? 1.0 : input.confidence());
        edge.setOrigin(input.origin() == null || input.origin().isBlank()
                ? "AUTO" : input.origin().trim().toUpperCase());
        edge.setSqlHash(sqlHash);
        edge.setDiscoveredAt(Instant.now());
        edge.setStatus(STATUS_ACTIVE);
    }

    private static String relation(String relation) {
        return relation == null || relation.isBlank() ? "DERIVE" : relation.trim().toUpperCase();
    }

    private static String key(String from, String to, String relation) {
        return from + "|" + to + "|" + relation;
    }
}
