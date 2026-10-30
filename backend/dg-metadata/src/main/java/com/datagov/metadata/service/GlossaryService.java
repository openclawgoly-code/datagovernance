package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.GovernanceEntities.BusinessDomain;
import com.datagov.metadata.entity.GlossaryEntities.GlossaryBinding;
import com.datagov.metadata.entity.GlossaryEntities.GlossaryTerm;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.GlossaryBindingMapper;
import com.datagov.metadata.mapper.GlossaryTermMapper;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务术语（P2）。
 *
 * <p>术语与绑定是两件事：术语是 workspace 级词汇，绑定是术语与资产/字段的
 * 关系。资产详情只展示 PUBLISHED 术语；DRAFT 是内部草稿，DEPRECATED 保留在
 * 管理页作为历史，不再对外展示。
 */
@Service
public class GlossaryService {

    private static final Set<String> STATUS = Set.of("DRAFT", "PUBLISHED", "DEPRECATED");

    private final GlossaryTermMapper termMapper;
    private final GlossaryBindingMapper bindingMapper;
    private final BusinessDomainMapper domainMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final WorkspaceService workspaceService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public GlossaryService(GlossaryTermMapper termMapper,
                           GlossaryBindingMapper bindingMapper,
                           BusinessDomainMapper domainMapper,
                           CatalogDatasetMapper datasetMapper,
                           CatalogColumnMapper columnMapper,
                           WorkspaceService workspaceService,
                           UserService userService,
                           ObjectMapper objectMapper) {
        this.termMapper = termMapper;
        this.bindingMapper = bindingMapper;
        this.domainMapper = domainMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.workspaceService = workspaceService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    // ── DTO ─────────────────────────────────────────────────────────────

    public record TermRequest(String term, String definition, List<String> synonyms,
                              String domainId, String ownerUsername) {
    }

    public record TermView(String id, String term, String definition,
                           List<String> synonyms, String domainId, String domainName,
                           String status, String ownerUsername, String publishedBy,
                           Instant publishedAt, Instant createdAt, Instant updatedAt,
                           long bindingCount) {
    }

    public record BindingRequest(String datasetId, String columnId, String note) {
    }

    public record BindingView(String id, String termId, String datasetId, String datasetName,
                              String datasetQualifiedName, String columnId, String columnName,
                              String note, Instant createdAt) {
    }

    public record AssetTermView(String termId, String term, String definition,
                                List<String> synonyms, String domainName,
                                String bindingId, String columnId, String columnName,
                                String note) {
    }

    // ── 术语 CRUD ───────────────────────────────────────────────────────

    public PageResult<TermView> listTerms(long page, long size, String keyword,
                                          String domainId, String status) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String term = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String domain = domainId == null || domainId.isBlank() ? null : domainId.trim();
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (statusFilter != null && !STATUS.contains(statusFilter)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "未知的术语状态: " + status);
        }
        LambdaQueryWrapper<GlossaryTerm> query = new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getWorkspaceId, workspaceId)
                .eq(domain != null, GlossaryTerm::getDomainId, domain)
                .eq(statusFilter != null, GlossaryTerm::getStatus, statusFilter)
                .and(term != null, w -> w.like(GlossaryTerm::getTerm, term)
                        .or().like(GlossaryTerm::getDefinition, term)
                        .or().like(GlossaryTerm::getSynonymsJson, term))
                .orderByAsc(GlossaryTerm::getTerm);
        Page<GlossaryTerm> result = termMapper.selectPage(Page.of(page, size), query);
        Map<String, Long> counts = bindingCounts(workspaceId,
                result.getRecords().stream().map(GlossaryTerm::getId).toList());
        Map<String, String> domains = domainNames(workspaceId);
        return PageResult.of(result.getRecords().stream()
                        .map(value -> toView(value, domains, counts.getOrDefault(value.getId(), 0L)))
                        .toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public TermView createTerm(TermRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        String term = requireText(request == null ? null : request.term(), "术语名称", 128);
        String definition = requireText(request == null ? null : request.definition(), "术语定义", 2048);
        requireTermAvailable(workspaceId, term, null);
        String domainId = resolveDomain(workspaceId, request.domainId());
        String owner = resolveOwner(workspaceId, request.ownerUsername());

        Instant now = Instant.now();
        GlossaryTerm entity = new GlossaryTerm();
        entity.setId(Ids.of("term"));
        entity.setWorkspaceId(workspaceId);
        entity.setTerm(term);
        entity.setDefinition(definition);
        entity.setSynonymsJson(writeSynonyms(request.synonyms()));
        entity.setDomainId(domainId);
        entity.setStatus("DRAFT");
        entity.setOwnerUsername(owner);
        entity.setCreatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);
        termMapper.insert(entity);
        return toView(entity, domainNames(workspaceId), 0);
    }

    @Transactional
    public TermView updateTerm(String id, TermRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        GlossaryTerm entity = requireTerm(workspaceId, id);
        String term = requireText(request == null ? null : request.term(), "术语名称", 128);
        String definition = requireText(request == null ? null : request.definition(), "术语定义", 2048);
        requireTermAvailable(workspaceId, term, id);
        entity.setTerm(term);
        entity.setDefinition(definition);
        entity.setSynonymsJson(writeSynonyms(request.synonyms()));
        entity.setDomainId(resolveDomain(workspaceId, request.domainId()));
        entity.setOwnerUsername(resolveOwner(workspaceId, request.ownerUsername()));
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(WorkspaceContext.require().userId());
        termMapper.updateById(entity);
        return toView(entity, domainNames(workspaceId),
                bindingCounts(workspaceId, List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional
    public TermView publish(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        GlossaryTerm entity = requireTerm(workspaceId, id);
        if ("PUBLISHED".equals(entity.getStatus())) {
            return toView(entity, domainNames(workspaceId), 0);
        }
        entity.setStatus("PUBLISHED");
        entity.setPublishedBy(operator);
        entity.setPublishedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(operator);
        termMapper.updateById(entity);
        return toView(entity, domainNames(workspaceId),
                bindingCounts(workspaceId, List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional
    public TermView deprecate(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        GlossaryTerm entity = requireTerm(workspaceId, id);
        if (!"PUBLISHED".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "只有已发布术语可以废弃");
        }
        entity.setStatus("DEPRECATED");
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(WorkspaceContext.require().userId());
        termMapper.updateById(entity);
        return toView(entity, domainNames(workspaceId),
                bindingCounts(workspaceId, List.of(id)).getOrDefault(id, 0L));
    }

    // ── 绑定 ────────────────────────────────────────────────────────────

    public List<BindingView> listBindings(String termId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireTerm(workspaceId, termId);
        List<GlossaryBinding> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<GlossaryBinding>()
                        .eq(GlossaryBinding::getWorkspaceId, workspaceId)
                        .eq(GlossaryBinding::getTermId, termId)
                        .orderByDesc(GlossaryBinding::getCreatedAt));
        return toBindingViews(workspaceId, bindings);
    }

    @Transactional
    public BindingView createBinding(String termId, BindingRequest request) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        GlossaryTerm term = requireTerm(workspaceId, termId);
        if (request == null || request.datasetId() == null || request.datasetId().isBlank()) {
            throw new BizException(ErrorCode.MTD_GLOSSARY_BINDING_INVALID, "绑定必须指定数据集");
        }
        CatalogDataset dataset = requireDataset(workspaceId, request.datasetId());
        String columnId = request.columnId() == null || request.columnId().isBlank()
                ? null : request.columnId().trim();
        if (columnId != null) {
            CatalogColumn column = columnMapper.selectById(columnId);
            if (column == null || !dataset.getId().equals(column.getDatasetId())) {
                throw new BizException(ErrorCode.MTD_GLOSSARY_BINDING_INVALID,
                        "字段不属于指定数据集");
            }
        }
        Long duplicate = bindingMapper.selectCount(new LambdaQueryWrapper<GlossaryBinding>()
                .eq(GlossaryBinding::getTermId, termId)
                .eq(GlossaryBinding::getDatasetId, dataset.getId())
                .eq(columnId != null, GlossaryBinding::getColumnId, columnId)
                .isNull(columnId == null, GlossaryBinding::getColumnId));
        if (duplicate != null && duplicate > 0) {
            throw BizException.conflict(ErrorCode.MTD_GLOSSARY_BINDING_DUPLICATED,
                    term.getTerm());
        }

        GlossaryBinding binding = new GlossaryBinding();
        binding.setId(Ids.of("bind"));
        binding.setWorkspaceId(workspaceId);
        binding.setTermId(termId);
        binding.setDatasetId(dataset.getId());
        binding.setColumnId(columnId);
        binding.setNote(blankToNull(request.note()));
        binding.setCreatedAt(Instant.now());
        binding.setCreatedBy(operator);
        bindingMapper.insert(binding);
        return toBindingViews(workspaceId, List.of(binding)).get(0);
    }

    @Transactional
    public void deleteBinding(String termId, String bindingId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireTerm(workspaceId, termId);
        GlossaryBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null || !workspaceId.equals(binding.getWorkspaceId())
                || !termId.equals(binding.getTermId())) {
            throw BizException.notFound(ErrorCode.MTD_GLOSSARY_NOT_FOUND, bindingId);
        }
        bindingMapper.deleteById(bindingId);
    }

    // ── 资产视角 ────────────────────────────────────────────────────────

    /**
     * 资产详情只返回 PUBLISHED 术语。
     *
     * <p>绑定到数据集本身或该数据集的任一字段都算命中；DRAFT/DEPRECATED
     * 即使有绑定也不对外展示。
     */
    public List<AssetTermView> assetGlossary(String datasetId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(workspaceId, datasetId);
        List<GlossaryBinding> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<GlossaryBinding>()
                        .eq(GlossaryBinding::getWorkspaceId, workspaceId)
                        .eq(GlossaryBinding::getDatasetId, dataset.getId()));
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<String> termIds = bindings.stream().map(GlossaryBinding::getTermId).distinct().toList();
        List<GlossaryTerm> terms = termMapper.selectList(
                new LambdaQueryWrapper<GlossaryTerm>()
                        .eq(GlossaryTerm::getWorkspaceId, workspaceId)
                        .in(GlossaryTerm::getId, termIds)
                        .eq(GlossaryTerm::getStatus, "PUBLISHED"));
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<String, GlossaryTerm> termById = terms.stream()
                .filter(term -> "PUBLISHED".equals(term.getStatus()))
                .collect(Collectors.toMap(GlossaryTerm::getId, Function.identity()));
        if (termById.isEmpty()) {
            return List.of();
        }
        Map<String, String> domains = domainNames(workspaceId);
        Map<String, CatalogColumn> columnById = columns(workspaceId, bindings);

        List<AssetTermView> result = new ArrayList<>();
        for (GlossaryBinding binding : bindings) {
            GlossaryTerm term = termById.get(binding.getTermId());
            if (term == null) {
                continue;
            }
            CatalogColumn column = binding.getColumnId() == null
                    ? null : columnById.get(binding.getColumnId());
            result.add(new AssetTermView(term.getId(), term.getTerm(),
                    term.getDefinition(), readSynonyms(term.getSynonymsJson()),
                    domains.get(term.getDomainId()), binding.getId(),
                    binding.getColumnId(), column == null ? null : column.getName(),
                    binding.getNote()));
        }
        result.sort((left, right) -> left.term().compareToIgnoreCase(right.term()));
        return result;
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private GlossaryTerm requireTerm(String workspaceId, String id) {
        GlossaryTerm term = termMapper.selectById(id);
        if (term == null || !workspaceId.equals(term.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_GLOSSARY_NOT_FOUND, id);
        }
        return term;
    }

    private CatalogDataset requireDataset(String workspaceId, String datasetId) {
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }
        return dataset;
    }

    private String resolveDomain(String workspaceId, String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return null;
        }
        BusinessDomain domain = domainMapper.selectById(domainId.trim());
        if (domain == null || !workspaceId.equals(domain.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_DOMAIN_NOT_FOUND, domainId);
        }
        return domain.getId();
    }

    private String resolveOwner(String workspaceId, String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return null;
        }
        String owner = ownerUsername.trim();
        List<String> memberIds = workspaceService.listMemberIds(workspaceId);
        boolean active = !memberIds.isEmpty() && userService.listByIds(memberIds).stream()
                .anyMatch(user -> "ACTIVE".equals(user.status()) && owner.equals(user.username()));
        if (!active) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "术语负责人必须是当前空间的 ACTIVE 成员: " + owner);
        }
        return owner;
    }

    private void requireTermAvailable(String workspaceId, String term, String excludeId) {
        LambdaQueryWrapper<GlossaryTerm> query = new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getWorkspaceId, workspaceId)
                .apply("lower(term) = {0}", term.toLowerCase());
        if (excludeId != null) {
            query.ne(GlossaryTerm::getId, excludeId);
        }
        Long count = termMapper.selectCount(query);
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.MTD_GLOSSARY_TERM_DUPLICATED, term);
        }
    }

    private Map<String, String> domainNames(String workspaceId) {
        return domainMapper.selectList(new LambdaQueryWrapper<BusinessDomain>()
                        .eq(BusinessDomain::getWorkspaceId, workspaceId)).stream()
                .collect(Collectors.toMap(BusinessDomain::getId, BusinessDomain::getName,
                        (left, right) -> left));
    }

    private Map<String, Long> bindingCounts(String workspaceId, List<String> termIds) {
        if (termIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (GlossaryBinding binding : bindingMapper.selectList(
                new LambdaQueryWrapper<GlossaryBinding>()
                        .eq(GlossaryBinding::getWorkspaceId, workspaceId)
                        .in(GlossaryBinding::getTermId, termIds))) {
            counts.merge(binding.getTermId(), 1L, Long::sum);
        }
        return counts;
    }

    private List<BindingView> toBindingViews(String workspaceId, List<GlossaryBinding> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<String> datasetIds = bindings.stream().map(GlossaryBinding::getDatasetId)
                .distinct().toList();
        Map<String, CatalogDataset> datasets = datasetMapper.selectList(
                        new LambdaQueryWrapper<CatalogDataset>()
                                .eq(CatalogDataset::getWorkspaceId, workspaceId)
                                .in(CatalogDataset::getId, datasetIds)).stream()
                .collect(Collectors.toMap(CatalogDataset::getId, Function.identity()));
        Map<String, CatalogColumn> columns = columns(workspaceId, bindings);
        return bindings.stream().map(binding -> {
            CatalogDataset dataset = datasets.get(binding.getDatasetId());
            CatalogColumn column = binding.getColumnId() == null
                    ? null : columns.get(binding.getColumnId());
            return new BindingView(binding.getId(), binding.getTermId(),
                    binding.getDatasetId(), dataset == null ? null : dataset.getName(),
                    dataset == null ? null : dataset.getQualifiedName(),
                    binding.getColumnId(), column == null ? null : column.getName(),
                    binding.getNote(), binding.getCreatedAt());
        }).toList();
    }

    private Map<String, CatalogColumn> columns(String workspaceId, List<GlossaryBinding> bindings) {
        List<String> columnIds = bindings.stream().map(GlossaryBinding::getColumnId)
                .filter(id -> id != null).distinct().toList();
        if (columnIds.isEmpty()) {
            return Map.of();
        }
        return columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, columnIds)).stream()
                .collect(Collectors.toMap(CatalogColumn::getId, Function.identity()));
    }

    private TermView toView(GlossaryTerm term, Map<String, String> domains, long bindingCount) {
        return new TermView(term.getId(), term.getTerm(), term.getDefinition(),
                readSynonyms(term.getSynonymsJson()), term.getDomainId(),
                term.getDomainId() == null ? null : domains.get(term.getDomainId()),
                term.getStatus(), term.getOwnerUsername(), term.getPublishedBy(),
                term.getPublishedAt(), term.getCreatedAt(), term.getUpdatedAt(),
                bindingCount);
    }

    private String writeSynonyms(List<String> synonyms) {
        if (synonyms == null || synonyms.isEmpty()) {
            return null;
        }
        List<String> cleaned = synonyms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "同义词无法序列化", e.getMessage(), e);
        }
    }

    private List<String> readSynonyms(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, field + "不能为空");
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    field + "不能超过 " + max + " 字");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
