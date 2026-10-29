package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ig_dashboard_item")
public class InsightDashboardItem {

    @TableId(type = IdType.INPUT)
    private String id;

    private String dashboardId;
    private String question;
    private Long datasetId;
    private String chartType;
    private Integer sortOrder;
    private String createdBy;
    private Instant createdAt;
}
