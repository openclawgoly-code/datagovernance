package com.datagov.app.runner;

import com.datagov.metadata.dto.RuleDtos.RuleView;
import com.datagov.metadata.service.RuleService;
import com.datagov.runtime.rule.RuleInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把物理计划里的 ruleId 解析成可执行的规则链(功能 17)。
 *
 * <p>三个 runner(离线同步、文件解析、接口解析)都要做这件事,所以提出来。
 *
 * <p>计划里存的是 ID 而不是规则内容 —— 这是架构风险 R6 的落点:改一条规则,
 * 所有引用它的任务下次执行时自动跟上,而不需要重新编译几十个任务。代价是
 * 执行期多一次查询,值得:内嵌规则内容意味着同一条脱敏规则会在十几个任务的
 * 计划里各存一份,修一处漏九处。
 */
@Component
public class RuleResolver {

    private static final Logger log = LoggerFactory.getLogger(RuleResolver.class);

    private final RuleService ruleService;

    public RuleResolver(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** @param raw 计划里的 {@code fieldRules} 段:{ 字段名: [ruleId...] } */
    public Map<String, List<RuleInterpreter.Rule>> resolve(String workspaceId, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, List<RuleInterpreter.Rule>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String field = String.valueOf(entry.getKey());
            List<String> ruleIds = toStringList(entry.getValue());
            if (ruleIds.isEmpty()) {
                continue;
            }
            List<RuleView> rules = ruleService.loadForExecution(workspaceId, ruleIds);
            if (rules.size() < ruleIds.size()) {
                // 规则被删了。删除保护本该拦住这种情况,走到这里说明保护被绕过
                // (直接改库、或引用计数失准)。宁可失败也不要静默少套一条规则 ——
                // 少套的那条可能正是脱敏。
                throw new IllegalStateException("字段 %s 引用的规则有 %d 条已不存在"
                        .formatted(field, ruleIds.size() - rules.size()));
            }
            result.put(field, rules.stream()
                    .map(r -> new RuleInterpreter.Rule(r.kind().name(), r.params()))
                    .toList());
        }
        if (!result.isEmpty()) {
            log.info("已解析 {} 个字段的规则链", result.size());
        }
        return result;
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf)
                .filter(s -> !s.isBlank()).toList();
    }
}
