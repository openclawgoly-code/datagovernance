package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Catalog 数据集 + 治理摘要的行视图。
 *
 * <p>只用于列表查询的 JOIN 结果：基础字段仍是 {@code md_catalog_dataset}
 * 的采集事实，治理字段为左连接得到的可空值。它不是持久化实体，
 * 因此没有 {@code @TableId}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("md_catalog_dataset")
public class CatalogDatasetGovernanceRow extends CatalogAssetEntities.CatalogDataset {
    private String governanceDomainId;
    private String domainName;
    private String ownerUsername;
    private String certificationStatus;
}
