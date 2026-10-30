package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ig_supersonic_mapping")
public class InsightSupersonicMapping {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String platformUsername;
    private String supersonicUsername;
    private String credentialId;
    private Boolean enabled;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
