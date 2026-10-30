package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ig_dashboard")
public class InsightDashboard {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String name;
    private String description;
    private String createdBy;
    private String visibility;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
