package com.datagov.control.workflow;

import com.datagov.control.workflow.WorkflowGraph.Edge;
import com.datagov.control.workflow.WorkflowGraph.Node;
import com.datagov.control.workflow.WorkflowGraph.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流图的拓扑算法(序号 22)。
 *
 * <p>这些是纯算法,所以能被穷举 —— 这正是把它从 Service 里拆出来的收益。
 */
class WorkflowGraphTest {

    private static Node task(String id) {
        return new Node(id, "任务" + id, NodeKind.TASK, "job_" + id, null);
    }

    private static Edge edge(String from, String to) {
        return new Edge(from, to, null);
    }

    @Nested
    @DisplayName("拓扑排序")
    class Topology {

        @Test
        @DisplayName("链式图按顺序排出")
        void linearChain() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b"), task("c")),
                    List.of(edge("a", "b"), edge("b", "c")));
            assertThat(g.topologicalOrder()).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("菱形图里汇聚点排在两个分支之后")
        void diamond() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b"), task("c"), task("d")),
                    List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));
            List<String> order = g.topologicalOrder();
            assertThat(order).hasSize(4);
            assertThat(order.indexOf("d")).isGreaterThan(order.indexOf("b"));
            assertThat(order.indexOf("d")).isGreaterThan(order.indexOf("c"));
            assertThat(order.indexOf("a")).isZero();
        }

        @Test
        @DisplayName("有环时返回空 —— 排不出来就别给一个残缺的顺序")
        void cycleYieldsEmpty() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b")),
                    List.of(edge("a", "b"), edge("b", "a")));
            assertThat(g.topologicalOrder()).isEmpty();
        }

        @Test
        @DisplayName("孤立节点也在结果里 —— 没有边不等于不存在")
        void isolatedNodesIncluded() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("island")), List.of());
            assertThat(g.topologicalOrder()).containsExactlyInAnyOrder("a", "island");
        }
    }

    @Nested
    @DisplayName("环检测")
    class Cycles {

        @Test
        @DisplayName("无环时返回空")
        void noCycle() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b")), List.of(edge("a", "b")));
            assertThat(g.findCycle()).isEmpty();
        }

        @Test
        @DisplayName("报出环上的具体节点,而不是一个「存在环」的布尔值")
        void reportsCycleMembers() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b"), task("c")),
                    List.of(edge("a", "b"), edge("b", "c"), edge("c", "a")));
            // 二十个节点的工作流里,用户要知道是哪三个绕成了圈
            assertThat(g.findCycle()).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("自环也算环")
        void selfLoop() {
            WorkflowGraph g = new WorkflowGraph(List.of(task("a")), List.of(edge("a", "a")));
            assertThat(g.findCycle()).containsExactly("a");
        }

        @Test
        @DisplayName("只报环上的节点,不把通往环的前缀也算进去")
        void excludesPathPrefix() {
            // x → a → b → a:x 不在环上
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("x"), task("a"), task("b")),
                    List.of(edge("x", "a"), edge("a", "b"), edge("b", "a")));
            assertThat(g.findCycle()).containsExactlyInAnyOrder("a", "b").doesNotContain("x");
        }
    }

    @Nested
    @DisplayName("起点与可达性")
    class Reachability {

        @Test
        @DisplayName("入度为 0 的是起点")
        void rootsAreZeroIndegree() {
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b"), task("c")),
                    List.of(edge("a", "c"), edge("b", "c")));
            assertThat(g.roots()).extracting(Node::id).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("从起点到不了的节点是孤岛 —— 它永远不会被执行")
        void findsUnreachableIslands() {
            // a → b 是主干;island1 → island2 自成一体,但 island1 入度也是 0,
            // 所以它其实是另一个起点。真正的孤岛要靠环制造
            WorkflowGraph g = new WorkflowGraph(
                    List.of(task("a"), task("b"), task("x"), task("y")),
                    List.of(edge("a", "b"), edge("x", "y"), edge("y", "x")));
            var reachable = g.reachableFrom(g.roots());
            assertThat(reachable).contains("a", "b");
            // x 与 y 互相指,两个入度都不为 0,谁都不是起点 —— 于是都到不了
            assertThat(reachable).doesNotContain("x", "y");
        }
    }
}
