package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.GovernanceEntities.AssetGovernance;
import com.datagov.metadata.entity.GovernanceEntities.BusinessDomain;
import com.datagov.metadata.mapper.AssetGovernanceMapper;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.service.AssetGovernanceService.DomainRequest;
import com.datagov.metadata.service.AssetGovernanceService.GovernanceRequest;
import com.datagov.metadata.service.AssetGovernanceService.GovernanceView;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
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

@DisplayName("资产治理")
class AssetGovernanceServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private BusinessDomainMapper domainMapper;
    private AssetGovernanceMapper governanceMapper;
    private CatalogDatasetMapper datasetMapper;
    private WorkspaceService workspaceService;
    private UserService userService;
    private AssetGovernanceService service;

    @BeforeEach
    void setUp() {
        domainMapper = mock(BusinessDomainMapper.class);
        governanceMapper = mock(AssetGovernanceMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        workspaceService = mock(WorkspaceService.class);
        userService = mock(UserService.class);
        service = new AssetGovernanceService(domainMapper, governanceMapper, datasetMapper,
                workspaceService, userService);
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));
        when(workspaceService.listMemberIds(WORKSPACE)).thenReturn(List.of("usr_1", "usr_2"));
        when(userService.listByIds(List.of("usr_1", "usr_2"))).thenReturn(List.of(
                user("usr_1", "alice", "ACTIVE"),
                user("usr_2", "bob", "ACTIVE")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("读取")
    class Read {

        @Test
        @DisplayName("未配置治理 —— 返回 NONE 而不是 404")
        void unconfiguredReturnsNone() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(null);

            GovernanceView view = service.getGovernance("catds_1");

            assertThat(view.certificationStatus()).isEqualTo("NONE");
            assertThat(view.domainId()).isNull();
            assertThat(view.ownerUsername()).isNull();
            assertThat(view.ownerActive()).isTrue();
        }

        @Test
        @DisplayName("历史 owner 已不在空间 —— 保留原值并标记失效")
        void missingOwnerMarkedInactive() {
            AssetGovernance governance = governance();
            governance.setOwnerUsername("carol");
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(governance);

            GovernanceView view = service.getGovernance("catds_1");

            assertThat(view.ownerUsername()).isEqualTo("carol");
            assertThat(view.ownerActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("写入")
    class Write {

        @Test
        @DisplayName("非空间成员不能成为 owner —— 400 且不写库")
        void nonMemberOwnerRejected() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.updateGovernance("catds_1",
                    new GovernanceRequest(null, "outsider", null, "NONE", null, null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("ACTIVE 成员");
            verify(governanceMapper, never()).insert(any(AssetGovernance.class));
        }

        @Test
        @DisplayName("认证 CERTIFIED 记录认证人与时间")
        void certifiedRecordsOperator() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(null);
            when(governanceMapper.insert(any(AssetGovernance.class))).thenAnswer(invocation -> {
                AssetGovernance value = invocation.getArgument(0);
                when(governanceMapper.selectOne(any())).thenReturn(value);
                return 1;
            });

            GovernanceView view = service.updateGovernance("catds_1",
                    new GovernanceRequest(null, "bob", null, "CERTIFIED", null, null));

            assertThat(view.certificationStatus()).isEqualTo("CERTIFIED");
            assertThat(view.certifiedBy()).isEqualTo(USER);
            assertThat(view.certifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("DEPRECATED 必须有说明 —— 400")
        void deprecatedRequiresNote() {
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.updateGovernance("catds_1",
                    new GovernanceRequest(null, null, null, "DEPRECATED", " ", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("说明");
            verify(governanceMapper, never()).insert(any(AssetGovernance.class));
        }

        @Test
        @DisplayName("历史 owner 未变化时允许更新其他字段")
        void unchangedMissingOwnerDoesNotBlockUpdate() {
            AssetGovernance existing = governance();
            existing.setOwnerUsername("carol");
            when(datasetMapper.selectOne(any())).thenReturn(dataset());
            when(governanceMapper.selectOne(any())).thenReturn(existing);
            when(governanceMapper.updateById(any(AssetGovernance.class))).thenReturn(1);

            GovernanceView view = service.updateGovernance("catds_1",
                    new GovernanceRequest(null, "carol", null, "NONE", null, null));

            assertThat(view.ownerUsername()).isEqualTo("carol");
            verify(governanceMapper).updateById(any(AssetGovernance.class));
        }
    }

    @Nested
    @DisplayName("业务域")
    class Domains {

        @Test
        @DisplayName("code 统一小写并检查唯一")
        void createNormalizesCode() {
            when(domainMapper.selectCount(any())).thenReturn(0L);
            when(domainMapper.insert(any(BusinessDomain.class))).thenReturn(1);

            var view = service.createDomain(new DomainRequest(" Sales ", "销售", null, null));

            assertThat(view.code()).isEqualTo("sales");
            assertThat(view.name()).isEqualTo("销售");
        }

        @Test
        @DisplayName("重复 code —— 409")
        void duplicateCodeRejected() {
            when(domainMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.createDomain(
                    new DomainRequest("sales", "销售", null, null)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_DOMAIN_CODE_DUPLICATED));
            verify(domainMapper, never()).insert(any(BusinessDomain.class));
        }

        @Test
        @DisplayName("仍被资产引用 —— 禁止删除")
        void referencedDomainCannotBeDeleted() {
            when(domainMapper.selectById("dom_1")).thenReturn(domain());
            when(governanceMapper.selectCount(any())).thenReturn(3L);

            assertThatThrownBy(() -> service.deleteDomain("dom_1"))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(ErrorCode.MTD_DOMAIN_IN_USE));
            verify(domainMapper, never()).deleteById("dom_1");
        }
    }

    private static CatalogDataset dataset() {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId("catds_1");
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName("orders");
        dataset.setQualifiedName("dwd.orders");
        return dataset;
    }

    private static AssetGovernance governance() {
        AssetGovernance governance = new AssetGovernance();
        governance.setId("gov_1");
        governance.setWorkspaceId(WORKSPACE);
        governance.setDatasetId("catds_1");
        governance.setCertificationStatus("NONE");
        governance.setUpdatedAt(Instant.now());
        return governance;
    }

    private static BusinessDomain domain() {
        BusinessDomain domain = new BusinessDomain();
        domain.setId("dom_1");
        domain.setWorkspaceId(WORKSPACE);
        domain.setCode("sales");
        domain.setName("销售");
        return domain;
    }

    private static UserView user(String id, String username, String status) {
        return new UserView(id, username, username, null, null, status, false,
                null, null);
    }
}
