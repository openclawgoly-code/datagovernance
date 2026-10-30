package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.GovernanceEntities.AssetGovernance;
import com.datagov.metadata.entity.GovernanceEntities.BusinessDomain;
import com.datagov.metadata.mapper.AssetGovernanceMapper;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 资产治理（P2）：业务域、owner、管家与认证状态。
 *
 * <p><b>不碰采集事实表。</b> 所有治理结论写入 {@code md_asset_governance}，
 * 采集刷新只更新 {@code md_catalog_dataset}；这是“刷新不丢 owner”的结构性保证，
 * 而不是依赖采集代码记得跳过某些字段。
 */
@Service
public class AssetGovernanceService {

    private static final Set<String> CERTIFICATION_STATUS =
            Set.of("NONE", "CERTIFIED", "DEPRECATED");
    private static final Pattern DOMAIN_CODE =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final BusinessDomainMapper domainMapper;
    private final AssetGovernanceMapper governanceMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final WorkspaceService workspaceService;
    private final UserService userService;

    public AssetGovernanceService(BusinessDomainMapper domainMapper,
                                  AssetGovernanceMapper governanceMapper,
                                  CatalogDatasetMapper datasetMapper,
                                  WorkspaceService workspaceService,
                                  UserService userService) {
        this.domainMapper = domainMapper;
        this.governanceMapper = governanceMapper;
        this.datasetMapper = datasetMapper;
        this.workspaceService = workspaceService;
        this.userService = userService;
    }

    // ── DTO ─────────────────────────────────────────────────────────────

    public record DomainRequest(String code, String name, String description,
                                String ownerUsername) {
    }

    public record DomainView(String id, String code, String name, String description,
                             String ownerUsername, long assetCount, Instant updatedAt) {
    }

    public record MemberOption(String id, String username, String displayName) {
    }

    public record GovernanceRequest(String domainId, String ownerUsername,
                                    String stewardUsername, String certificationStatus,
                                    String certificationNote, Instant reviewDueAt) {
    }

    public record GovernanceView(String datasetId, String datasetName,
                                 String datasetQualifiedName, String domainId,
                                 String domainName, String ownerUsername,
                                 String stewardUsername, String certificationStatus,
                                 String certifiedBy, Instant certifiedAt,
                                 String certificationNote, Instant reviewDueAt,
                                 boolean ownerActive, boolean stewardActive,
                                 Instant updatedAt, String updatedBy) {
    }

    // ── 读取 ────────────────────────────────────────────────────────────

    public GovernanceView getGovernance(String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(workspaceId, datasetId);
        AssetGovernance governance = governanceMapper.selectOne(
                new LambdaQueryWrapper<AssetGovernance>()
                        .eq(AssetGovernance::getWorkspaceId, workspaceId)
                        .eq(AssetGovernance::getDatasetId, datasetId));
        BusinessDomain domain = governance == null || governance.getDomainId() == null
                ? null
                : domainMapper.selectById(governance.getDomainId());
        Set<String> activeMembers = activeMemberUsernames(workspaceId);
        String owner = governance == null ? null : governance.getOwnerUsername();
        String steward = governance == null ? null : governance.getStewardUsername();
        return new GovernanceView(
                dataset.getId(), dataset.getName(), dataset.getQualifiedName(),
                governance == null ? null : governance.getDomainId(),
                domain == null ? null : domain.getName(),
                owner, steward,
                governance == null || governance.getCertificationStatus() == null
                        ? "NONE" : governance.getCertificationStatus(),
                governance == null ? null : governance.getCertifiedBy(),
                governance == null ? null : governance.getCertifiedAt(),
                governance == null ? null : governance.getCertificationNote(),
                governance == null ? null : governance.getReviewDueAt(),
                owner == null || owner.isBlank() || activeMembers.contains(owner),
                steward == null || steward.isBlank() || activeMembers.contains(steward),
                governance == null ? null : governance.getUpdatedAt(),
                governance == null ? null : governance.getUpdatedBy());
    }

    public List<DomainView> listDomains() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Map<String, Long> counts = domainAssetCounts(workspaceId);
        return domainMapper.selectList(new LambdaQueryWrapper<BusinessDomain>()
                        .eq(BusinessDomain::getWorkspaceId, workspaceId)
                        .orderByAsc(BusinessDomain::getCode)).stream()
                .map(domain -> toView(domain, counts.getOrDefault(domain.getId(), 0L)))
                .toList();
    }

    /**
     * 当前空间的可选负责人。
     *
     * <p>不复用 {@code /users}：那个接口需要 Platform 的用户管理权限，
     * 而资产管家只需要“这个空间里有哪些人”。只返回 ACTIVE 成员，
     * 避免把已禁用账号再次指派成 owner。
     */
    public List<MemberOption> listMembers() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        List<String> memberIds = workspaceService.listMemberIds(workspaceId);
        if (memberIds.isEmpty()) {
            return List.of();
        }
        List<UserView> users = userService.listByIds(memberIds);
        return users.stream()
                .filter(user -> "ACTIVE".equals(user.status()))
                .map(user -> new MemberOption(user.id(), user.username(),
                        user.displayName()))
                .sorted((left, right) -> left.username().compareTo(right.username()))
                .toList();
    }

    // ── 治理写入 ────────────────────────────────────────────────────────

    @Transactional
    public GovernanceView updateGovernance(String datasetId, GovernanceRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        CatalogDataset dataset = requireDataset(workspaceId, datasetId);
        if (request == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "治理请求不能为空");
        }

        String status = request.certificationStatus() == null
                || request.certificationStatus().isBlank()
                ? "NONE"
                : request.certificationStatus().trim().toUpperCase();
        if (!CERTIFICATION_STATUS.contains(status)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "认证状态只能是 NONE / CERTIFIED / DEPRECATED");
        }

        AssetGovernance existing = governanceMapper.selectOne(
                new LambdaQueryWrapper<AssetGovernance>()
                        .eq(AssetGovernance::getWorkspaceId, workspaceId)
                        .eq(AssetGovernance::getDatasetId, datasetId));

        String domainId = blankToNull(request.domainId());
        if (domainId != null) {
            BusinessDomain domain = domainMapper.selectById(domainId);
            if (domain == null || !workspaceId.equals(domain.getWorkspaceId())) {
                throw BizException.notFound(ErrorCode.MTD_DOMAIN_NOT_FOUND, domainId);
            }
        }

        String owner = blankToNull(request.ownerUsername());
        String steward = blankToNull(request.stewardUsername());
        validateMembers(workspaceId, owner, steward,
                existing == null ? null : existing.getOwnerUsername(),
                existing == null ? null : existing.getStewardUsername());

        String note = blankToNull(request.certificationNote());
        if ("DEPRECATED".equals(status) && note == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "废弃资产必须填写迁移/废弃说明");
        }

        Instant now = Instant.now();
        AssetGovernance entity = existing == null ? new AssetGovernance() : existing;
        if (existing == null) {
            entity.setId(Ids.of("gov"));
            entity.setWorkspaceId(workspaceId);
            entity.setDatasetId(datasetId);
            entity.setCreatedAt(now);
            entity.setCreatedBy(operator);
        }
        entity.setDomainId(domainId);
        entity.setOwnerUsername(owner);
        entity.setStewardUsername(steward);
        entity.setCertificationStatus(status);
        entity.setCertificationNote(note);
        entity.setReviewDueAt(request.reviewDueAt());
        if ("CERTIFIED".equals(status)) {
            // 状态没有变化时保留原认证人和时间，避免“改个 owner 就变成今天认证的”。
            if (existing == null || !"CERTIFIED".equals(existing.getCertificationStatus())) {
                entity.setCertifiedBy(operator);
                entity.setCertifiedAt(now);
            }
        } else if ("NONE".equals(status)) {
            entity.setCertifiedBy(null);
            entity.setCertifiedAt(null);
        }
        // DEPRECATED：保留此前的认证信息，作为“曾经认证、现已废弃”的证据。
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);

        if (existing == null) {
            governanceMapper.insert(entity);
        } else {
            governanceMapper.updateById(entity);
        }
        return getGovernance(datasetId);
    }

    // ── 业务域 ──────────────────────────────────────────────────────────

    @Transactional
    public DomainView createDomain(DomainRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        String code = requireCode(request == null ? null : request.code());
        String name = requireName(request == null ? null : request.name());
        requireCodeAvailable(workspaceId, code, null);

        Instant now = Instant.now();
        BusinessDomain domain = new BusinessDomain();
        domain.setId(Ids.of("dom"));
        domain.setWorkspaceId(workspaceId);
        domain.setCode(code);
        domain.setName(name);
        domain.setDescription(blankToNull(request.description()));
        domain.setOwnerUsername(blankToNull(request.ownerUsername()));
        domain.setCreatedAt(now);
        domain.setCreatedBy(operator);
        domain.setUpdatedAt(now);
        domain.setUpdatedBy(operator);
        domainMapper.insert(domain);
        return toView(domain, 0);
    }

    @Transactional
    public DomainView updateDomain(String id, DomainRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        BusinessDomain domain = requireDomain(workspaceId, id);
        String code = requireCode(request == null ? null : request.code());
        String name = requireName(request == null ? null : request.name());
        requireCodeAvailable(workspaceId, code, id);

        domain.setCode(code);
        domain.setName(name);
        domain.setDescription(blankToNull(request.description()));
        domain.setOwnerUsername(blankToNull(request.ownerUsername()));
        domain.setUpdatedAt(Instant.now());
        domain.setUpdatedBy(WorkspaceContext.require().userId());
        domainMapper.updateById(domain);
        return toView(domain, countAssets(workspaceId, id));
    }

    @Transactional
    public void deleteDomain(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        BusinessDomain domain = requireDomain(workspaceId, id);
        long assets = countAssets(workspaceId, id);
        if (assets > 0) {
            throw new BizException(ErrorCode.MTD_DOMAIN_IN_USE,
                    "业务域「%s」仍被 %d 个资产引用，请先迁移资产".formatted(
                            domain.getName(), assets));
        }
        domainMapper.deleteById(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private CatalogDataset requireDataset(String workspaceId, String datasetId) {
        CatalogDataset dataset = datasetMapper.selectOne(
                new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getId, datasetId)
                        .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }
        return dataset;
    }

    private BusinessDomain requireDomain(String workspaceId, String id) {
        BusinessDomain domain = domainMapper.selectById(id);
        if (domain == null || !workspaceId.equals(domain.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_DOMAIN_NOT_FOUND, id);
        }
        return domain;
    }

    /**
     * owner/steward 必须是当前空间 ACTIVE 成员。
     *
     * <p>只在“值发生变化”时校验：历史 owner 被移出空间后，管理员仍可以
     * 更新认证状态/说明而不被卡住；一旦要改成另一个人，就必须选有效成员。
     */
    private void validateMembers(String workspaceId, String owner, String steward,
                                 String previousOwner, String previousSteward) {
        List<String> memberIds = workspaceService.listMemberIds(workspaceId);
        Set<String> activeUsernames = memberIds.isEmpty()
                ? Set.of()
                : userService.listByIds(memberIds).stream()
                        .filter(user -> "ACTIVE".equals(user.status()))
                        .map(UserView::username)
                        .collect(java.util.stream.Collectors.toSet());
        if (owner != null && !owner.equals(previousOwner)
                && !activeUsernames.contains(owner)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "负责人必须是当前空间的 ACTIVE 成员: " + owner);
        }
        if (steward != null && !steward.equals(previousSteward)
                && !activeUsernames.contains(steward)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "管家必须是当前空间的 ACTIVE 成员: " + steward);
        }
    }

    private Set<String> activeMemberUsernames(String workspaceId) {
        List<String> memberIds = workspaceService.listMemberIds(workspaceId);
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return userService.listByIds(memberIds).stream()
                .filter(user -> "ACTIVE".equals(user.status()))
                .map(UserView::username)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Map<String, Long> domainAssetCounts(String workspaceId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AssetGovernance governance : governanceMapper.selectList(
                new LambdaQueryWrapper<AssetGovernance>()
                        .eq(AssetGovernance::getWorkspaceId, workspaceId)
                        .isNotNull(AssetGovernance::getDomainId))) {
            counts.merge(governance.getDomainId(), 1L, Long::sum);
        }
        return counts;
    }

    private long countAssets(String workspaceId, String domainId) {
        Long count = governanceMapper.selectCount(new LambdaQueryWrapper<AssetGovernance>()
                .eq(AssetGovernance::getWorkspaceId, workspaceId)
                .eq(AssetGovernance::getDomainId, domainId));
        return count == null ? 0 : count;
    }

    private void requireCodeAvailable(String workspaceId, String code, String excludeId) {
        LambdaQueryWrapper<BusinessDomain> query = new LambdaQueryWrapper<BusinessDomain>()
                .eq(BusinessDomain::getWorkspaceId, workspaceId)
                .eq(BusinessDomain::getCode, code);
        if (excludeId != null) {
            query.ne(BusinessDomain::getId, excludeId);
        }
        Long count = domainMapper.selectCount(query);
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.MTD_DOMAIN_CODE_DUPLICATED, code);
        }
    }

    private static String requireCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "业务域 code 不能为空");
        }
        String code = value.trim().toLowerCase();
        if (!DOMAIN_CODE.matcher(code).matches()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "业务域 code 只能包含小写字母、数字、下划线和短横线");
        }
        return code;
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "业务域名称不能为空");
        }
        String name = value.trim();
        if (name.length() > 128) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "业务域名称不能超过 128 字");
        }
        return name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainView toView(BusinessDomain domain, long assetCount) {
        return new DomainView(domain.getId(), domain.getCode(), domain.getName(),
                domain.getDescription(), domain.getOwnerUsername(), assetCount,
                domain.getUpdatedAt());
    }
}
