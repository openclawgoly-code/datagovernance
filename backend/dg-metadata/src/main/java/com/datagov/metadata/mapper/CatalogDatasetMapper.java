package com.datagov.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogDatasetGovernanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CatalogDatasetMapper extends BaseMapper<CatalogDataset> {

    /**
     * 带治理摘要的目录分页。
     *
     * <p>筛选发生在 SQL 里而不是内存里：分页总数必须反映筛选结果，
     * 否则第 2 页的 total 与列表对不上。
     */
    @Select("""
            <script>
            SELECT d.*,
                   g.domain_id AS governance_domain_id,
                   dm.name AS domain_name,
                   g.owner_username,
                   COALESCE(g.certification_status, 'NONE') AS certification_status
              FROM md_catalog_dataset d
              LEFT JOIN md_asset_governance g
                     ON g.dataset_id = d.id AND g.workspace_id = d.workspace_id
              LEFT JOIN md_business_domain dm
                     ON dm.id = g.domain_id
             WHERE d.workspace_id = #{workspaceId}
               AND d.source_id = #{sourceId}
               AND d.deleted = FALSE
               <if test="keyword != null and keyword != ''">
                 AND (d.name ILIKE CONCAT('%', #{keyword}, '%')
                      OR d.qualified_name ILIKE CONCAT('%', #{keyword}, '%'))
               </if>
               <if test="domainId != null and domainId != ''">
                 AND g.domain_id = #{domainId}
               </if>
               <if test="owner != null and owner != ''">
                 AND g.owner_username = #{owner}
               </if>
               <if test="certificationStatus != null and certificationStatus != ''">
                 AND COALESCE(g.certification_status, 'NONE') = #{certificationStatus}
               </if>
             ORDER BY d.qualified_name
            </script>
            """)
    IPage<CatalogDatasetGovernanceRow> selectGovernancePage(
            IPage<CatalogDatasetGovernanceRow> page,
            @Param("workspaceId") String workspaceId,
            @Param("sourceId") String sourceId,
            @Param("keyword") String keyword,
            @Param("domainId") String domainId,
            @Param("owner") String owner,
            @Param("certificationStatus") String certificationStatus);
}
