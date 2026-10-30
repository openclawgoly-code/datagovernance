package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ig_dashboard_version")
public class InsightDashboardVersion {

    @TableId(type = IdType.INPUT)
    private String id;

    private String dashboardId;
    private Integer version;
    private String snapshotJson;
    private String createdBy;
    private Instant createdAt;
}
