package com.datagov.data.spi;

import com.datagov.common.error.ErrorCode;

/**
 * 连通性测试结果(功能 1-4 的「测试连接」按钮背后的返回值)。
 *
 * <p>失败时<b>必须</b>给出可行动的信息: 是网络不通、认证失败,还是驱动缺失?
 * 三者对应完全不同的处理动作(找网管 / 改密码 / 装驱动),
 * 只回一句"连接失败"会把排障成本转嫁给用户。
 *
 * @param success       是否连通
 * @param latencyMillis 握手往返耗时,失败时为实际耗时(便于区分"拒绝"与"超时")
 * @param serverVersion 目标端版本号,成功时尽力获取;取不到为 null
 * @param message       面向用户的可读结论
 * @param errorCode     失败归类;成功时为 null
 * @param detail        原始异常信息,仅在详情区展示,不做用户提示
 */
public record ConnectivityResult(
        boolean success,
        long latencyMillis,
        String serverVersion,
        String message,
        ErrorCode errorCode,
        String detail
) {

    public static ConnectivityResult success(long latencyMillis, String serverVersion) {
        return new ConnectivityResult(true, latencyMillis, serverVersion,
                "连接成功", null, null);
    }

    public static ConnectivityResult failure(ErrorCode errorCode, long latencyMillis,
                                             String message, String detail) {
        return new ConnectivityResult(false, latencyMillis, null,
                message == null ? errorCode.defaultMessage() : message, errorCode, detail);
    }
}
