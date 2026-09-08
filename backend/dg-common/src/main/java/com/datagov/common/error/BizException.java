package com.datagov.common.error;

/**
 * 业务异常。所有 Space 抛出的可预期错误都应是它(或其子类),
 * 由 dg-app 的全局异常处理器统一翻译为 HTTP 响应。
 *
 * <p>约定: 不要用它承载"驱动加载失败"这类环境错误的堆栈给最终用户看,
 * 而应通过 {@link #detail} 保留技术细节、{@link #getMessage()} 面向用户。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public BizException(ErrorCode errorCode, String message, String detail) {
        this(errorCode, message, detail, null);
    }

    public BizException(ErrorCode errorCode, String message, String detail, Throwable cause) {
        super(message == null ? errorCode.defaultMessage() : message, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    // ── 便捷工厂 ────────────────────────────────────────────────

    public static BizException notFound(ErrorCode code, String what) {
        return new BizException(code, code.defaultMessage() + ": " + what);
    }

    public static BizException conflict(ErrorCode code, String what) {
        return new BizException(code, code.defaultMessage() + ": " + what);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.PLT_FORBIDDEN, message);
    }
}
