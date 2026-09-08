package com.datagov.app.web;

import com.datagov.common.api.ApiResponse;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常翻译。
 *
 * <p>这是 HTTP 状态码<b>唯一</b>被决定的地方 —— 各 Space 的服务只抛
 * {@link BizException} 并携带 {@link ErrorCode},不关心自己会变成 404 还是 409。
 * 这样业务模块不必依赖 spring-web,Space 边界也不会被传输层细节污染。
 *
 * <p>每个错误响应都带 traceId,并在服务端日志中以同一个 traceId 记录完整堆栈。
 * 用户截图报错时报一个 traceId,就能定位到具体那一次调用 —— 这比让用户
 * 复述错误信息可靠得多。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        ErrorCode code = ex.errorCode();

        // 4xx 是调用方的问题,记 WARN 且不打堆栈 —— 否则正常的"名称重复"
        // 会把日志淹没,真正的 5xx 反而找不到。
        if (code.httpStatus() >= 500) {
            log.error("[{}] {} {} -> {}: {} | detail={}", traceId, request.getMethod(),
                    request.getRequestURI(), code.code(), ex.getMessage(), ex.detail(), ex);
        } else {
            log.warn("[{}] {} {} -> {}: {} | detail={}", traceId, request.getMethod(),
                    request.getRequestURI(), code.code(), ex.getMessage(), ex.detail());
        }

        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code.code(), ex.getMessage(), traceId));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException ex,
                                                              HttpServletRequest request) {
        String traceId = newTraceId();
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describeFieldError)
                .collect(Collectors.joining("; "));

        log.warn("[{}] {} {} -> 参数校验失败: {}", traceId, request.getMethod(),
                request.getRequestURI(), message);

        ErrorCode code = ErrorCode.SYS_VALIDATION_FAILED;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code.code(), message, traceId));
    }

    /**
     * 路径不存在。
     *
     * <p>没有这一条,任何一个拼错的 URL 都会掉进下面的兜底里,变成一个 500 加
     * 一行「未预期异常」的错误日志。后果不只是响应码不对:真正的内部错误会被
     * 淹没在一堆拼写错误里,而那正是错误日志唯一的用途。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex,
                                                            HttpServletRequest request) {
        log.debug("路径不存在 {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(404)
                .body(ApiResponse.fail(ErrorCode.SYS_NOT_FOUND.code(),
                        "接口不存在: " + request.getRequestURI(), null));
    }

    /**
     * 方法不被支持 —— 路径对但动词错。
     *
     * <p>与 404 分开:审计日志只有 GET 而没有 DELETE,收到 405 的调用方能立刻
     * 知道"这个资源在,但它不允许删",而 404 会让人以为路径写错了。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.debug("方法不支持 {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(405)
                .body(ApiResponse.fail(ErrorCode.SYS_VALIDATION_FAILED.code(),
                        "%s 不支持 %s,允许:%s".formatted(request.getRequestURI(),
                                request.getMethod(), ex.getSupportedHttpMethods()),
                        null));
    }

    /**
     * 兜底。
     *
     * <p>注意这里<b>不</b>把 {@code ex.getMessage()} 回传给用户 —— 未预期异常的
     * 消息里可能含有连接串、SQL 片段甚至凭据。用户拿到 traceId,细节留在服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex,
                                                              HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] {} {} -> 未预期异常", traceId, request.getMethod(),
                request.getRequestURI(), ex);

        ErrorCode code = ErrorCode.SYS_INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code.code(),
                        code.defaultMessage() + ",请将 traceId 提供给管理员", traceId));
    }

    private static String describeFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
