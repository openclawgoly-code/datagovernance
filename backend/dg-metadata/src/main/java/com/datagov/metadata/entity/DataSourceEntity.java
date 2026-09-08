package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.DataSourceStatus;
import lombok.Data;

import java.time.Instant;

/**
 * 数据源定义(功能 1-4)。对应 {@code md_datasource}。
 *
 * <p><b>这里没有口令字段,而且不该有。</b> 口令通过 {@link #credentialId} 指向
 * {@code pf_credential},Metadata 拿不到也无权解密。{@link #username} 留在这里
 * 是因为它不是秘密 —— 列表页要显示"这个库用哪个账号连的",把它塞进凭据里
 * 会让一次列表查询变成 N 次解密。
 */
@Data
@TableName("md_datasource")
public class DataSourceEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String name;
    private DataSourceType type;
    private DataSourceType.Family family;
    private DataSourceStatus status;
    private String description;

    /** 所属目录(功能 5);null 表示未分类 */
    private String catalogId;

    // ── 周期连通性检查(功能 6)────────────────────────────────────────
    // 与手工测试走不同的状态迁移边:探测失败进 UNREACHABLE(本来是好的),
    // 手工失败回 DRAFT(配置可能没配对)。因此两者的时间戳也各记各的。
    private Boolean probeEnabled;
    private Integer probeIntervalMinutes;
    private Instant lastProbeAt;

    // ── 连接参数(非机密部分)────────────────────────────────────────
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    /** 驱动扩展参数,JSON 对象字符串 */
    private String propertiesJson;
    private String jdbcUrlOverride;
    private String baseUrl;
    /** → pf_credential.id,唯一的凭据通路 */
    private String credentialId;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;

    // ── 最近一次连通性测试的结论 ──────────────────────────────────────
    // Metadata 只记结论不记过程:完整执行历史属于 Runtime/Governance(P2/P4)
    private Instant lastTestAt;
    private Boolean lastTestSuccess;
    private String lastTestMessage;
    private Long lastTestLatencyMs;

    /** 定义版本号,每次变更 +1,与 md_datasource_version 对应 */
    private Integer version;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @TableLogic
    private Boolean deleted;
}
