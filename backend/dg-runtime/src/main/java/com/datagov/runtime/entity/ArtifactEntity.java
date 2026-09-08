package com.datagov.runtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 作业制品(序号 32「文件管理」)。
 *
 * <p><b>菜单在「基础配置」下,归属却是 Runtime</b>(架构风险 R2)。这不是笔误:
 * 这里存的是作业要执行的 JAR / Python 包,它的生命周期与执行绑定 —— 一个被
 * 正在运行的流任务引用的 JAR 不能删。把它当成"配置项"管理,就会有人在界面上
 * 顺手删掉一个跑了三个月的实时任务正在用的包。
 *
 * <p>制品<b>不可变</b>:同名同版本只能上传一次。要改内容就发新版本。可变的
 * 制品意味着"上周跑成功的那次执行"今天再跑可能是另一个结果,而那正是
 * 可复现性要排除的东西。
 */
@Data
@TableName("rt_artifact")
public class ArtifactEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    /** null 表示平台级制品(所有空间可用);非 null 表示某空间专属 */
    private String workspaceId;

    private String name;
    /** 语义化版本或任意标签。与 name 一起唯一 */
    private String version;
    /** JAR / PYTHON / SQL / OTHER */
    private String type;

    private String description;

    /** 存储相对路径。不存绝对路径 —— 换个部署目录就全失效了 */
    private String storagePath;
    private Long sizeBytes;
    /** 内容摘要。用来判断"这真的是同一个包吗" */
    private String checksumSha256;
    private String originalFilename;

    /**
     * 被多少个任务定义引用。
     *
     * <p>与规则的引用计数同一个套路(功能 17):被引用的制品不许删,否则任务
     * 会在凌晨的调度里找不到自己的 JAR。
     */
    private Integer refCount;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @TableLogic
    private Boolean deleted;
}
