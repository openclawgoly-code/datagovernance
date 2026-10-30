package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.GlossaryEntities.GlossaryBinding;
import com.datagov.metadata.entity.GlossaryEntities.GlossaryTerm;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.GlossaryBindingMapper;
import com.datagov.metadata.mapper.GlossaryTermMapper;
import com.datagov.metadata.service.GlossaryService.AssetTermView;
import com.datagov.metadata.service.GlossaryService.BindingRequest;
import com.datagov.metadata.service.GlossaryService.TermRequest;
import com.datagov.metadata.service.GlossaryService.TermView;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("业务术语")
class GlossaryServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private GlossaryTermMapper termMapper;
    private GlossaryBindingMapper bindingMapper;
    private BusinessDomainMapper domainMapper;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private WorkspaceService workspaceService;
    private UserService userService;
    private GlossaryService service;

    @BeforeEach
    void setUp() {
        termMapper = mock(GlossaryTermMapper.class);
        bindingMapper = mock(GlossaryBindingMapper.class);
        domainMapper = mock(BusinessDomainMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        workspaceService = mock(WorkspaceService.class);
        userService = mock(UserService.class);
        service = new GlossaryService(termMapper, bindingMapper, domainMapper,
                datasetMapper, columnMapper, workspaceService, userService,
                new ObjectMapper());
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(workspaceService.listMemberIds(WORKSPACE)).thenReturn(List.of("usr_1"));
        when(userService.listByIds(List.of("usr_1"))).thenReturn(List.of(
                new com.datagov.platform.dto.UserView("usr_1", "alice", "Alice", null,
                        null, "ACTIVE", false, null, null)));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("术语")
    class Terms {

        @Test
        @DisplayName("创建术语为 DRAFT，同义词写 JSON")
        void createDraft() {
            when(termMapper.selectCount(any())).thenReturn(0L);
            when(termMapper.insert(any(GlossaryTerm.class))).thenReturn(1);
            when(domainMapper.selectList(any())).thenReturn(List.of());

            TermView view = service.createTerm(new TermRequest("GMV", "商品交易总额",
                    List.of("成交总额", "销售额"), null, "alice"));

            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.synonyms()).containsExactly("成交总额", "销售额");
            assertThat(view.ownerUsername()).isEqualTo("alice");
            verify(termMapper).insert(any(GlossaryTerm.class));
        }

        @Test
        @DisplayName("大小写重复术语 —— 409")
        void duplicateCaseInsensitive() {
            when(termMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.createTerm(
                    new TermRequest("gmv", "商品交易总额", List.of(), null, null)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_GLOSSARY_TERM_DUPLICATED));
            verify(termMapper, never()).insert(any(GlossaryTerm.class));
        }

        @Test
        @DisplayName("发布记录发布人与时间")
        void publishRecordsOperator() {
            GlossaryTerm term = term("DRAFT");
            when(termMapper.selectById("term_1")).thenReturn(term);
            when(termMapper.updateById(any(GlossaryTerm.class))).thenReturn(1);
            when(domainMapper.selectList(any())).thenReturn(List.of());
            when(bindingMapper.selectList(any())).thenReturn(List.of());

            TermView view = service.publish("term_1");

            assertThat(view.status()).isEqualTo("PUBLISHED");
            assertThat(view.publishedBy()).isEqualTo(USER);
            assertThat(view.publishedAt()).isNotNull();
        }

        @Test
        @DisplayName("未发布术语不能废弃")
        void deprecateRequiresPublished() {
            when(termMapper.selectById("term_1")).thenReturn(term("DRAFT"));

            assertThatThrownBy(() -> service.deprecate("term_1"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION));
        }
    }

    @Nested
    @DisplayName("绑定")
    class Bindings {

        @Test
        @DisplayName("字段不属于数据集 —— 400")
        void columnMustBelongToDataset() {
            when(termMapper.selectById("term_1")).thenReturn(term("PUBLISHED"));
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            CatalogColumn foreign = column("catcol_2", "catds_2");
            when(columnMapper.selectById("catcol_2")).thenReturn(foreign);

            assertThatThrownBy(() -> service.createBinding("term_1",
                    new BindingRequest("catds_1", "catcol_2", null)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_GLOSSARY_BINDING_INVALID));
            verify(bindingMapper, never()).insert(any(GlossaryBinding.class));
        }

        @Test
        @DisplayName("资产详情只展示 PUBLISHED 术语")
        void assetGlossaryOnlyPublished() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(bindingMapper.selectList(any())).thenReturn(List.of(binding("bind_1", "term_1")));
            GlossaryTerm draft = term("DRAFT");
            when(termMapper.selectList(any())).thenReturn(List.of(draft));

            List<AssetTermView> terms = service.assetGlossary("catds_1");

            assertThat(terms).isEmpty();
        }

        @Test
        @DisplayName("字段绑定在资产术语中带字段名")
        void assetGlossaryIncludesColumn() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            GlossaryBinding binding = binding("bind_1", "term_1");
            binding.setColumnId("catcol_1");
            when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
            GlossaryTerm published = term("PUBLISHED");
            when(termMapper.selectList(any())).thenReturn(List.of(published));
            when(columnMapper.selectList(any())).thenReturn(List.of(
                    column("catcol_1", "catds_1")));
            when(domainMapper.selectList(any())).thenReturn(List.of());

            List<AssetTermView> terms = service.assetGlossary("catds_1");

            assertThat(terms).hasSize(1);
            assertThat(terms.get(0).columnName()).isEqualTo("amount");
        }
    }

    private static GlossaryTerm term(String status) {
        GlossaryTerm term = new GlossaryTerm();
        term.setId("term_1");
        term.setWorkspaceId(WORKSPACE);
        term.setTerm("GMV");
        term.setDefinition("商品交易总额");
        term.setStatus(status);
        term.setCreatedAt(Instant.now());
        term.setUpdatedAt(Instant.now());
        return term;
    }

    private static GlossaryBinding binding(String id, String termId) {
        GlossaryBinding binding = new GlossaryBinding();
        binding.setId(id);
        binding.setWorkspaceId(WORKSPACE);
        binding.setTermId(termId);
        binding.setDatasetId("catds_1");
        binding.setCreatedAt(Instant.now());
        return binding;
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName("orders");
        dataset.setQualifiedName("dwd.orders");
        return dataset;
    }

    private static CatalogColumn column(String id, String datasetId) {
        CatalogColumn column = new CatalogColumn();
        column.setId(id);
        column.setDatasetId(datasetId);
        column.setName("amount");
        return column;
    }
}
