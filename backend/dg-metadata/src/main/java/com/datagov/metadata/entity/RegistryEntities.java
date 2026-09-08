package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Intelligence 契约的落地(序号 34,SPACE-MODEL.md C+D.8 的第 3、4 条)。
 *
 * <p>序号 34 已确认<b>独立立项</b>(架构风险 R1):它包含 OWL 2 七层医学概念
 * 体系、多智能体标注工厂、数据飞轮与四维度质控引擎,工程量与序号 1-33 之和
 * 相当。本期<b>不实现它</b>,只锁定它与平台之间的四条契约 —— 而契约要写成
 * 代码才算数,写在文档里的契约会在对接时被两边各自理解一遍。
 *
 * <p>这个文件实现其中两条:
 * <ul>
 *   <li><b>第 3 条(注册)</b> —— Dataset / Model 的<b>标识与版本</b>注册进
 *       Metadata Registry,<b>内容</b>(影像、标注文件、模型权重、本体文件)
 *       存对象存储。所以这里只有 URI 与摘要,没有一个 BLOB 列 ——
 *       「不建第二注册中心」这句话的意思是平台的注册中心就是这一个</li>
 *   <li><b>第 4 条(语义)</b> —— {@code Column ──MapsTo──> Concept} 写进
 *       {@link RelationEdge},使医学语义能被平台的血缘与影响分析看见</li>
 * </ul>
 *
 * <p>另外两条不靠表结构而靠别处保证:
 * <ul>
 *   <li><b>第 1 条(取数)</b> —— Intelligence 不得直连业务数据源。它没有
 *       连接器依赖,只能读集成任务(序号 11-13)落地后的产物</li>
 *   <li><b>第 2 条(算力)</b> —— 训练与预标注作业通过 Control 提交为
 *       {@code PYTHON_JOB},复用统一 Execution 事实模型,因而自动获得
 *       监控(24)、告警(25)、审计(27)</li>
 * </ul>
 */
public final class RegistryEntities {

    private RegistryEntities() {
    }

    /**
     * 数据集 / 模型的注册项。
     *
     * <p>Dataset 与 Model 共用一张表而不是两张:它们的注册信息完全同构
     * (名字、类型、版本序列、内容在哪、谁产出的),差异全在<b>内容</b>里,
     * 而内容不归平台管。拆两张表会得到两套一模一样的 CRUD。
     */
    @Data
    @TableName("md_registry_artifact")
    public static class RegistryArtifact {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;

        /** DATASET / MODEL / ONTOLOGY */
        private String kind;
        private String name;
        private String description;

        /**
         * 产出它的执行记录。
         *
         * <p>这是契约第 2 条在数据上的痕迹:一个数据集版本是<b>哪一次执行</b>
         * 产出的,能一路追到 rt_execution,再追到当时的物理计划与定义版本。
         * 没有它,"这个模型是用哪版数据训的"就只能靠人记。
         */
        private String producedByExecutionId;

        /** 当前最新版本号。版本明细在 {@link RegistryArtifactVersion} */
        private Integer latestVersion;

        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;

        @TableLogic
        private Boolean deleted;
    }

    /**
     * 一个版本。
     *
     * <p><b>版本一旦发布就不可变</b>:这是"可复现"的前提。要改内容就发新版本 ——
     * 与作业制品(序号 32)同一条原则,理由也一样:一个可变的数据集版本意味着
     * "上个月那次评测"今天再跑可能是另一个结果。
     */
    @Data
    @TableName("md_registry_version")
    public static class RegistryArtifactVersion {
        @TableId(type = IdType.INPUT)
        private String id;

        private String artifactId;
        private String workspaceId;
        private Integer version;

        /**
         * 内容在哪。
         *
         * <p>只存 URI,不存内容。影像、标注文件、模型权重动辄几十 GB,
         * 平台的元数据库不该也不能承载它们 —— 契约第 3 条的原话是
         * 「内容存对象存储」。
         */
        private String contentUri;
        private Long sizeBytes;
        private String checksumSha256;
        /** 行数 / 样本数 / 参数量,按 kind 解释 */
        private Long itemCount;

        /** 由哪一次执行产出 */
        private String producedByExecutionId;
        /** 这一版是用哪个上游版本做出来的 —— 数据飞轮的一条边 */
        private String derivedFromVersionId;

        /** 自由形状的元信息(指标、超参、评测结果),平台不解释 */
        private String metadataJson;

        private Instant createdAt;
        private String createdBy;
    }

    /**
     * 关系边(契约第 4 条)。
     *
     * <p>刻意做成<b>通用的三元组</b>而不是一张 {@code column_concept_mapping} 表:
     * 血缘与影响分析要遍历的是"任意实体之间的任意关系",而不只是这一种。
     * 一张专用表会让下一种关系(Dataset ──DerivedFrom──> Table)又开一张表,
     * 而遍历这些关系的代码要 UNION 它们全部。
     *
     * <p>本期只写入 {@code MapsTo}:{@code Column ──MapsTo──> Concept}。
     * Concept 的本体归 Intelligence,平台只记这条边的两端标识 ——
     * 「不得拥有血缘的本体定义」是 Governance 的 must_not_do,同理适用于此。
     */
    @Data
    @TableName("md_relation_edge")
    public static class RelationEdge {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;

        /** 起点类型:COLUMN / TABLE / DATASET / MODEL / JOB */
        private String fromType;
        /**
         * 起点标识。
         *
         * <p>COLUMN 用 {@code 数据源ID:库.模式.表.字段} 这种可拼可拆的形式,
         * 而不是一个指向 md_catalog_snapshot 某一行的外键:快照会随刷新重建,
         * 外键会跟着失效,而这条语义映射不该因为刷新了一次目录就丢掉。
         */
        private String fromId;

        /** 关系类型。本期只有 MAPS_TO */
        private String relation;

        /** 终点类型:CONCEPT / DATASET / MODEL / TABLE */
        private String toType;
        /** 终点标识。CONCEPT 用本体里的 IRI —— 它的定义归 Intelligence */
        private String toId;
        /** 终点的可读名,便于界面显示而不必反查 Intelligence */
        private String toLabel;

        /** 置信度。自动抽取的映射需要它,人工确认的为 1.0 */
        private Double confidence;
        /** MANUAL / AUTO —— 自动映射要能被区分出来单独复核 */
        private String origin;

        private Instant createdAt;
        private String createdBy;

        @TableLogic
        private Boolean deleted;
    }
}
