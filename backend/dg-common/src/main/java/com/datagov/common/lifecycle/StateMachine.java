package com.datagov.common.lifecycle;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 极小的显式状态机。
 *
 * <p>存在的理由不是"框架好用",而是架构文档 E 章节要求 7 个核心对象的状态机满足:
 * 每个状态可达、每条迁移有触发器、每个终态有定义。把迁移声明成数据之后,
 * 这三条就能被<b>测试直接断言</b>,而不是停留在文档里的承诺。
 *
 * <p>每条迁移都必须带一个 trigger 名(Command 或 Event 的名字),
 * 这样 F 章节(Command/Query/Event)与 E 章节(状态机)之间不会各说各话。
 *
 * @param <S> 状态枚举
 */
public final class StateMachine<S extends Enum<S>> {

    /**
     * 一条状态迁移。
     *
     * @param from    起始状态
     * @param to      目标状态
     * @param trigger 触发它的 Command 或 Event 名,例如 {@code "TestDataSourceConnection"}
     */
    public record Transition<S extends Enum<S>>(S from, S to, String trigger) {
    }

    private final Class<S> stateType;
    private final S initial;
    private final Set<S> terminals;
    private final List<Transition<S>> transitions;

    private StateMachine(Class<S> stateType, S initial, Set<S> terminals, List<Transition<S>> transitions) {
        this.stateType = stateType;
        this.initial = initial;
        this.terminals = Set.copyOf(terminals);
        this.transitions = List.copyOf(transitions);
    }

    public static <S extends Enum<S>> Builder<S> builder(Class<S> stateType, S initial) {
        return new Builder<>(stateType, initial);
    }

    public S initial() {
        return initial;
    }

    public Set<S> terminals() {
        return terminals;
    }

    public List<Transition<S>> transitions() {
        return transitions;
    }

    public boolean canTransition(S from, S to) {
        return transitions.stream().anyMatch(t -> t.from() == from && t.to() == to);
    }

    /** 校验一次迁移,不合法则抛 409。 */
    public void checkTransition(S from, S to) {
        if (!canTransition(from, to)) {
            throw new BizException(
                    ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "不允许的状态迁移: %s -> %s".formatted(from, to),
                    "允许的后继状态: " + nextStates(from));
        }
    }

    public Set<S> nextStates(S from) {
        Set<S> next = new LinkedHashSet<>();
        for (Transition<S> t : transitions) {
            if (t.from() == from) {
                next.add(t.to());
            }
        }
        return next;
    }

    /** 从初始状态出发可达的状态集合。用于断言"没有孤岛状态"。 */
    public Set<S> reachableStates() {
        Set<S> seen = new LinkedHashSet<>();
        Deque<S> queue = new ArrayDeque<>();
        seen.add(initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            S current = queue.poll();
            for (S next : nextStates(current)) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /**
     * 完整性自检,对应架构验证第 4 条。返回违规描述列表,空列表代表通过。
     *
     * <p>检查三件事:
     * <ol>
     *   <li>枚举里声明的每个状态都从初始状态可达 —— 不存在写了却永远进不去的状态</li>
     *   <li>每个非终态至少有一条出边 —— 不存在未声明为终态却走不出去的死状态</li>
     *   <li>每个终态没有出边 —— 终态名副其实</li>
     * </ol>
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();
        Set<S> reachable = reachableStates();

        for (S state : EnumSet.allOf(stateType)) {
            if (!reachable.contains(state)) {
                violations.add("状态不可达: " + state);
            }
        }
        for (S state : EnumSet.allOf(stateType)) {
            boolean terminal = terminals.contains(state);
            boolean hasOutgoing = !nextStates(state).isEmpty();
            if (!terminal && !hasOutgoing) {
                violations.add("非终态但无任何后继迁移(死状态): " + state);
            }
            if (terminal && hasOutgoing) {
                violations.add("已声明为终态却仍有后继迁移: " + state + " -> " + nextStates(state));
            }
        }
        for (Transition<S> t : transitions) {
            if (t.trigger() == null || t.trigger().isBlank()) {
                violations.add("迁移缺少触发器名: " + t.from() + " -> " + t.to());
            }
        }
        return violations;
    }

    public static final class Builder<S extends Enum<S>> {
        private final Class<S> stateType;
        private final S initial;
        private final List<Transition<S>> transitions = new ArrayList<>();
        private final Set<S> terminals = new LinkedHashSet<>();

        private Builder(Class<S> stateType, S initial) {
            this.stateType = stateType;
            this.initial = initial;
        }

        /** 声明一条迁移及其触发器(Command 或 Event 名)。 */
        public Builder<S> allow(S from, S to, String trigger) {
            transitions.add(new Transition<>(from, to, trigger));
            return this;
        }

        /** 声明终态。终态不得再有出边,由 {@link #validate()} 强制。 */
        public Builder<S> terminal(S state) {
            terminals.add(state);
            return this;
        }

        public StateMachine<S> build() {
            return new StateMachine<>(stateType, initial, terminals, transitions);
        }
    }
}
