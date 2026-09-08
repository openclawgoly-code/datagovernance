package com.datagov.common.tenant;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

/**
 * 当前请求的调用者上下文持有者。
 *
 * <p>这是整个平台<b>唯一</b>的空间(租户)作用域来源。任何 Space 需要知道
 * "现在在哪个空间下工作",都必须从这里取,不得自行从请求参数解析 —— 否则
 * 每个模块都会发明一套租户模型,正是把 Platform/Tenancy 独立成 Space 要防止的事。
 *
 * <p>使用 {@link ThreadLocal};dg-app 的过滤器负责在请求进入时 {@link #set}、
 * 在 finally 中 {@link #clear}。异步执行(P2 起的调度线程)必须显式传递,
 * 不可依赖继承。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<Caller> HOLDER = new ThreadLocal<>();

    private WorkspaceContext() {
    }

    public static void set(Caller caller) {
        HOLDER.set(caller);
    }

    public static Caller get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前调用者,未认证则抛 401。 */
    public static Caller require() {
        Caller caller = HOLDER.get();
        if (caller == null) {
            throw new BizException(ErrorCode.PLT_UNAUTHENTICATED);
        }
        return caller;
    }

    /**
     * 取当前空间 ID。所有按空间隔离的仓储查询都应以它为过滤条件。
     *
     * @throws BizException 未认证(401)或请求未携带空间(400)
     */
    public static String requireWorkspaceId() {
        Caller caller = require();
        if (caller.workspaceId() == null || caller.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.PLT_MISSING_WORKSPACE);
        }
        return caller.workspaceId();
    }

    /** 在给定身份下执行一段逻辑,结束后恢复原上下文。用于测试与后台任务。 */
    public static <T> T callAs(Caller caller, java.util.function.Supplier<T> action) {
        Caller previous = HOLDER.get();
        HOLDER.set(caller);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
