package com.datagov.control.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流的有向图(序号 22)。
 *
 * <p>这是一个<b>纯数据结构</b>:不碰数据库、不认识 Spring、不知道任务定义长什么样。
 * 拓扑排序与环检测是可以被穷举测试的算法,把它们和"从哪张表读节点"混在一起,
 * 就再也没法为"三个节点互相依赖"写一个两行的测试。
 *
 * <p>节点分两类,这个区分贯穿整个 P3:
 * <ul>
 *   <li><b>任务节点</b> —— 引用一个已发布的任务定义,执行时下发给 Runtime</li>
 *   <li><b>条件节点</b> —— 在 <b>Control 内部</b>求值,<b>不下发 Runtime</b>。
 *       Runtime 的 must_not_do 第一条是「不得解释业务语义」,而
 *       「上游写入行数 &gt; 0 就走左边」正是业务语义</li>
 * </ul>
 */
public final class WorkflowGraph {

    /** 节点种类。 */
    public enum NodeKind {
        /** 引用一个任务定义。六类任务节点都是它 —— 区别在被引用的定义的 jobType */
        TASK,
        /** 条件节点:在 Control 求值,按结果选择往哪条边走 */
        CONDITION
    }

    /**
     * 一个节点。
     *
     * @param jobDefinitionId TASK 节点引用的任务定义;CONDITION 节点为 null
     * @param condition       CONDITION 节点的判据;TASK 节点为 null
     */
    public record Node(
            String id,
            String name,
            NodeKind kind,
            String jobDefinitionId,
            Condition condition
    ) {
    }

    /**
     * 条件节点的判据。
     *
     * <p>刻意做成一个<b>封闭的小语言</b>而不是"填一段表达式":一段任意表达式
     * 意味着要在 Control 里跑一个求值器,而那个求值器迟早会被用来读业务数据 ——
     * 那是 Control 的 must_not_do 第一条。这里只允许对<b>上游执行的事实</b>取值。
     *
     * @param source   取值对象:UPSTREAM_STATUS / UPSTREAM_ROWS_WRITTEN / UPSTREAM_ROWS_READ
     * @param operator EQ / NE / GT / GTE / LT / LTE
     * @param value    比较值。STATUS 比字符串,ROWS 比数字
     */
    public record Condition(String source, String operator, String value) {

        public static final Set<String> SOURCES =
                Set.of("UPSTREAM_STATUS", "UPSTREAM_ROWS_WRITTEN", "UPSTREAM_ROWS_READ");
        public static final Set<String> OPERATORS =
                Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
    }

    /**
     * 一条边。
     *
     * @param branch 从条件节点出发时走哪个分支:TRUE / FALSE。
     *               从任务节点出发时为 null —— 任务节点只有一种"下一步"
     */
    public record Edge(String from, String to, String branch) {

        public static final String TRUE = "TRUE";
        public static final String FALSE = "FALSE";
    }

    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final Map<String, List<Edge>> outgoing;
    private final Map<String, List<Edge>> incoming;

    public WorkflowGraph(List<Node> nodeList, List<Edge> edgeList) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodeList) {
            byId.put(node.id(), node);
        }
        this.nodes = Map.copyOf(byId);
        this.edges = List.copyOf(edgeList);
        this.outgoing = new HashMap<>();
        this.incoming = new HashMap<>();
        for (Edge edge : edgeList) {
            outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
        }
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    public List<Edge> incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    /** 入度为 0 的节点 —— 工作流的起点。 */
    public List<Node> roots() {
        return nodes.values().stream()
                .filter(n -> incoming(n.id()).isEmpty())
                .toList();
    }

    /**
     * 拓扑排序。
     *
     * @return 排好序的节点 ID;<b>图里有环时返回空列表</b>,由调用方去查环
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String id : nodes.keySet()) {
            indegree.put(id, incoming(id).size());
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });

        List<String> order = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (Edge edge : outgoing(id)) {
                Integer left = indegree.computeIfPresent(edge.to(), (k, v) -> v - 1);
                if (left != null && left == 0) {
                    queue.add(edge.to());
                }
            }
        }
        return order.size() == nodes.size() ? order : List.of();
    }

    /**
     * 找出一个环上的节点。
     *
     * <p>报"存在环"没有用 —— 一个二十节点的工作流,用户需要知道是<b>哪三个</b>
     * 节点绕成了圈。所以这里返回具体的环,而不是一个布尔值。
     *
     * @return 环上的节点 ID(按环的顺序);无环时为空列表
     */
    public List<String> findCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        for (String id : nodes.keySet()) {
            List<String> cycle = dfsCycle(id, visited, onPath);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private List<String> dfsCycle(String id, Set<String> visited, Set<String> onPath) {
        if (onPath.contains(id)) {
            // 从环的入口开始截取,而不是把整条搜索路径都报出来
            List<String> path = new ArrayList<>(onPath);
            return path.subList(path.indexOf(id), path.size());
        }
        if (!visited.add(id)) {
            return List.of();
        }
        onPath.add(id);
        for (Edge edge : outgoing(id)) {
            List<String> cycle = dfsCycle(edge.to(), visited, onPath);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        onPath.remove(id);
        return List.of();
    }

    /** 从若干起点出发能到达的节点。用来找孤岛。 */
    public Set<String> reachableFrom(List<Node> starts) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        starts.forEach(n -> stack.push(n.id()));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!seen.add(id)) {
                continue;
            }
            outgoing(id).forEach(e -> stack.push(e.to()));
        }
        return seen;
    }
}
