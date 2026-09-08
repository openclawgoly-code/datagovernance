package com.datagov.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Platform / Tenancy Space 的持久化实体。
 *
 * <p>集中在一个文件里是刻意的:这九张表构成一个紧密的整体(租户 → 成员 → 角色 → 权限),
 * 总是被一起阅读、一起演进。拆成九个文件只增加跳转成本,而每个类本身
 * 只有字段没有行为。
 *
 * <p><b>术语警示(风险 R3)</b>:{@link Workspace} 是平台功能菜单里的「空间」=<b>租户</b>,
 * 与架构中的 Space(边界)同名不同物。本模块乃至全代码库中,
 * 任何运行时对象一律用 Workspace 命名,绝不用 Space。
 */
public final class PlatformEntities {

    private PlatformEntities() {
    }

    /** 空间(租户)—— 功能 28 */
    @Data
    @TableName("pf_workspace")
    public static class Workspace {
        @TableId(type = IdType.INPUT)
        private String id;
        /** 空间标识,全局唯一,用于 URL 与 API 引用 */
        private String code;
        private String name;
        private String description;
        /** ACTIVE / SUSPENDED */
        private String status;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
        @TableLogic
        private Boolean deleted;
    }

    /**
     * 空间鉴权密钥 —— 功能 28「空间鉴权密钥管理」。
     *
     * <p>单独成表而非内联在 {@link Workspace} 上,是为了支持轮换:
     * 轮换期间新旧密钥需要并存一段时间(让还在用旧密钥的调用方有时间切换),
     * 内联字段做不到"换了但旧的还能用"。
     */
    @Data
    @TableName("pf_workspace_secret")
    public static class WorkspaceSecret {
        @TableId(type = IdType.INPUT)
        private String id;
        private String workspaceId;
        private String accessKey;
        /** AES-GCM 密文 */
        private String secretKeyEnc;
        /** ACTIVE / RETIRED */
        private String status;
        private Instant rotatedAt;
        private Instant createdAt;
        private String createdBy;
    }

    /** 用户 —— 功能 30 */
    @Data
    @TableName("pf_user")
    public static class User {
        @TableId(type = IdType.INPUT)
        private String id;
        private String username;
        /** BCrypt 单向散列。与可逆加密的 secretKeyEnc 不同,永远不需要还原 */
        private String passwordHash;
        private String displayName;
        private String email;
        private String phone;
        /** ACTIVE / DISABLED */
        private String status;
        /** 唯一可跨空间的身份 */
        private Boolean platformAdmin;
        private Instant lastLoginAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
        @TableLogic
        private Boolean deleted;
    }

    /** 空间成员(授权用户绑定)—— 功能 28 的「授权用户」 */
    @Data
    @TableName("pf_workspace_member")
    public static class WorkspaceMember {
        private String workspaceId;
        private String userId;
        private Instant createdAt;
        private String createdBy;
    }

    /**
     * 权限项 —— 功能 29 的权限全集。
     *
     * <p>{@code ownerSpace} 记录该菜单项归属哪个架构 Space。这一列直接编码了
     * 本项目最关键的一条推导结论:功能菜单「基础配置」横跨 Platform / Runtime /
     * Governance 三个 Space,不是一个模块。P1 只落地其中的 PLATFORM 部分,
     * 将来 31/32(执行器、JAR)与 33(告警渠道)会带着不同的 ownerSpace
     * 插入同一张表 —— 菜单仍在一起,归属却始终可查。
     */
    @Data
    @TableName("pf_permission")
    public static class Permission {
        @TableId(type = IdType.INPUT)
        private String code;
        private String name;
        /** MENU / ACTION */
        private String type;
        private String parentCode;
        private String routePath;
        private String icon;
        private Integer sortOrder;
        /** UI / METADATA / PLATFORM / RUNTIME / GOVERNANCE ... */
        private String ownerSpace;
        private Boolean builtIn;
    }

    /** 角色 —— 功能 29。workspaceId 为 null 表示平台级内置角色,对所有空间可见。 */
    @Data
    @TableName("pf_role")
    public static class Role {
        @TableId(type = IdType.INPUT)
        private String id;
        private String workspaceId;
        private String code;
        private String name;
        private String description;
        private Boolean builtIn;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
        @TableLogic
        private Boolean deleted;
    }

    @Data
    @TableName("pf_role_permission")
    public static class RolePermission {
        private String roleId;
        private String permissionCode;
    }

    /** 用户在某空间下的角色授予 —— 同一用户在不同空间可以有不同角色 */
    @Data
    @TableName("pf_user_role")
    public static class UserRole {
        private String workspaceId;
        private String userId;
        private String roleId;
        private Instant createdAt;
        private String createdBy;
    }

    /**
     * 凭据托管 —— 功能 4 的接口认证 + 功能 1-3 的数据库口令。
     *
     * <p>这是整个 P1 最重要的一条边界:凭据<b>只</b>存在于 Platform Space。
     * Metadata 的数据源持有的是 credentialId —— 一个不可解密的引用。
     * 等保要求凭据不落业务库;若把密文放进 md_datasource,那么任何一次
     * 数据源列表查询、导出或日志打印都可能把它带出去。
     */
    @Data
    @TableName("pf_credential")
    public static class Credential {
        @TableId(type = IdType.INPUT)
        private String id;
        private String workspaceId;
        private String name;
        /** NONE / BASIC / TOKEN / PASSWORD */
        private String authType;
        /** AES-GCM 密文,内含该认证方式所需的全部字段 */
        private String payloadEnc;
        private String description;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
        @TableLogic
        private Boolean deleted;
    }
}
