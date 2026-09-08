package com.datagov.metadata.dto;

import com.datagov.metadata.domain.RuleKind;
import com.datagov.metadata.entity.RuleEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/** 规则(功能 17)的对外 DTO。 */
public final class RuleDtos {

    private RuleDtos() {
    }

    public record RuleUpsertRequest(
            @NotBlank(message = "规则名称不能为空")
            @Size(max = 128, message = "规则名称不得超过 128 字符")
            String name,

            @NotNull(message = "规则种类不能为空")
            RuleKind kind,

            @Size(max = 512, message = "描述不得超过 512 字符")
            String description,

            Map<String, String> params
    ) {

        public RuleUpsertRequest {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    public record RuleView(
            String id,
            String name,
            RuleKind kind,
            String kindDisplayName,
            String category,
            String categoryDisplayName,
            String description,
            Map<String, String> params,
            /** 被多少个任务引用。>0 时不允许删除 */
            Integer referenceCount,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy
    ) {

        public static RuleView from(RuleEntity entity, Map<String, String> params) {
            RuleKind kind = entity.getKind();
            return new RuleView(
                    entity.getId(), entity.getName(), kind,
                    kind == null ? null : kind.displayName(),
                    kind == null ? null : kind.category().name(),
                    kind == null ? null : kind.category().displayName(),
                    entity.getDescription(), params, entity.getReferenceCount(),
                    entity.getCreatedAt(), entity.getCreatedBy(),
                    entity.getUpdatedAt(), entity.getUpdatedBy());
        }
    }

    /**
     * 规则种类的元数据。
     *
     * <p>参数表单由后端下发的 paramSpec 渲染 —— 前端不硬编码每种规则有哪些字段,
     * 加一种规则时只改后端枚举,界面自动跟上。
     */
    public record RuleKindInfo(
            String kind,
            String displayName,
            String category,
            String categoryDisplayName,
            Map<String, String> paramSpec,
            java.util.List<String> requiredParams
    ) {

        public static RuleKindInfo of(RuleKind kind) {
            return new RuleKindInfo(kind.name(), kind.displayName(),
                    kind.category().name(), kind.category().displayName(),
                    kind.paramSpec(), kind.requiredParams());
        }
    }
}
