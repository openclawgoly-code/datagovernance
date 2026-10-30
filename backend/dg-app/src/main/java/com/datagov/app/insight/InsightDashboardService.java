package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.InsightDashboard;
import com.datagov.app.insight.entity.InsightDashboardAcl;
import com.datagov.app.insight.entity.InsightDashboardItem;
import com.datagov.app.insight.entity.InsightDashboardVersion;
import com.datagov.app.insight.mapper.InsightDashboardAclMapper;
import com.datagov.app.insight.mapper.InsightDashboardItemMapper;
import com.datagov.app.insight.mapper.InsightDashboardMapper;
import com.datagov.app.insight.mapper.InsightDashboardVersionMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问数看板。
 *
 * <p>三层权限:
 * <ul>
 *   <li>Space 权限 {@code insight:dashboard:read/manage} 决定能不能使用看板功能</li>
 *   <li>创建者/ACL 决定能看/能改哪一个看板</li>
 *   <li>平台管理员可跨看板</li>
 * </ul>
 * 版本快照只存问题定义,不存查询结果;回放时重新问数。
 */
@Service
public class InsightDashboardService {

    private final InsightDashboardMapper dashboardMapper;
    private final InsightDashboardItemMapper itemMapper;
    private final InsightDashboardAclMapper aclMapper;
    private final InsightDashboardVersionMapper versionMapper;
    private final InsightQueryService queryService;
    private final ObjectMapper objectMapper;

    public InsightDashboardService(InsightDashboardMapper dashboardMapper,
                                   InsightDashboardItemMapper itemMapper,
                                   InsightDashboardAclMapper aclMapper,
                                   InsightDashboardVersionMapper versionMapper,
                                   InsightQueryService queryService,
                                   ObjectMapper objectMapper) {
        this.dashboardMapper = dashboardMapper;
        this.itemMapper = itemMapper;
        this.aclMapper = aclMapper;
        this.versionMapper = versionMapper;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    public record DashboardView(String id, String name, String description,
                                int itemCount, Integer version, String visibility,
                                String createdBy, Instant updatedAt) {
    }

    public record ItemView(String id, String question, Long datasetId,
                           String chartType, Integer sortOrder, String layoutJson) {
        static ItemView from(InsightDashboardItem item) {
            return new ItemView(item.getId(), item.getQuestion(), item.getDatasetId(),
                    item.getChartType(), item.getSortOrder(), item.getLayoutJson());
        }
    }

    public record DashboardDetail(String id, String name, String description,
                                  Integer version, String visibility, String createdBy,
                                  Instant updatedAt, List<ItemView> items) {
    }

    public record CreateRequest(String name, String description) {
    }

    public record ItemRequest(String question, Long datasetId, String chartType,
                              String layoutJson) {
    }

    public record ItemUpdateRequest(Integer sortOrder, String layoutJson) {
    }

    public record AclView(String username, String permission) {
    }

    public record AclRequest(String username, String permission) {
    }

    public record VersionView(Integer version, String createdBy, Instant createdAt,
                              int itemCount) {
    }

    public record RunItem(String itemId, String question, boolean succeeded,
                          InsightQueryService.Answer answer, String error) {
    }

    public record RunResult(String dashboardId, String name, Instant refreshedAt,
                            List<RunItem> items) {
    }

    public List<DashboardView> list() {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<InsightDashboard> query = new LambdaQueryWrapper<InsightDashboard>()
                .eq(InsightDashboard::getWorkspaceId, workspaceId);
        if (!caller.platformAdmin()) {
            List<String> aclIds = aclMapper.selectList(
                            new LambdaQueryWrapper<InsightDashboardAcl>()
                                    .eq(InsightDashboardAcl::getUsername, caller.username()))
                    .stream().map(InsightDashboardAcl::getDashboardId).toList();
            query.and(w -> {
                w.eq(InsightDashboard::getCreatedBy, caller.username());
                if (!aclIds.isEmpty()) {
                    w.or().in(InsightDashboard::getId, aclIds);
                }
            });
        }
        query.orderByDesc(InsightDashboard::getUpdatedAt);
        return dashboardMapper.selectList(query).stream()
                .map(dashboard -> new DashboardView(
                        dashboard.getId(), dashboard.getName(), dashboard.getDescription(),
                        countItems(dashboard.getId()), dashboard.getVersion(),
                        dashboard.getVisibility(), dashboard.getCreatedBy(),
                        dashboard.getUpdatedAt()))
                .toList();
    }

    public DashboardDetail get(String id) {
        InsightDashboard dashboard = requireReadable(id);
        List<ItemView> items = itemMapper.selectList(
                        new LambdaQueryWrapper<InsightDashboardItem>()
                                .eq(InsightDashboardItem::getDashboardId, id)
                                .orderByAsc(InsightDashboardItem::getSortOrder)
                                .orderByAsc(InsightDashboardItem::getCreatedAt))
                .stream().map(ItemView::from).toList();
        return new DashboardDetail(dashboard.getId(), dashboard.getName(),
                dashboard.getDescription(), dashboard.getVersion(), dashboard.getVisibility(),
                dashboard.getCreatedBy(), dashboard.getUpdatedAt(), items);
    }

    public RunResult run(String id) {
        Caller caller = WorkspaceContext.require();
        if (!caller.hasPermission("insight:ask")) {
            throw BizException.forbidden("缺少权限: insight:ask");
        }
        DashboardDetail detail = get(id);
        List<RunItem> items = detail.items().stream().map(item -> {
            try {
                InsightQueryService.Answer answer = queryService.ask(
                        new InsightQueryService.AskRequest(
                                item.question(), item.datasetId(), null));
                return new RunItem(item.id(), item.question(), true, answer, null);
            } catch (RuntimeException e) {
                return new RunItem(item.id(), item.question(), false, null, e.getMessage());
            }
        }).toList();
        return new RunResult(detail.id(), detail.name(), Instant.now(), items);
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
        dashboard.setVisibility("WORKSPACE");
        dashboard.setVersion(1);
        dashboard.setCreatedBy(caller.username());
        dashboard.setCreatedAt(Instant.now());
        dashboard.setUpdatedAt(dashboard.getCreatedAt());
        dashboard.setDeleted(false);
        dashboardMapper.insert(dashboard);
        snapshot(dashboard, caller.username());
        return toView(dashboard);
    }

    @Transactional
    public ItemView addItem(String dashboardId, ItemRequest request) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireManageable(dashboardId);
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
        item.setLayoutJson(request.layoutJson() == null ? "{}" : request.layoutJson());
        item.setCreatedBy(caller.username());
        item.setCreatedAt(Instant.now());
        itemMapper.insert(item);
        bumpVersion(dashboard, caller.username());
        return ItemView.from(item);
    }

    @Transactional
    public ItemView updateItem(String dashboardId, String itemId, ItemUpdateRequest request) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireManageable(dashboardId);
        InsightDashboardItem item = itemMapper.selectOne(
                new LambdaQueryWrapper<InsightDashboardItem>()
                        .eq(InsightDashboardItem::getId, itemId)
                        .eq(InsightDashboardItem::getDashboardId, dashboardId));
        if (item == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "看板问题项不存在");
        }
        if (request != null) {
            if (request.sortOrder() != null) {
                item.setSortOrder(request.sortOrder());
            }
            if (request.layoutJson() != null) {
                item.setLayoutJson(request.layoutJson());
            }
        }
        itemMapper.updateById(item);
        bumpVersion(dashboard, caller.username());
        return ItemView.from(item);
    }

    @Transactional
    public void delete(String dashboardId) {
        InsightDashboard dashboard = requireManageable(dashboardId);
        itemMapper.delete(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId));
        aclMapper.delete(new LambdaQueryWrapper<InsightDashboardAcl>()
                .eq(InsightDashboardAcl::getDashboardId, dashboardId));
        versionMapper.delete(new LambdaQueryWrapper<InsightDashboardVersion>()
                .eq(InsightDashboardVersion::getDashboardId, dashboardId));
        dashboardMapper.deleteById(dashboard.getId());
    }

    @Transactional
    public void deleteItem(String dashboardId, String itemId) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireManageable(dashboardId);
        itemMapper.delete(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId)
                .eq(InsightDashboardItem::getId, itemId));
        bumpVersion(dashboard, caller.username());
    }

    public List<AclView> acl(String dashboardId) {
        requireReadable(dashboardId);
        return aclMapper.selectList(new LambdaQueryWrapper<InsightDashboardAcl>()
                        .eq(InsightDashboardAcl::getDashboardId, dashboardId))
                .stream()
                .map(value -> new AclView(value.getUsername(), value.getPermission()))
                .toList();
    }

    @Transactional
    public AclView addAcl(String dashboardId, AclRequest request) {
        Caller caller = WorkspaceContext.require();
        requireManageable(dashboardId);
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "用户名不能为空");
        }
        String permission = request.permission() == null
                ? "READ" : request.permission().toUpperCase();
        if (!permission.equals("READ") && !permission.equals("MANAGE")) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "看板权限只能是 READ / MANAGE");
        }
        InsightDashboardAcl existing = aclMapper.selectOne(
                new LambdaQueryWrapper<InsightDashboardAcl>()
                        .eq(InsightDashboardAcl::getDashboardId, dashboardId)
                        .eq(InsightDashboardAcl::getUsername, request.username()));
        if (existing == null) {
            existing = new InsightDashboardAcl();
            existing.setId(Ids.of("dashacl"));
            existing.setDashboardId(dashboardId);
            existing.setUsername(request.username());
            existing.setCreatedAt(Instant.now());
            existing.setCreatedBy(caller.username());
        }
        existing.setPermission(permission);
        if (aclMapper.selectById(existing.getId()) == null) {
            aclMapper.insert(existing);
        } else {
            aclMapper.updateById(existing);
        }
        return new AclView(existing.getUsername(), existing.getPermission());
    }

    public void removeAcl(String dashboardId, String username) {
        requireManageable(dashboardId);
        aclMapper.delete(new LambdaQueryWrapper<InsightDashboardAcl>()
                .eq(InsightDashboardAcl::getDashboardId, dashboardId)
                .eq(InsightDashboardAcl::getUsername, username));
    }

    public List<VersionView> versions(String dashboardId) {
        requireReadable(dashboardId);
        return versionMapper.selectList(new LambdaQueryWrapper<InsightDashboardVersion>()
                        .eq(InsightDashboardVersion::getDashboardId, dashboardId)
                        .orderByDesc(InsightDashboardVersion::getVersion))
                .stream()
                .map(value -> new VersionView(value.getVersion(), value.getCreatedBy(),
                        value.getCreatedAt(), snapshotSize(value.getSnapshotJson())))
                .toList();
    }

    private InsightDashboard requireReadable(String id) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireDashboard(id);
        if (caller.platformAdmin() || caller.username().equals(dashboard.getCreatedBy())
                || hasAcl(dashboard.getId(), caller.username())) {
            return dashboard;
        }
        throw BizException.forbidden("无权查看该看板");
    }

    private InsightDashboard requireManageable(String id) {
        Caller caller = WorkspaceContext.require();
        InsightDashboard dashboard = requireDashboard(id);
        if (caller.platformAdmin() || caller.username().equals(dashboard.getCreatedBy())
                || hasAcl(dashboard.getId(), caller.username(), "MANAGE")) {
            return dashboard;
        }
        throw BizException.forbidden("无权修改该看板");
    }

    private boolean hasAcl(String dashboardId, String username) {
        return aclMapper.selectCount(new LambdaQueryWrapper<InsightDashboardAcl>()
                .eq(InsightDashboardAcl::getDashboardId, dashboardId)
                .eq(InsightDashboardAcl::getUsername, username)) > 0;
    }

    private boolean hasAcl(String dashboardId, String username, String permission) {
        return aclMapper.selectCount(new LambdaQueryWrapper<InsightDashboardAcl>()
                .eq(InsightDashboardAcl::getDashboardId, dashboardId)
                .eq(InsightDashboardAcl::getUsername, username)
                .eq(InsightDashboardAcl::getPermission, permission)) > 0;
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

    private void bumpVersion(InsightDashboard dashboard, String username) {
        dashboard.setVersion((dashboard.getVersion() == null ? 0 : dashboard.getVersion()) + 1);
        dashboard.setUpdatedAt(Instant.now());
        dashboardMapper.updateById(dashboard);
        snapshot(dashboard, username);
    }

    private void snapshot(InsightDashboard dashboard, String username) {
        List<ItemView> items = itemMapper.selectList(
                        new LambdaQueryWrapper<InsightDashboardItem>()
                                .eq(InsightDashboardItem::getDashboardId, dashboard.getId())
                                .orderByAsc(InsightDashboardItem::getSortOrder)
                                .orderByAsc(InsightDashboardItem::getCreatedAt))
                .stream().map(ItemView::from).toList();
        InsightDashboardVersion version = new InsightDashboardVersion();
        version.setId(Ids.of("dashver"));
        version.setDashboardId(dashboard.getId());
        version.setVersion(dashboard.getVersion());
        version.setSnapshotJson(writeJson(items));
        version.setCreatedBy(username);
        version.setCreatedAt(Instant.now());
        versionMapper.insert(version);
    }

    private int snapshotSize(String snapshotJson) {
        try {
            List<?> list = objectMapper.readValue(snapshotJson,
                    new TypeReference<List<?>>() {
                    });
            return list.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "看板版本序列化失败");
        }
    }

    private DashboardView toView(InsightDashboard dashboard) {
        return new DashboardView(dashboard.getId(), dashboard.getName(),
                dashboard.getDescription(), countItems(dashboard.getId()),
                dashboard.getVersion(), dashboard.getVisibility(),
                dashboard.getCreatedBy(), dashboard.getUpdatedAt());
    }

    private int countItems(String dashboardId) {
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<InsightDashboardItem>()
                .eq(InsightDashboardItem::getDashboardId, dashboardId));
        return count == null ? 0 : count.intValue();
    }
}
