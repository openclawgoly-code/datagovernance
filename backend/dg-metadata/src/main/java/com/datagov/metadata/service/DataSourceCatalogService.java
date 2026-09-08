package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.dto.CatalogNodeView;
import com.datagov.metadata.entity.DataSourceCatalogEntity;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceCatalogMapper;
import com.datagov.metadata.mapper.DataSourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据源目录管理(功能 5)。
 *
 * <p>目录是人工维护的组织结构,不是从目标库探测来的东西 ——
 * 见 {@link DataSourceCatalogEntity} 的类注释对两种"目录"的区分。
 */
@Service
public class DataSourceCatalogService {

    /**
     * 目录最大深度。
     *
     * <p>限制不是技术必需,而是可用性判断:超过四层的树在左侧面板里已经没法看了,
     * 用户会开始用命名来表达层级(「A-B-C-某某库」),那说明层级设计本身出了问题。
     * 早点拦住比事后清理容易。
     */
    private static final int MAX_DEPTH = 4;

    private final DataSourceCatalogMapper catalogMapper;
    private final DataSourceMapper dataSourceMapper;

    public DataSourceCatalogService(DataSourceCatalogMapper catalogMapper,
                                    DataSourceMapper dataSourceMapper) {
        this.catalogMapper = catalogMapper;
        this.dataSourceMapper = dataSourceMapper;
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /** 整棵目录树,附带每个节点直接挂载的数据源数量。 */
    public List<CatalogNodeView> tree() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();

        List<DataSourceCatalogEntity> all = catalogMapper.selectList(
                new LambdaQueryWrapper<DataSourceCatalogEntity>()
                        .eq(DataSourceCatalogEntity::getWorkspaceId, workspaceId));

        Map<String, Integer> counts = countByCatalog(workspaceId);
        Map<String, List<DataSourceCatalogEntity>> byParent = new HashMap<>();
        for (DataSourceCatalogEntity node : all) {
            byParent.computeIfAbsent(node.getParentId() == null ? "" : node.getParentId(),
                    k -> new ArrayList<>()).add(node);
        }
        return buildChildren("", byParent, counts);
    }

    /** 未归类数据源的数量 —— 前端把它显示成一个虚拟的「未分类」节点。 */
    public int uncategorizedCount() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Long count = dataSourceMapper.selectCount(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getWorkspaceId, workspaceId)
                .isNull(DataSourceEntity::getCatalogId));
        return count == null ? 0 : count.intValue();
    }

    // ── 命令 ────────────────────────────────────────────────────────────

    @Transactional
    public CatalogNodeView create(String parentId, String name, String description, Integer sortOrder) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        if (parentId != null) {
            requireInWorkspace(workspaceId, parentId);
            if (depthOf(workspaceId, parentId) + 1 >= MAX_DEPTH) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "目录层级不得超过 %d 层".formatted(MAX_DEPTH));
            }
        }
        requireNameAvailable(workspaceId, parentId, name, null);

        Instant now = Instant.now();
        DataSourceCatalogEntity node = new DataSourceCatalogEntity();
        node.setId(Ids.of("dsc"));
        node.setWorkspaceId(workspaceId);
        node.setParentId(parentId);
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
        DataSourceCatalogEntity node = requireInWorkspace(workspaceId, id);
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
     * 移动目录到新的父节点。
     *
     * <p>必须防止把一个目录移动到它自己的子孙下面 —— 那会在树里造出一个环,
     * 而环的表现是查询目录树时无限递归,直到栈溢出。
     */
    @Transactional
    public void move(String id, String newParentId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        DataSourceCatalogEntity node = requireInWorkspace(workspaceId, id);

        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID, "不能把目录移动到它自己下面");
            }
            requireInWorkspace(workspaceId, newParentId);
            if (descendantIds(workspaceId, id).contains(newParentId)) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "不能把目录移动到它自己的子目录下面,那会在目录树里造出一个环");
            }
        }
        requireNameAvailable(workspaceId, newParentId, node.getName(), id);

        node.setParentId(newParentId);
        node.setUpdatedAt(Instant.now());
        node.setUpdatedBy(WorkspaceContext.require().userId());
        catalogMapper.updateById(node);
    }

    /**
     * 删除目录。
     *
     * <p>非空目录不允许删除,而不是级联删除或把数据源静默挪走。级联删除会让
     * 一次误点丢掉整棵子树;静默挪走则让用户找不到自己的数据源。
     * 报错并说清"里面还有什么"是唯一不会造成意外的选择。
     */
    @Transactional
    public void delete(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireInWorkspace(workspaceId, id);

        Long childCount = catalogMapper.selectCount(new LambdaQueryWrapper<DataSourceCatalogEntity>()
                .eq(DataSourceCatalogEntity::getWorkspaceId, workspaceId)
                .eq(DataSourceCatalogEntity::getParentId, id));
        Long dataSourceCount = dataSourceMapper.selectCount(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getWorkspaceId, workspaceId)
                .eq(DataSourceEntity::getCatalogId, id));

        if ((childCount != null && childCount > 0) || (dataSourceCount != null && dataSourceCount > 0)) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "目录非空,无法删除:含 %d 个子目录、%d 个数据源。请先移出或删除其中内容"
                            .formatted(childCount == null ? 0 : childCount,
                                    dataSourceCount == null ? 0 : dataSourceCount));
        }
        catalogMapper.deleteById(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private List<CatalogNodeView> buildChildren(String parentKey,
                                                Map<String, List<DataSourceCatalogEntity>> byParent,
                                                Map<String, Integer> counts) {
        List<DataSourceCatalogEntity> children = byParent.get(parentKey);
        if (children == null) {
            return List.of();
        }
        children.sort(Comparator
                .comparing(DataSourceCatalogEntity::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DataSourceCatalogEntity::getName));

        List<CatalogNodeView> views = new ArrayList<>(children.size());
        for (DataSourceCatalogEntity node : children) {
            views.add(toView(node,
                    counts.getOrDefault(node.getId(), 0),
                    buildChildren(node.getId(), byParent, counts)));
        }
        return views;
    }

    private Map<String, Integer> countByCatalog(String workspaceId) {
        // 一次查出全部数据源的归属再在内存里计数,而不是对每个目录发一条 count。
        // 目录数以百计时,后者是几百次往返。
        Map<String, Integer> counts = new HashMap<>();
        List<DataSourceEntity> all = dataSourceMapper.selectList(
                new LambdaQueryWrapper<DataSourceEntity>()
                        .select(DataSourceEntity::getCatalogId)
                        .eq(DataSourceEntity::getWorkspaceId, workspaceId)
                        .isNotNull(DataSourceEntity::getCatalogId));
        for (DataSourceEntity entity : all) {
            counts.merge(entity.getCatalogId(), 1, Integer::sum);
        }
        return counts;
    }

    private int depthOf(String workspaceId, String nodeId) {
        int depth = 0;
        String current = nodeId;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current) && depth < MAX_DEPTH + 2) {
            DataSourceCatalogEntity node = catalogMapper.selectById(current);
            if (node == null || !workspaceId.equals(node.getWorkspaceId())) {
                break;
            }
            depth++;
            current = node.getParentId();
        }
        return depth;
    }

    /** 某节点的全部子孙 ID,用于移动时的环检测。 */
    private Set<String> descendantIds(String workspaceId, String rootId) {
        Set<String> result = new HashSet<>();
        List<String> frontier = List.of(rootId);
        while (!frontier.isEmpty()) {
            List<DataSourceCatalogEntity> children = catalogMapper.selectList(
                    new LambdaQueryWrapper<DataSourceCatalogEntity>()
                            .eq(DataSourceCatalogEntity::getWorkspaceId, workspaceId)
                            .in(DataSourceCatalogEntity::getParentId, frontier));
            List<String> next = new ArrayList<>();
            for (DataSourceCatalogEntity child : children) {
                if (result.add(child.getId())) {
                    next.add(child.getId());
                }
            }
            frontier = next;
        }
        return result;
    }

    private DataSourceCatalogEntity requireInWorkspace(String workspaceId, String id) {
        DataSourceCatalogEntity node = catalogMapper.selectOne(
                new LambdaQueryWrapper<DataSourceCatalogEntity>()
                        .eq(DataSourceCatalogEntity::getId, id)
                        .eq(DataSourceCatalogEntity::getWorkspaceId, workspaceId));
        if (node == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_NOT_FOUND, id);
        }
        return node;
    }

    private void requireNameAvailable(String workspaceId, String parentId,
                                      String name, String excludeId) {
        LambdaQueryWrapper<DataSourceCatalogEntity> query =
                new LambdaQueryWrapper<DataSourceCatalogEntity>()
                        .eq(DataSourceCatalogEntity::getWorkspaceId, workspaceId)
                        .eq(DataSourceCatalogEntity::getName, name.trim())
                        .ne(excludeId != null, DataSourceCatalogEntity::getId, excludeId);
        // parent_id 可能为 null,而 SQL 里 null = null 不成立,必须用 isNull
        if (parentId == null) {
            query.isNull(DataSourceCatalogEntity::getParentId);
        } else {
            query.eq(DataSourceCatalogEntity::getParentId, parentId);
        }

        Long count = catalogMapper.selectCount(query);
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "同级目录下名称已存在: " + name);
        }
    }

    private static CatalogNodeView toView(DataSourceCatalogEntity node, int count,
                                          List<CatalogNodeView> children) {
        return new CatalogNodeView(node.getId(), node.getParentId(), node.getName(),
                node.getDescription(), node.getSortOrder(), count, children);
    }
}
