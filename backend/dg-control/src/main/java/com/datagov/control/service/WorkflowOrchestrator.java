package com.datagov.control.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.workflow.ConditionEvaluator;
import com.datagov.control.workflow.WorkflowGraph;
import com.datagov.control.workflow.WorkflowGraph.Condition;
import com.datagov.control.workflow.WorkflowGraph.Edge;
import com.datagov.control.workflow.WorkflowGraph.Node;
import com.datagov.control.workflow.WorkflowGraph.NodeKind;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.dto.DispatchCommand;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流推进(序号 22 / 23)。
 *
 * <p><b>条件节点在这里求值,不下发 Runtime</b>(架构约束)。Runtime 的
 * must_not_do 第一条是「不得解释业务语义」,而"上游写了 0 行所以跳过下游"
 * 就是业务语义。所以推进逻辑住在 Control:它读子执行的<b>事实</b>(状态、行数),
 * 算出下一步该跑谁,再把那个"谁"下发给 Runtime。
 *
 * <p>推进是<b>拉模式</b>的:定时轮询而不是让 Runtime 回调。回调看起来更实时,
 * 但它要求 Runtime 知道"我是某个工作流的第三个节点" —— 那就把编排语义泄露
 * 进了执行引擎。轮询的代价是几秒延迟,换来的是 Runtime 完全不知道工作流的存在。
 */
@Service
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final JobDefinitionService jobService;
    private final ExecutionService executionService;

    public WorkflowOrchestrator(JobDefinitionService jobService,
                                ExecutionService executionService) {
        this.jobService = jobService;
        this.executionService = executionService;
    }

    /**
     * 推进一个正在跑的工作流。
     *
     * @return 本轮新下发的节点数;0 表示没有可推进的(在等子执行,或已经结束)
     */
    @Transactional
    public int advance(String workflowExecutionId) {
        ExecutionView.Detail detail = executionService.get(workflowExecutionId);
        ExecutionView parent = detail.execution();
        if (parent.status().isTerminal()) {
            return 0;
        }

        WorkflowGraph graph = graphOf(parent);
        if (graph.nodes().isEmpty()) {
            return 0;
        }

        // 已经跑过的节点:节点 ID → 那次子执行
        Map<String, ExecutionView> done = new HashMap<>();
        Set<String> inFlight = new HashSet<>();
        for (ExecutionView child : executionService.listChildren(workflowExecutionId)) {
            String nodeId = child.workflowNodeId();
            if (nodeId == null) {
                continue;
            }
            if (child.status().isTerminal()) {
                done.put(nodeId, child);
            } else {
                inFlight.add(nodeId);
            }
        }

        // 有节点失败 → 整个工作流失败。不继续跑下游:下游多半依赖它的产出,
        // 让它们在错误的输入上跑完,比直接失败更难收拾
        for (ExecutionView child : done.values()) {
            if (child.status() == ExecutionStatus.FAILED
                    || child.status() == ExecutionStatus.TIMEOUT) {
                executionService.failFromOrchestrator(workflowExecutionId,
                        "节点「%s」执行失败".formatted(nodeNameOf(graph, child.workflowNodeId())));
                return 0;
            }
            if (child.status() == ExecutionStatus.CANCELED) {
                executionService.cancelFromOrchestrator(workflowExecutionId,
                        "节点「%s」已取消".formatted(nodeNameOf(graph, child.workflowNodeId())));
                return 0;
            }
        }

        // 条件节点在 Control 求值,不产生子执行 —— 它们只决定"往哪边走"
        Set<String> skipped = new HashSet<>();
        Map<String, Boolean> conditionResults = evaluateConditions(graph, done, skipped);

        List<Node> ready = readyNodes(graph, done, inFlight, skipped, conditionResults);
        if (ready.isEmpty()) {
            if (inFlight.isEmpty()) {
                // 没有在跑的,也没有能跑的 —— 工作流走完了
                executionService.succeedFromOrchestrator(workflowExecutionId,
                        "共执行 %d 个节点".formatted(done.size()));
            }
            return 0;
        }

        int dispatched = 0;
        for (Node node : ready) {
            if (dispatchNode(parent, node)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /**
     * 级联取消(序号 23 的明确要求)。
     *
     * <p>取消父执行 → 取消所有还在跑的子执行。少了这一步,界面上工作流显示
     * 「已取消」,而它的三个节点还在目标库上写数据 —— 那比不支持取消更糟。
     *
     * @return 被级联取消的子执行数
     */
    @Transactional
    public int cancelCascade(String workflowExecutionId) {
        int canceled = 0;
        for (ExecutionView child : executionService.listChildren(workflowExecutionId)) {
            if (child.status().isTerminal()) {
                continue;
            }
            // 子执行可能刚好在这一刻自己结束了 —— 竞态,不是错误。
            // cancelIfActive 把它变成一个返回值而不是异常:抛出来的话
            // Spring 会把这个事务标成 rollback-only,连带前面已经取消成功的
            // 那几个也一起回滚
            if (executionService.cancelIfActive(child.id())) {
                canceled++;
            }
        }
        log.info("工作流级联取消 parent={} 子执行={} 个", workflowExecutionId, canceled);
        return canceled;
    }

    // ── 推进的三个步骤 ──────────────────────────────────────────────────

    /**
     * 求值所有上游已就绪的条件节点。
     *
     * <p>不满足的分支上的节点进 skipped —— 它们不会被执行,但工作流不因此失败。
     * "条件不成立所以没跑"与"跑了但失败了"是两件事,监控上必须能分开。
     */
    private Map<String, Boolean> evaluateConditions(WorkflowGraph graph,
                                                    Map<String, ExecutionView> done,
                                                    Set<String> skipped) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        // 按拓扑序求值:一个条件节点的上游可能是另一个条件节点
        for (String nodeId : graph.topologicalOrder()) {
            Node node = graph.nodes().get(nodeId);
            if (node == null || node.kind() != NodeKind.CONDITION) {
                continue;
            }
            if (isSkipped(graph, nodeId, skipped, results)) {
                skipped.add(nodeId);
                continue;
            }
            ExecutionView upstream = upstreamExecution(graph, nodeId, done);
            if (upstream == null) {
                // 上游还没跑完,这个条件还不能求值
                continue;
            }
            boolean result;
            try {
                result = ConditionEvaluator.evaluate(node.condition(),
                        ConditionEvaluator.Facts.of(upstream.status().name(),
                                upstream.rowsWritten(), upstream.rowsRead()));
            } catch (IllegalArgumentException e) {
                // 判据不合法本该在编译期拦下。走到这里说明编译校验有漏,
                // 而此刻只能保守地判 false —— 不跑,好过跑错
                log.warn("条件节点求值失败 node={} reason={}", nodeId, e.getMessage());
                result = false;
            }
            results.put(nodeId, result);

            // 没被选中的那条分支上的下游全部跳过
            for (Edge edge : graph.outgoing(nodeId)) {
                boolean taken = result
                        ? Edge.TRUE.equals(edge.branch())
                        : Edge.FALSE.equals(edge.branch());
                if (!taken) {
                    markSubtreeSkipped(graph, edge.to(), skipped);
                }
            }
        }
        return results;
    }

    private List<Node> readyNodes(WorkflowGraph graph, Map<String, ExecutionView> done,
                                  Set<String> inFlight, Set<String> skipped,
                                  Map<String, Boolean> conditionResults) {
        List<Node> ready = new ArrayList<>();
        for (String nodeId : graph.topologicalOrder()) {
            Node node = graph.nodes().get(nodeId);
            if (node == null || node.kind() != NodeKind.TASK) {
                continue;
            }
            if (done.containsKey(nodeId) || inFlight.contains(nodeId) || skipped.contains(nodeId)) {
                continue;
            }
            if (upstreamSatisfied(graph, nodeId, done, skipped, conditionResults)) {
                ready.add(node);
            }
        }
        return ready;
    }

    private boolean dispatchNode(ExecutionView parent, Node node) {
        JobDefinition definition;
        try {
            definition = jobService.loadInternal(node.jobDefinitionId());
        } catch (BizException e) {
            executionService.failFromOrchestrator(parent.id(),
                    "节点「%s」引用的任务定义已不存在".formatted(node.name()));
            return false;
        }
        Map<String, Object> plan = jobService.readPhysicalPlan(definition);
        if (plan.isEmpty()) {
            executionService.failFromOrchestrator(parent.id(),
                    "节点「%s」引用的任务没有物理计划".formatted(node.name()));
            return false;
        }

        DispatchCommand command = new DispatchCommand(
                definition.getWorkspaceId(),
                definition.getJobType().runtimeType(),
                definition.getId(),
                definition.getName(),
                definition.getPlanDefVersion(),
                plan,
                null,
                definition.getTimeoutMs(),
                parent.id(),
                "WORKFLOW",
                parent.triggeredBy());

        ExecutionView child = executionService.dispatchNode(command, node.id());
        log.info("工作流下发节点 parent={} node={} execution={}",
                parent.id(), node.id(), child.id());
        return true;
    }

    // ── 图上的小问题 ────────────────────────────────────────────────────

    /** 一个节点的上游是否都已就绪(跑完或被跳过)。 */
    private boolean upstreamSatisfied(WorkflowGraph graph, String nodeId,
                                      Map<String, ExecutionView> done, Set<String> skipped,
                                      Map<String, Boolean> conditionResults) {
        List<Edge> incoming = graph.incoming(nodeId);
        if (incoming.isEmpty()) {
            return true;
        }
        for (Edge edge : incoming) {
            Node upstream = graph.nodes().get(edge.from());
            if (upstream == null) {
                return false;
            }
            if (upstream.kind() == NodeKind.CONDITION) {
                Boolean result = conditionResults.get(edge.from());
                if (result == null) {
                    return false;
                }
                boolean taken = result
                        ? Edge.TRUE.equals(edge.branch())
                        : Edge.FALSE.equals(edge.branch());
                if (!taken) {
                    // 这条边没被选中;若还有别的入边,靠那条来决定
                    continue;
                }
            } else if (!done.containsKey(edge.from()) && !skipped.contains(edge.from())) {
                return false;
            }
        }
        // 所有入边都被跳过 → 这个节点也该跳过,而不是"上游都就绪了"
        return incoming.stream().anyMatch(e -> !skipped.contains(e.from()));
    }

    private boolean isSkipped(WorkflowGraph graph, String nodeId, Set<String> skipped,
                              Map<String, Boolean> conditionResults) {
        List<Edge> incoming = graph.incoming(nodeId);
        if (incoming.isEmpty()) {
            return false;
        }
        return incoming.stream().allMatch(e -> skipped.contains(e.from()));
    }

    /** 把一个节点及其下游全部标记为跳过。 */
    private void markSubtreeSkipped(WorkflowGraph graph, String nodeId, Set<String> skipped) {
        if (!skipped.add(nodeId)) {
            return;
        }
        for (Edge edge : graph.outgoing(nodeId)) {
            // 汇聚点:还有别的活着的入边就不该跳过它
            boolean allUpstreamSkipped = graph.incoming(edge.to()).stream()
                    .allMatch(e -> skipped.contains(e.from()));
            if (allUpstreamSkipped) {
                markSubtreeSkipped(graph, edge.to(), skipped);
            }
        }
    }

    /** 条件节点取哪个上游的事实。多个上游时取第一个跑完的 —— 条件节点应当只有一个上游。 */
    private ExecutionView upstreamExecution(WorkflowGraph graph, String nodeId,
                                            Map<String, ExecutionView> done) {
        for (Edge edge : graph.incoming(nodeId)) {
            ExecutionView execution = done.get(edge.from());
            if (execution != null) {
                return execution;
            }
        }
        return null;
    }

    private static String nodeNameOf(WorkflowGraph graph, String nodeId) {
        Node node = graph.nodes().get(nodeId);
        return node == null ? nodeId : node.name();
    }

    @SuppressWarnings("unchecked")
    private WorkflowGraph graphOf(ExecutionView parent) {
        Map<String, Object> plan = executionService.planOf(parent.id());
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();

        if (plan.get("nodes") instanceof List<?> rawNodes) {
            for (Object item : rawNodes) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> n = (Map<String, Object>) map;
                Condition condition = null;
                if (n.get("condition") instanceof Map<?, ?> c) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    condition = new Condition(text(cm.get("source")), text(cm.get("operator")),
                            text(cm.get("value")));
                }
                nodes.add(new Node(text(n.get("id")), text(n.get("name")),
                        NodeKind.valueOf(text(n.getOrDefault("kind", "TASK"))),
                        text(n.get("jobDefinitionId")), condition));
            }
        }
        if (plan.get("edges") instanceof List<?> rawEdges) {
            for (Object item : rawEdges) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> e = (Map<String, Object>) map;
                    edges.add(new Edge(text(e.get("from")), text(e.get("to")),
                            text(e.get("branch"))));
                }
            }
        }
        return new WorkflowGraph(nodes, edges);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** 校验一个执行确实是工作流执行。取消接口用它决定要不要级联。 */
    public boolean isWorkflow(String executionId) {
        ExecutionView.Detail detail = executionService.get(executionId);
        return detail.execution().jobRefType() == JobType.WORKFLOW.runtimeType();
    }

    /** 空间校验后的推进入口,供定时器使用。 */
    public int advanceAs(String workspaceId, String executionId) {
        return WorkspaceContext.callAs(
                new com.datagov.common.tenant.Caller("system", "system", workspaceId, true,
                        Set.of()),
                () -> advance(executionId));
    }
}
