package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 问数查询事实表。只追加,不修改。 */
@Data
@TableName("ig_query_log")
public class InsightQueryLog {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String userId;
    private String username;
    private String question;
    private Integer agentId;
    private Long datasetId;
    private String queryState;
    private String generatedSql;
    private Integer rowCount;
    private Long elapsedMs;
    private String maskedColumns;
    private Boolean succeeded;
    private String errorMessage;
    private Instant createdAt;
}
