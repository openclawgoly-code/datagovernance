package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.domain.RuleKind;
import com.datagov.metadata.dto.RuleDtos.RuleUpsertRequest;
import com.datagov.metadata.dto.RuleDtos.RuleView;
import com.datagov.metadata.entity.RuleEntity;
import com.datagov.metadata.mapper.RuleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 规则定义管理(功能 17 的定义侧)。
 *
 * <p>规则本身不执行任何东西 —— 它只是一份"该怎么变换"的声明。执行归 Runtime,
 * 这条分工是架构风险 R6 的落点:任务<b>引用</b> ruleId,而不是把变换逻辑抄一份
 * 进自己的配置。
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private static final TypeReference<Map<String, String>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final RuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public RuleService(RuleMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RuleView create(RuleUpsertRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        requireNameAvailable(workspaceId, request.name(), null);
        validateParams(request.kind(), request.params());

        Instant now = Instant.now();
        RuleEntity entity = new RuleEntity();
        entity.setId(Ids.of("rule"));
        entity.setWorkspaceId(workspaceId);
        entity.setName(request.name().trim());
        entity.setKind(request.kind());
        entity.setDescription(request.description());
        entity.setParamsJson(writeJson(request.params()));
        entity.setReferenceCount(0);
        entity.setCreatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);
        entity.setDeleted(false);
        ruleMapper.insert(entity);

        log.info("规则已创建 workspace={} id={} kind={}", workspaceId, entity.getId(), request.kind());
        return toView(entity);
    }

    /**
     * 修改规则。
     *
     * <p>种类不可变更:换种类等于换了一条规则,参数结构全变,而引用它的那些任务
     * 会在下一次执行时得到完全不同的行为 —— 且没有任何人被告知。
     *
     * <p>参数<b>可以</b>改,并且立刻对所有引用它的任务生效。这正是"引用而非内嵌"
     * 想要的效果:改一处,所有地方跟上。但因此修改要谨慎,界面上会提示引用数。
     */
    @Transactional
    public RuleView update(String id, RuleUpsertRequest request) {
        RuleEntity entity = requireInWorkspace(id);
        if (request.kind() != entity.getKind()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "规则种类不可变更,请新建规则。当前 %s,请求 %s"
                            .formatted(entity.getKind(), request.kind()));
        }
        requireNameAvailable(entity.getWorkspaceId(), request.name(), id);
        validateParams(request.kind(), request.params());

        entity.setName(request.name().trim());
        entity.setDescription(request.description());
        entity.setParamsJson(writeJson(request.params()));
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(WorkspaceContext.require().userId());
        ruleMapper.updateById(entity);

        if (entity.getReferenceCount() != null && entity.getReferenceCount() > 0) {
            log.info("规则 {} 已修改,{} 个任务将在下次执行时使用新参数",
                    id, entity.getReferenceCount());
        }
        return toView(entity);
    }

    /**
     * 删除规则。
     *
     * <p>被引用的规则不许删 —— 删了之后引用它的任务会在执行时找不到规则,
     * 而那种失败发生在凌晨的调度里。宁可让用户先去解除引用。
     */
    @Transactional
    public void delete(String id) {
        RuleEntity entity = requireInWorkspace(id);
        if (entity.getReferenceCount() != null && entity.getReferenceCount() > 0) {
            throw BizException.conflict(ErrorCode.MTD_RULE_IN_USE,
                    "%s(被 %d 个任务引用)".formatted(entity.getName(), entity.getReferenceCount()));
        }
        ruleMapper.deleteById(id);
        log.info("规则已删除 workspace={} id={}", entity.getWorkspaceId(), id);
    }

    public List<RuleView> list(RuleKind kind) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return ruleMapper.selectList(new LambdaQueryWrapper<RuleEntity>()
                        .eq(RuleEntity::getWorkspaceId, workspaceId)
                        .eq(kind != null, RuleEntity::getKind, kind)
                        .orderByDesc(RuleEntity::getUpdatedAt)).stream()
                .map(this::toView).toList();
    }

    public RuleView get(String id) {
        return toView(requireInWorkspace(id));
    }

    /**
     * 按 ID 批量取规则,供执行期组装规则链。
     *
     * <p>不带租户校验 —— 调用方是执行引擎线程,那里没有 WorkspaceContext。
     * 用 workspaceId 显式过滤,而不是"信任传进来的 ID 都是对的"。
     */
    public List<RuleView> loadForExecution(String workspaceId, List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.of();
        }
        List<RuleEntity> found = ruleMapper.selectList(new LambdaQueryWrapper<RuleEntity>()
                .eq(RuleEntity::getWorkspaceId, workspaceId)
                .in(RuleEntity::getId, ruleIds));

        // 按传入顺序返回:规则的应用顺序有意义(先去空格再判空 ≠ 反过来),
        // 而 IN 查询的返回顺序是数据库定的
        Map<String, RuleEntity> byId = found.stream()
                .collect(java.util.stream.Collectors.toMap(RuleEntity::getId, r -> r));
        return ruleIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(this::toView).toList();
    }

    /** 引用计数增减。由 Control 在任务定义保存时调用。 */
    @Transactional
    public void adjustReferences(String workspaceId, List<String> ruleIds, int delta) {
        for (String ruleId : ruleIds) {
            RuleEntity entity = ruleMapper.selectOne(new LambdaQueryWrapper<RuleEntity>()
                    .eq(RuleEntity::getId, ruleId)
                    .eq(RuleEntity::getWorkspaceId, workspaceId));
            if (entity == null) {
                continue;
            }
            int next = Math.max(0, (entity.getReferenceCount() == null ? 0
                    : entity.getReferenceCount()) + delta);
            entity.setReferenceCount(next);
            ruleMapper.updateById(entity);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 参数校验。
     *
     * <p>只校验必填与已知键。<b>不</b>拒绝未知键:后端加了新参数而前端还没更新时,
     * 严格拒绝会让整个规则保存不了。未知键原样存着,解释器忽略它。
     */
    private void validateParams(RuleKind kind, Map<String, String> params) {
        Map<String, String> effective = params == null ? Map.of() : params;
        List<String> missing = kind.requiredParams().stream()
                .filter(key -> {
                    String value = effective.get(key);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "%s 规则缺少必填参数: %s".formatted(kind.displayName(), String.join("、", missing)),
                    kind.paramSpec().entrySet().stream()
                            .filter(e -> missing.contains(e.getKey()))
                            .map(e -> e.getKey() + " —— " + e.getValue())
                            .reduce((a, b) -> a + "; " + b).orElse(null));
        }

        // 解密规则的密钥必须是凭据引用而不是明文 —— 这一条值得单独拦:
        // 一份明文密钥躺进 md_rule.params_json,而规则定义是所有人都能看的
        if (kind == RuleKind.DECRYPT) {
            String credentialId = effective.get("credentialId");
            if (credentialId != null && !credentialId.startsWith("cred_")) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "解密规则的 credentialId 必须是凭据引用,不能直接填密钥",
                        "请先在凭据管理里创建密钥,再引用它的 ID");
            }
        }
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = ruleMapper.selectCount(new LambdaQueryWrapper<RuleEntity>()
                .eq(RuleEntity::getWorkspaceId, workspaceId)
                .eq(RuleEntity::getName, name.trim())
                .ne(excludeId != null, RuleEntity::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.MTD_RULE_NAME_DUPLICATED, name);
        }
    }

    private RuleEntity requireInWorkspace(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        RuleEntity entity = ruleMapper.selectOne(new LambdaQueryWrapper<RuleEntity>()
                .eq(RuleEntity::getId, id)
                .eq(RuleEntity::getWorkspaceId, workspaceId));
        if (entity == null) {
            throw BizException.notFound(ErrorCode.MTD_RULE_NOT_FOUND, id);
        }
        return entity;
    }

    private RuleView toView(RuleEntity entity) {
        return RuleView.from(entity, parseParams(entity.getParamsJson()));
    }

    public Map<String, String> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(paramsJson, PARAMS_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "规则参数不是合法的 JSON 对象", e.getMessage(), e);
        }
    }

    private String writeJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "规则参数序列化失败",
                    e.getMessage(), e);
        }
    }
}
