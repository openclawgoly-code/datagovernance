package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 库表结构快照(功能 5)。对应 {@code md_catalog_snapshot}。
 *
 * <p>存在的理由不只是缓存:「上次看到的结构长什么样」是 P2 字段映射与
 * P4 血缘的输入,也是 Schema 漂移检测(metadata.yaml 里的 {@code SchemaDrifted}
 * 事件)唯一可能的比较基准。只做内存缓存的话,这些都无从谈起。
 */
@Data
@TableName("md_catalog_snapshot")
public class CatalogSnapshotEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String datasourceId;

    /** ROOT / DATABASE / SCHEMA / TABLE / PATH,对应 CatalogPath.Level */
    private String level;

    private String pathDatabase;
    private String pathSchema;
    private String pathTable;
    /** 文件型数据源的目录路径 */
    private String pathLiteral;

    /** CatalogPage 的序列化结果 */
    private String payloadJson;
    private Integer itemCount;

    private Instant capturedAt;
    private String capturedBy;
}
