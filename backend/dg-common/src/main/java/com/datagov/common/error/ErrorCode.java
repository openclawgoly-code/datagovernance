package com.datagov.common.error;

/**
 * 平台错误码。
 *
 * <p>前缀即归属 Space,这样一个错误码本身就能告诉你该找哪个模块:
 * <ul>
 *   <li>{@code PLT_} Platform/Tenancy</li>
 *   <li>{@code MTD_} Metadata</li>
 *   <li>{@code DAT_} Data(连接器)</li>
 *   <li>{@code SYS_} 平台通用</li>
 * </ul>
 * P2 之后新增 {@code CTL_}(Control)、{@code RUN_}(Runtime)、{@code GOV_}(Governance)。
 *
 * <p>httpStatus 以 int 保存而非 Spring 的 HttpStatus,是为了让 dg-common
 * 不产生对 spring-web 的依赖 —— 共享内核越瘦,越不容易变成杂物袋(风险 R2)。
 */
public enum ErrorCode {

    // ── 平台通用 ────────────────────────────────────────────────
    SYS_INTERNAL_ERROR("SYS_INTERNAL_ERROR", 500, "系统内部错误"),
    SYS_VALIDATION_FAILED("SYS_VALIDATION_FAILED", 400, "请求参数校验失败"),
    SYS_NOT_FOUND("SYS_NOT_FOUND", 404, "资源不存在"),
    SYS_CONFLICT("SYS_CONFLICT", 409, "资源状态冲突"),
    SYS_ILLEGAL_STATE_TRANSITION("SYS_ILLEGAL_STATE_TRANSITION", 409, "不允许的状态迁移"),

    // ── Platform / Tenancy ─────────────────────────────────────
    PLT_UNAUTHENTICATED("PLT_UNAUTHENTICATED", 401, "未认证或登录已过期"),
    PLT_FORBIDDEN("PLT_FORBIDDEN", 403, "无权限执行该操作"),
    PLT_BAD_CREDENTIALS("PLT_BAD_CREDENTIALS", 401, "用户名或密码错误"),
    PLT_USER_DISABLED("PLT_USER_DISABLED", 403, "用户已禁用"),
    PLT_WORKSPACE_NOT_FOUND("PLT_WORKSPACE_NOT_FOUND", 404, "空间不存在"),
    PLT_WORKSPACE_FORBIDDEN("PLT_WORKSPACE_FORBIDDEN", 403, "当前用户未被授权访问该空间"),
    PLT_WORKSPACE_CODE_DUPLICATED("PLT_WORKSPACE_CODE_DUPLICATED", 409, "空间标识已存在"),
    // 403 而非 423(Locked):对调用方来说"这个空间现在不让你用"与其它 403 是同一类处置,
    // 而 423 会诱导前端写出一条只为这一种情况存在的分支。
    PLT_WORKSPACE_SUSPENDED("PLT_WORKSPACE_SUSPENDED", 403, "空间已停用"),
    PLT_USERNAME_DUPLICATED("PLT_USERNAME_DUPLICATED", 409, "用户名已存在"),
    PLT_ROLE_CODE_DUPLICATED("PLT_ROLE_CODE_DUPLICATED", 409, "角色标识已存在"),
    PLT_ROLE_IN_USE("PLT_ROLE_IN_USE", 409, "角色已被用户引用,无法删除"),
    PLT_CREDENTIAL_NOT_FOUND("PLT_CREDENTIAL_NOT_FOUND", 404, "凭据不存在"),
    PLT_MISSING_WORKSPACE("PLT_MISSING_WORKSPACE", 400, "请求未指定空间"),

    // ── Metadata ───────────────────────────────────────────────
    MTD_DATASOURCE_NOT_FOUND("MTD_DATASOURCE_NOT_FOUND", 404, "数据源不存在"),
    MTD_DATASOURCE_NAME_DUPLICATED("MTD_DATASOURCE_NAME_DUPLICATED", 409, "同空间下数据源名称已存在"),
    MTD_DATASOURCE_NOT_ACTIVE("MTD_DATASOURCE_NOT_ACTIVE", 409, "数据源未处于可用状态"),
    MTD_DATASOURCE_IN_USE("MTD_DATASOURCE_IN_USE", 409, "数据源已被引用,无法删除"),
    MTD_UNSUPPORTED_DATASOURCE_TYPE("MTD_UNSUPPORTED_DATASOURCE_TYPE", 400, "不支持的数据源类型"),
    MTD_CATALOG_NOT_FOUND("MTD_CATALOG_NOT_FOUND", 404, "目录快照不存在"),
    MTD_CONFIG_INVALID("MTD_CONFIG_INVALID", 400, "数据源连接配置不合法"),

    // ── Data(连接器)────────────────────────────────────────────
    DAT_CONNECTOR_NOT_FOUND("DAT_CONNECTOR_NOT_FOUND", 400, "没有匹配的连接器实现"),
    DAT_DRIVER_MISSING("DAT_DRIVER_MISSING", 503, "连接器驱动未安装"),
    DAT_CONNECT_FAILED("DAT_CONNECT_FAILED", 502, "连接目标数据源失败"),
    DAT_AUTH_FAILED("DAT_AUTH_FAILED", 502, "目标数据源认证失败"),
    DAT_TIMEOUT("DAT_TIMEOUT", 504, "连接目标数据源超时"),
    DAT_INTROSPECT_FAILED("DAT_INTROSPECT_FAILED", 502, "读取目标数据源结构失败"),
    /** 功能7:只允许查询类语句。以 400 而非 403 返回 —— 这是请求内容的问题,不是权限问题 */
    DAT_SQL_NOT_ALLOWED("DAT_SQL_NOT_ALLOWED", 400, "只允许执行查询类 SQL 语句"),
    DAT_QUERY_FAILED("DAT_QUERY_FAILED", 502, "执行查询失败"),
    DAT_UNSUPPORTED_OPERATION("DAT_UNSUPPORTED_OPERATION", 400, "该数据源类型不支持此操作");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
