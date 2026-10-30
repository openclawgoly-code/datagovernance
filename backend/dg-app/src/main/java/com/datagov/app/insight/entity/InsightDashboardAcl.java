package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ig_dashboard_acl")
public class InsightDashboardAcl {

    @TableId(type = IdType.INPUT)
    private String id;

    private String dashboardId;
    private String username;
    private String permission;
    private Instant createdAt;
    private String createdBy;
}
