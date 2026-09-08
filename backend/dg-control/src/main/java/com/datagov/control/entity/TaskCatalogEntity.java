package com.datagov.control.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 任务目录节点(功能 16)。
 *
 * <p>与数据源目录(功能 5)同构 —— 人工维护的组织结构,不是任务的分类属性。
 * 复用同一套树形结构而不是发明新的:用户对这两棵树的心智模型是一样的,
 * 而两套不同的树会让"为什么这里能拖拽那里不能"变成一个要解释的问题。
 */
@Data
@TableName("ctl_task_catalog")
public class TaskCatalogEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    /** null 表示根节点 */
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
