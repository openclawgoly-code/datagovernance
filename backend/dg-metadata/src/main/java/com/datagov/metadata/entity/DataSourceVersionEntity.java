package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 数据源定义的版本快照(功能 8)。对应 {@code md_datasource_version}。
 *
 * <p>版本表是<b>只追加</b>的:没有逻辑删除字段,也不提供更新方法。
 * 一份变更历史如果可以被改写,它就不再是历史。
 */
@Data
@TableName("md_datasource_version")
public class DataSourceVersionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String datasourceId;
    private String workspaceId;
    private Integer version;

    /** CREATED / UPDATED / STATUS_CHANGED / DISABLED */
    private String changeType;

    /**
     * 该版本的完整定义序列化结果。
     *
     * <p>不含任何凭据内容 —— 数据源本身就不持有口令,只持有 credentialId,
     * 所以这份快照天然是安全的。这是把凭据挪出 Metadata 的一个附带好处:
     * 版本历史不再需要额外的脱敏逻辑。
     */
    private String snapshotJson;

    private String changeSummary;
    private Instant changedAt;
    private String changedBy;
}
