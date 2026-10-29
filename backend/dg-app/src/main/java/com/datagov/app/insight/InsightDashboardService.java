package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.InsightDashboard;
import com.datagov.app.insight.entity.InsightDashboardItem;
import com.datagov.app.insight.mapper.InsightDashboardItemMapper;
import com.datagov.app.insight.mapper.InsightDashboardMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 问数看板(最小版)。
 *
 * <p>只保存问题定义,不保存结果快照;打开看板时重新问数。这样不会出现
 * “看板上的数字是上周的,但没有任何过期提示”的问题。
 */
@Service
public class InsightDashboardService {

    private final InsightDashboardMapper dashboardMapper;
    private final InsightDashboardItemMapper itemMapper;

    public InsightDashboardService(InsightDashboardMapper dashboardMapper,
                                   InsightDashboardItemMapper itemMapper) {
        this.dashboardMapper = dashboardMapper;
        this.itemMapper = itemMapper;
    }

    public record DashboardView(String id, String name, String description,
                                int itemCount, String createdBy, Instant updatedAt) {
    }

    public record ItemView(String id, String question, Long datasetId,
                           String chartType, Integer sortOrder) {
        static ItemView from(InsightDashboardItem item) {
            return new ItemView(item.getId(), item.getQuestion(), item.getDatasetId(),
                    item.getChartType(), item.getSortOrder());
        }
    }

    public record DashboardDetail(String id, String name, String description,
                                  String createdBy, Instant updatedAt,
                                  List<ItemView> items) {
    }

    public record CreateRequest(String name, String description) {
    }

    public record ItemRequest(String question, Long datasetId, String chartType) {
    }

    public List<DashboardView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return dashboardMapper.selectList(new LambdaQueryWrapper<InsightDashboard>()
                        .eq(InsightDashboard::getWorkspaceId, workspaceId)
                        .orderByDesc(InsightDashboard::getUpdatedAt))
                .stream().map(dashboard -> new DashboardView(
                        dashboard.getId(), dashboard.getName(), dashboard.getDescription(),
                        countItems(dashboard.getId()), dashboard.getCreatedBy(),
                        dashboard.getUpdatedAt()))
                .toList();
    }

    public DashboardDetail get(String id) {
        InsightDashboard dashboard = requireDashboard(id);
        List<ItemView> items = itemMapper.selectList(
                        new LambdaQueryWrapper<InsightDashboardItem>()
                                .eq(InsightDashboardItem::getDashboardId, id)
                                .orderByAsc(InsightDashboardItem::getSortOrder)
                                .orderByAsc(InsightDashboardItem::getCreatedAt))
                .stream().map(ItemView::from).toList();
        return new DashboardDetail(dashboard.getId(), dashboard.getName(),
                dashboard.getDescription(), dashboard.getCreatedBy(),
                dashboard.getUpdatedAt(), items);
    }

    public DashboardView create(CreateRequest request) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "看板名称不能为空");
        }
        String name = request.name().trim();
        if (name.length() > 128) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "看板名称不能超过 128 字");
        }
        InsightDashboard dashboard = new InsightDashboard();
        dashboard.setId(Ids.of("dash"));
        dashboard.setWorkspaceId(workspaceId);
        dashboard.setName(name);
        dashboard.setDescription(request.description());
        dashboard.setCreatedBy(caller.username());
        dashboard.setCreatedAt(Instant.now());
        dashboard.setUpdatedAt(dashboard.getCreatedAt());
        dashboard.setDeleted(false);
        dashboardMapper.insert(dashboard);
        return new DashboardView(dashboard.getId(), dashboard.getName(),
                dashboard.getDescription(), 0, dashboard.getCreatedBy(),
                dashboard.getUpdatedAt());
    }

    @Transactional
    public ItemView addItem(String dashboardId, ItemRequest request) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireDashboard(dashboardId);
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能为空");
        }
        String question = request.question().trim();
        if (question.length() > 2000) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能超过 2000 字");
        }
        InsightDashboardItem item = new InsightDashboardItem();
        item.setId(Ids.of("dashitem"));
        item.setDashboardId(dashboardId);
        item.setQuestion(question);
        item.setDatasetId(request.datasetId());
        item.setChartType(request.chartType() == null || request.chartType().isBlank()
                ? "AUTO" : request.chartType().toUpperCase());
        item.setSortOrder(countItems(dashboardId));
        item.setCreatedBy(caller.username());
        item.setCreatedAt(Instant.now());
        itemMapper.insert(item);
        dashboard.setUpdatedAt(Instant.now());
        dashboardMapper.updateById(dashboard);
        return ItemView.from(item);
    }

    @Transactional
    public void delete(String dashboardId) {
        InsightDashboard dashboard = requireDashboard(dashboardId);
        itemMapper.delete(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId));
        dashboardMapper.deleteById(dashboard.getId());
    }

    public void deleteItem(String dashboardId, String itemId) {
        requireDashboard(dashboardId);
        itemMapper.delete(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId)
                .eq(InsightDashboardItem::getId, itemId));
    }

    private InsightDashboard requireDashboard(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        InsightDashboard dashboard = dashboardMapper.selectOne(
                new LambdaQueryWrapper<InsightDashboard>()
                        .eq(InsightDashboard::getId, id)
                        .eq(InsightDashboard::getWorkspaceId, workspaceId));
        if (dashboard == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "看板不存在: " + id);
        }
        return dashboard;
    }

    private int countItems(String dashboardId) {
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId));
        return count == null ? 0 : count.intValue();
    }
}
