package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 数据源目录(功能 5)。对应 {@code md_datasource_catalog}。
 *
 * <p><b>务必与 {@link CatalogSnapshotEntity} 区分</b>,两者都叫 catalog 但完全不同:
 * <ul>
 *   <li>本类是<b>人工维护的组织结构</b> —— 用户按业务域、按系统给数据源分类,
 *       就像文件夹。它属于功能 5。</li>
 *   <li>{@link CatalogSnapshotEntity} 是<b>从目标库探测来的库表结构快照</b>,
 *       用户改不了它,它反映的是目标端的客观事实。它属于功能 7「数据查询」。</li>
 * </ul>
 * 这两个概念在需求文档里都用了"目录"一词,是本项目命名上第二处需要显式
 * 警示的地方(第一处是 Workspace 与 Space)。
 */
@Data
@TableName("md_datasource_catalog")
public class DataSourceCatalogEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;

    /** 父目录;null 表示根节点 */
    private String parentId;

    private String name;
    private String description;
    private Integer sortOrder;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @TableLogic
    private Boolean deleted;
}
