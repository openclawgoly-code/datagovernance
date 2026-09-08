package com.datagov.control.compile;

import com.datagov.control.domain.JobDefinitionStatus;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.mapper.JobDefinitionMapper;
import com.datagov.control.workflow.WorkflowGraph;
import com.datagov.control.workflow.WorkflowGraph.Condition;
import com.datagov.control.workflow.WorkflowGraph.Edge;
import com.datagov.control.workflow.WorkflowGraph.Node;
import com.datagov.control.workflow.WorkflowGraph.NodeKind;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流编排(序号 22)的编译器。
 *
 * <p>这是所有编译器里唯一<b>要读别的任务定义</b>的:节点引用的是任务定义,
 * 而"被引用的定义有没有发布"必须在编译期查清楚 —— 否则工作流跑到第七个节点
 * 才发现那个任务还是草稿,前六个节点的写入已经发生了。
 *
 * <p>校验分五层,每一层都能单独让编译失败:
 * <ol>
 *   <li>结构:节点非空、ID 唯一、边指向存在的节点</li>
 *   <li>拓扑:无环、无孤岛(从起点到不了的节点永远不会执行)</li>
 *   <li>条件节点:判据合法、恰好两条出边(TRUE / FALSE)</li>
 *   <li>引用:被引用的任务定义存在、已发布、不是工作流自己</li>
 *   <li>调度:与其他类型共用的 Cron 校验</li>
 * </ol>
 *
 * <p>配置形状:
 * <pre>
 * nodes: [{ id, name, kind: TASK|CONDITION, jobDefinitionId, condition: {source, operator, value} }]
 * edges: [{ from, to, branch: TRUE|FALSE }]
 * </pre>
 */
@Component
public class WorkflowCompiler implements JobCompiler {

    /**
     * 单个工作流的节点数上限。
     *
     * <p>不是技术限制,是可维护性限制:超过 50 个节点的 DAG 在界面上已经没法
     * 看了,而它出错时的排查成本会高到没人愿意碰。到那个规模应该拆成几个
     * 工作流,用"工作流节点引用工作流"来组合 —— 这也是本期不支持的,
     * 所以更该早点拦住。
     */
    static final int MAX_NODES = 50;

    private final JobDefinitionMapper jobMapper;

    public WorkflowCompiler(JobDefinitionMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    @Override
    public JobType jobType() {
        return JobType.WORKFLOW;
    }


    /**
     * 平台认识的配置键。不在这里的键会被警告 —— 一个拼错的键会让配置静默失效,
     * 而任务照常报告成功。
     */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "nodes", "edges");

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();
        CompilerSupport.warnUnknownKeys(collector, config, KNOWN_KEYS);
        String workspaceId = CompilerSupport.workspaceOf(context.definition());

        List<Node> nodes = parseNodes(config, collector);
        List<Edge> edges = parseEdges(config, collector);
        if (nodes.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes", "工作流里没有任何节点");
            return CompileResult.failure(collector.all());
        }
        if (nodes.size() > MAX_NODES) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes",
                    "节点数 %d 超过上限 %d".formatted(nodes.size(), MAX_NODES),
                    "拆成几个工作流会比一张巨图好维护");
        }

        Set<String> ids = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (!ids.add(node.id())) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes." + node.id(),
                        "节点 ID 重复: " + node.id());
            }
        }
        // 边指向不存在的节点:多半是删了节点没删边
        for (Edge edge : edges) {
            if (!ids.contains(edge.from())) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "edges",
                        "边的起点「%s」不是任何一个节点".formatted(edge.from()));
            }
            if (!ids.contains(edge.to())) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "edges",
                        "边的终点「%s」不是任何一个节点".formatted(edge.to()));
            }
        }
        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }

        WorkflowGraph graph = new WorkflowGraph(nodes, edges);
        validateTopology(graph, collector);
        validateConditionNodes(graph, collector);
        validateReferences(graph, context.definition(), workspaceId, collector);

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        return CompileResult.success(buildPlan(graph), collector.all());
    }

    // ── 拓扑 ────────────────────────────────────────────────────────────

    private void validateTopology(WorkflowGraph graph, CompileResult.Collector collector) {
        List<String> cycle = graph.findCycle();
        if (!cycle.isEmpty()) {
            // 报出环上的具体节点。「存在环」这三个字对一个二十节点的图毫无帮助
            collector.error(CompileStage.DAG_VALIDATION, "edges",
                    "存在循环依赖: " + String.join(" → ", cycle) + " → " + cycle.get(0),
                    "工作流必须是有向无环图,否则执行时无从决定谁先跑");
            return;
        }

        List<Node> roots = graph.roots();
        if (roots.isEmpty()) {
            collector.error(CompileStage.DAG_VALIDATION, "edges",
                    "没有起点节点(每个节点都有上游)");
            return;
        }

        Set<String> reachable = graph.reachableFrom(roots);
        List<String> islands = graph.nodes().keySet().stream()
                .filter(id -> !reachable.contains(id))
                .toList();
        if (!islands.isEmpty()) {
            collector.error(CompileStage.DAG_VALIDATION, "nodes",
                    "有 %d 个节点从起点到不了: %s".formatted(islands.size(), String.join(", ", islands)),
                    "它们永远不会被执行 —— 多半是漏连了一条边");
        }
    }

    // ── 条件节点 ────────────────────────────────────────────────────────

    private void validateConditionNodes(WorkflowGraph graph, CompileResult.Collector collector) {
        for (Node node : graph.nodes().values()) {
            String location = "nodes." + node.id();
            if (node.kind() == NodeKind.CONDITION) {
                validateCondition(node, location, collector);
                validateBranches(graph, node, location, collector);
            } else {
                // 任务节点的出边不该带分支标记 —— 它只有一种"下一步"
                boolean branded = graph.outgoing(node.id()).stream()
                        .anyMatch(e -> e.branch() != null && !e.branch().isBlank());
                if (branded) {
                    collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                            "任务节点「%s」的出边带了分支标记".formatted(node.name()),
                            "TRUE / FALSE 分支只有条件节点才有");
                }
            }
        }
    }

    private void validateCondition(Node node, String location, CompileResult.Collector collector) {
        Condition condition = node.condition();
        if (condition == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                    "条件节点「%s」没有配置判据".formatted(node.name()));
            return;
        }
        if (!Condition.SOURCES.contains(condition.source())) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".condition.source",
                    "未知的取值来源: " + condition.source(),
                    "可选:" + String.join(" / ", Condition.SOURCES));
        }
        if (!Condition.OPERATORS.contains(condition.operator())) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".condition.operator",
                    "未知的比较符: " + condition.operator(),
                    "可选:" + String.join(" / ", Condition.OPERATORS));
        }
        // 状态上的大小比较:编译期就该拦下,而不是等执行到那一步才抛
        if ("UPSTREAM_STATUS".equals(condition.source())
                && !Set.of("EQ", "NE").contains(condition.operator())) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".condition.operator",
                    "状态只能用 EQ / NE 比较,不能用 " + condition.operator(),
                    "想比行数的话,取值来源要选 UPSTREAM_ROWS_WRITTEN");
        }
        if (condition.source() != null && condition.source().startsWith("UPSTREAM_ROWS")) {
            try {
                Long.parseLong(condition.value() == null ? "" : condition.value().trim());
            } catch (NumberFormatException e) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".condition.value",
                        "行数条件的比较值必须是整数: " + condition.value());
            }
        }
    }

    private void validateBranches(WorkflowGraph graph, Node node, String location,
                                  CompileResult.Collector collector) {
        List<Edge> out = graph.outgoing(node.id());
        Set<String> branches = new HashSet<>();
        for (Edge edge : out) {
            String branch = edge.branch();
            if (branch == null || !Set.of(Edge.TRUE, Edge.FALSE).contains(branch)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                        "条件节点「%s」的出边必须标 TRUE 或 FALSE,当前是: %s"
                                .formatted(node.name(), branch));
                continue;
            }
            if (!branches.add(branch)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                        "条件节点「%s」有多条 %s 分支".formatted(node.name(), branch),
                        "一个判断只能有一个去向,否则执行时不知道该走哪条");
            }
        }
        if (out.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                    "条件节点「%s」没有出边 —— 判断完了无处可去".formatted(node.name()));
        } else if (!branches.contains(Edge.TRUE)) {
            // 只有 FALSE 分支是合法的("不满足才做点什么"),但它更可能是漏配。
            // 所以是 WARNING 而不是 ERROR:拦住它会挡掉一个真实的用法
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, location,
                    "条件节点「%s」没有 TRUE 分支".formatted(node.name()),
                    "条件成立时工作流会在这里结束 —— 如果这不是本意,补一条 TRUE 边");
        }
    }

    // ── 引用 ────────────────────────────────────────────────────────────

    private void validateReferences(WorkflowGraph graph, JobDefinition self, String workspaceId,
                                    CompileResult.Collector collector) {
        List<String> referenced = graph.nodes().values().stream()
                .filter(n -> n.kind() == NodeKind.TASK)
                .map(Node::jobDefinitionId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (referenced.isEmpty()
                && graph.nodes().values().stream().anyMatch(n -> n.kind() == NodeKind.TASK)) {
            collector.error(CompileStage.DEPENDENCY_VALIDATION, "nodes",
                    "有任务节点没有引用任何任务定义");
        }
        if (referenced.isEmpty()) {
            return;
        }

        Map<String, JobDefinition> found = new LinkedHashMap<>();
        for (JobDefinition def : jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .in(JobDefinition::getId, referenced))) {
            found.put(def.getId(), def);
        }

        for (Node node : graph.nodes().values()) {
            if (node.kind() != NodeKind.TASK) {
                continue;
            }
            String location = "nodes." + node.id();
            String refId = node.jobDefinitionId();
            if (refId == null || refId.isBlank()) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "任务节点「%s」没有选择任务定义".formatted(node.name()));
                continue;
            }
            // 引用自己:一个工作流把自己当成节点,执行时会无限展开
            if (refId.equals(self.getId())) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "节点「%s」引用了工作流自己".formatted(node.name()),
                        "那会在执行时无限展开");
                continue;
            }
            JobDefinition ref = found.get(refId);
            if (ref == null) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "节点「%s」引用的任务定义不存在: %s".formatted(node.name(), refId));
                continue;
            }
            // 本期不支持工作流套工作流:级联取消与超时归属都还没有答案
            if (ref.getJobType() == JobType.WORKFLOW) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "节点「%s」引用了另一个工作流".formatted(node.name()),
                        "本期不支持工作流嵌套");
            }
            if (!isRunnable(ref.getStatus())) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "节点「%s」引用的任务「%s」还没发布(当前 %s)"
                                .formatted(node.name(), ref.getName(),
                                        ref.getStatus() == null ? "未知" : ref.getStatus().displayName()),
                        "工作流跑到这一步才发现,前面节点的写入已经发生了");
            } else if (!planUpToDate(ref)) {
                // 引用的任务改过定义但没重新编译 —— 工作流会跑一份过期的计划
                collector.error(CompileStage.DEPENDENCY_VALIDATION, location,
                        "节点「%s」引用的任务「%s」改过定义但没重新编译发布"
                                .formatted(node.name(), ref.getName()));
            }
        }
    }

    private static boolean isRunnable(JobDefinitionStatus status) {
        return status == JobDefinitionStatus.PUBLISHED
                || status == JobDefinitionStatus.SCHEDULING
                || status == JobDefinitionStatus.PAUSED;
    }

    private static boolean planUpToDate(JobDefinition def) {
        return def.getPlanDefVersion() != null
                && def.getPlanDefVersion().equals(def.getVersion());
    }

    // ── 物理计划 ────────────────────────────────────────────────────────

    /**
     * 编译产物。
     *
     * <p>带上拓扑序:执行时按它推进,不必再算一遍。更重要的是这份顺序被
     * <b>钉在计划里</b> —— 一个月后回看这次执行,能确定当时是按什么顺序跑的。
     */
    private Map<String, Object> buildPlan(WorkflowGraph graph) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "WORKFLOW");
        plan.put("topologicalOrder", graph.topologicalOrder());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Node node : graph.nodes().values()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.id());
            n.put("name", node.name());
            n.put("kind", node.kind().name());
            n.put("jobDefinitionId", node.jobDefinitionId());
            if (node.condition() != null) {
                n.put("condition", Map.of(
                        "source", node.condition().source(),
                        "operator", node.condition().operator(),
                        "value", node.condition().value() == null ? "" : node.condition().value()));
            }
            nodes.add(n);
        }
        plan.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", edge.from());
            e.put("to", edge.to());
            e.put("branch", edge.branch());
            edges.add(e);
        }
        plan.put("edges", edges);
        return plan;
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Node> parseNodes(Map<String, Object> config, CompileResult.Collector collector) {
        Object raw = config.get("nodes");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Node> nodes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) map;
            String id = text(node.get("id"));
            if (id == null || id.isBlank()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes", "有节点没有 ID");
                continue;
            }
            NodeKind kind;
            try {
                kind = NodeKind.valueOf(text(node.getOrDefault("kind", "TASK")));
            } catch (IllegalArgumentException e) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes." + id,
                        "未知的节点类型: " + node.get("kind"), "可选:TASK / CONDITION");
                continue;
            }
            nodes.add(new Node(id, text(node.getOrDefault("name", id)), kind,
                    text(node.get("jobDefinitionId")), parseCondition(node.get("condition"))));
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private Condition parseCondition(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> c = (Map<String, Object>) map;
        return new Condition(text(c.get("source")), text(c.get("operator")), text(c.get("value")));
    }

    @SuppressWarnings("unchecked")
    private List<Edge> parseEdges(Map<String, Object> config, CompileResult.Collector collector) {
        Object raw = config.get("edges");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Edge> edges = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> edge = (Map<String, Object>) map;
            String from = text(edge.get("from"));
            String to = text(edge.get("to"));
            if (from == null || to == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "edges", "有边缺少起点或终点");
                continue;
            }
            edges.add(new Edge(from, to, text(edge.get("branch"))));
        }
        return edges;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
