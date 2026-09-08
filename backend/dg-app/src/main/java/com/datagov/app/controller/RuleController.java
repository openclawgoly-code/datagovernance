package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.metadata.domain.RuleKind;
import com.datagov.metadata.dto.RuleDtos.RuleKindInfo;
import com.datagov.metadata.dto.RuleDtos.RuleUpsertRequest;
import com.datagov.metadata.dto.RuleDtos.RuleView;
import com.datagov.metadata.service.RuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 清洗 / 转换规则(功能 17)。
 *
 * <p>这里只管<b>定义</b>。执行归 Runtime 的 RuleInterpreter —— 规则是双栖对象,
 * 而这个控制器只暴露定义的那一半。
 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "规则管理", description = "清洗与转换规则的定义(功能 17)")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * 规则种类元数据。
     *
     * <p>参数表单由 paramSpec 渲染 —— 前端不硬编码每种规则有哪些字段,
     * 加一种规则时只改后端枚举,界面自动跟上。
     */
    @GetMapping("/kinds")
    @Operation(summary = "规则种类及其参数规格")
    public ApiResponse<List<RuleKindInfo>> kinds() {
        return ApiResponse.ok(Arrays.stream(RuleKind.values()).map(RuleKindInfo::of).toList());
    }

    @GetMapping
    @RequirePermission("metadata:rule:read")
    @Operation(summary = "规则列表")
    public ApiResponse<List<RuleView>> list(@RequestParam(required = false) RuleKind kind) {
        return ApiResponse.ok(ruleService.list(kind));
    }

    @GetMapping("/{id}")
    @RequirePermission("metadata:rule:read")
    @Operation(summary = "查询单条规则")
    public ApiResponse<RuleView> get(@PathVariable String id) {
        return ApiResponse.ok(ruleService.get(id));
    }

    @PostMapping
    @RequirePermission("metadata:rule:manage")
    @Operation(summary = "新建规则")
    public ApiResponse<RuleView> create(@Valid @RequestBody RuleUpsertRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission("metadata:rule:manage")
    @Operation(summary = "编辑规则",
            description = "参数改动立刻对所有引用它的任务生效 —— 这正是「引用而非内嵌」的效果")
    public ApiResponse<RuleView> update(@PathVariable String id,
                                        @Valid @RequestBody RuleUpsertRequest request) {
        return ApiResponse.ok(ruleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("metadata:rule:manage")
    @Operation(summary = "删除规则", description = "被任务引用的规则不可删除")
    public ApiResponse<Void> delete(@PathVariable String id) {
        ruleService.delete(id);
        return ApiResponse.ok();
    }
}
