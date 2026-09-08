package com.datagov.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 全平台统一响应封装。
 *
 * <p>刻意不携带 HTTP 状态码语义 —— 传输层状态由 dg-app 的异常处理器决定,
 * 这里只表达业务结果。dg-common 因此不需要依赖 spring-web。
 *
 * @param success 业务是否成功
 * @param code    业务码,成功恒为 "OK",失败为 {@link com.datagov.common.error.ErrorCode#code()}
 * @param message 面向用户的可读信息
 * @param data    业务负载
 * @param traceId 链路追踪 ID,用于把一次前端报错关联到后端日志与(P4 之后的)审计记录
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {

    public static final String OK = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, OK, null, data, null, Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, OK, null, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> fail(String code, String message, String traceId) {
        return new ApiResponse<>(false, code, message, null, traceId, Instant.now());
    }
}
