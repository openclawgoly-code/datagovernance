package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.entity.TaskCatalogEntity;
import com.datagov.control.mapper.JobDefinitionMapper;
import com.datagov.control.mapper.TaskCatalogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务目录(功能 16)。
 *
 * <p>与数据源目录(功能 5)同构。<b>没有把两者合并成一个通用的"目录服务"</b> ——
 * 它们的删除保护、计数口径、归属字段都不同,合并后的那个类会是一堆
 * {@code if (type == DATASOURCE)}。同构不等于同一个。
 */
@Service
public class TaskCatalogService {

    /**
     * 目录最大深度。
     *
     * <p>与数据源目录同样是 4 层:超过四层的树在左侧面板里已经没法看了,
     * 用户会开始用命名来表达层级(「A-B-C-某某任务」),那说明层级设计本身
     * 出了问题。早点拦住比事后清理容易。
     */
    private static final int MAX_DEPTH = 4;

    private final TaskCatalogMapper catalogMapper;
    private final JobDefinitionMapper jobMapper;

    public TaskCatalogService(TaskCatalogMapper catalogMapper, JobDefinitionMapper jobMapper) {
        this.catalogMapper = catalogMapper;
        this.jobMapper = jobMapper;
    }

    /** 目录树节点视图。 */
    public record CatalogNodeView(
            String id,
            String parentId,
            String name,
            String description,
            Integer sortOrder,
            /** 该目录<b>直接</b>挂载的任务数,不含子目录 —— 与树的展示一致 */
            int taskCount,
            List<CatalogNodeView> children
    ) {
    }

    public List<CatalogNodeView> tree() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();

        List<TaskCatalogEntity> all = catalogMapper.selectList(
                new LambdaQueryWrapper<TaskCatalogEntity>()
                        .eq(TaskCatalogEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(TaskCatalogEntity::getSortOrder));

        Map<String, Integer> counts = countByCatalog(workspaceId);
        Map<String, List<TaskCatalogEntity>> byParent = new HashMap<>();
        for (TaskCatalogEntity node : all) {
            byParent.computeIfAbsent(node.getParentId() == null ? "" : node.getParentId(),
                    k -> new ArrayList<>()).add(node);
        }
        return buildChildren("", byParent, counts);
    }

    /** 未归类任务数 —— 前端把它显示成一个虚拟的「未分类」节点。 */
    public int uncategorizedCount() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Long count = jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .isNull(JobDefinition::getCatalogId));
        return count == null ? 0 : count.intValue();
    }

    @Transactional
    public CatalogNodeView create(String parentId, String name, String description,
                                  Integer sortOrder) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        if (parentId != null && !parentId.isBlank()) {
            requireInWorkspace(workspaceId, parentId);
            if (depthOf(workspaceId, parentId) + 1 >= MAX_DEPTH) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "目录层级不得超过 %d 层".formatted(MAX_DEPTH));
            }
        }
        requireNameAvailable(workspaceId, parentId, name, null);

        Instant now = Instant.now();
        TaskCatalogEntity node = new TaskCatalogEntity();
        node.setId(Ids.of("tsc"));
        node.setWorkspaceId(workspaceId);
        node.setParentId(parentId == null || parentId.isBlank() ? null : parentId);
        node.setName(name.trim());
        node.setDescription(description);
        node.setSortOrder(sortOrder == null ? 0 : sortOrder);
        node.setCreatedAt(now);
        node.setCreatedBy(operator);
        node.setUpdatedAt(now);
        node.setUpdatedBy(operator);
        node.setDeleted(false);
        catalogMapper.insert(node);

        return toView(node, 0, List.of());
    }

    @Transactional
    public CatalogNodeView update(String id, String name, String description, Integer sortOrder) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        TaskCatalogEntity node = requireInWorkspace(workspaceId, id);
        requireNameAvailable(workspaceId, node.getParentId(), name, id);

        node.setName(name.trim());
        node.setDescription(description);
        if (sortOrder != null) {
            node.setSortOrder(sortOrder);
        }
        node.setUpdatedAt(Instant.now());
        node.setUpdatedBy(WorkspaceContext.require().userId());
        catalogMapper.updateById(node);
        return toView(node, 0, List.of());
    }

    /**
     * 删除目录。
     *
     * <p>非空目录不许删 —— 无论是含子目录还是含任务。级联删除在这里是危险的:
     * 一次误点会带走一整棵子树下的所有任务定义,而任务定义里可能有几个月积累的
     * 调度配置。让用户先清空,是刻意的摩擦。
     */
    @Transactional
    public void delete(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireInWorkspace(workspaceId, id);

        Long children = catalogMapper.selectCount(new LambdaQueryWrapper<TaskCatalogEntity>()
                .eq(TaskCatalogEntity::getWorkspaceId, workspaceId)
                .eq(TaskCatalogEntity::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "目录下还有 %d 个子目录,无法删除".formatted(children));
        }

        Long tasks = jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .eq(JobDefinition::getCatalogId, id));
        if (tasks != null && tasks > 0) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "目录下还有 %d 个任务,请先移出或删除".formatted(tasks));
        }
        catalogMapper.deleteById(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private Map<String, Integer> countByCatalog(String workspaceId) {
        Map<String, Integer> counts = new HashMap<>();
        // 一次查全量再在内存里分组:任务定义数以千计,GROUP BY 与全量拉取
        // 在这个量级上没有差别,而后者少一个只在这里用的 mapper 方法
        for (JobDefinition job : jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getWorkspaceId, workspaceId)
                .isNotNull(JobDefinition::getCatalogId)
                .select(JobDefinition::getCatalogId))) {
            counts.merge(job.getCatalogId(), 1, Integer::sum);
        }
        return counts;
    }

    private List<CatalogNodeView> buildChildren(String parentKey,
                                                Map<String, List<TaskCatalogEntity>> byParent,
                                                Map<String, Integer> counts) {
        List<TaskCatalogEntity> nodes = byParent.get(parentKey);
        if (nodes == null) {
            return List.of();
        }
        List<CatalogNodeView> views = new ArrayList<>(nodes.size());
        for (TaskCatalogEntity node : nodes) {
            views.add(toView(node, counts.getOrDefault(node.getId(), 0),
                    buildChildren(node.getId(), byParent, counts)));
        }
        return views;
    }

    private static CatalogNodeView toView(TaskCatalogEntity node, int taskCount,
                                          List<CatalogNodeView> children) {
        return new CatalogNodeView(node.getId(), node.getParentId(), node.getName(),
                node.getDescription(), node.getSortOrder(), taskCount, children);
    }

    private int depthOf(String workspaceId, String nodeId) {
        int depth = 0;
        String current = nodeId;
        // 上限保护:数据异常造成的环会让这里死循环,而死循环发生在一个
        // 用户点击触发的请求里
        while (current != null && depth <= MAX_DEPTH + 1) {
            TaskCatalogEntity node = catalogMapper.selectOne(
                    new LambdaQueryWrapper<TaskCatalogEntity>()
                            .eq(TaskCatalogEntity::getId, current)
                            .eq(TaskCatalogEntity::getWorkspaceId, workspaceId));
            if (node == null) {
                break;
            }
            current = node.getParentId();
            depth++;
        }
        return depth;
    }

    private void requireNameAvailable(String workspaceId, String parentId, String name,
                                      String excludeId) {
        Long count = catalogMapper.selectCount(new LambdaQueryWrapper<TaskCatalogEntity>()
                .eq(TaskCatalogEntity::getWorkspaceId, workspaceId)
                // parentId 为 null 时必须用 isNull —— SQL 里 null = null 不成立,
                // 而 eq(null) 会被 MyBatis-Plus 当作"不加这个条件"
                .eq(parentId != null, TaskCatalogEntity::getParentId, parentId)
                .isNull(parentId == null, TaskCatalogEntity::getParentId)
                .eq(TaskCatalogEntity::getName, name.trim())
                .ne(excludeId != null, TaskCatalogEntity::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "同级目录下已有「%s」".formatted(name));
        }
    }

    private TaskCatalogEntity requireInWorkspace(String workspaceId, String id) {
        TaskCatalogEntity node = catalogMapper.selectOne(
                new LambdaQueryWrapper<TaskCatalogEntity>()
                        .eq(TaskCatalogEntity::getId, id)
                        .eq(TaskCatalogEntity::getWorkspaceId, workspaceId));
        if (node == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "任务目录 " + id);
        }
        return node;
    }
}
