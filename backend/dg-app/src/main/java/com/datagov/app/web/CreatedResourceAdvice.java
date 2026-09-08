package com.datagov.app.web;

import com.datagov.common.api.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Method;

/**
 * 把"刚创建的资源是哪一个"从响应体里捞出来,供审计使用。
 *
 * <p>解决的是一个具体的缺口:{@code POST /jobs} 的<b>路径里没有 ID</b>,
 * 所以 {@link AuditInterceptor} 只能记下"有人建了一个任务",记不下建的是哪个。
 * 而"上周谁建了这个任务"恰恰是审计最常被问到的问题之一。
 *
 * <p>为什么不在每个 Service 里手写一行审计:那正是 {@link AuditInterceptor}
 * 的类注释里说的失败模式 —— 新加接口时忘掉,而忘掉这件事没有任何征兆。
 *
 * <p>为什么不缓冲响应字节:{@code ResponseBodyAdvice} 在<b>序列化之前</b>拿到
 * 的是对象本身,取一个字段是一次反射调用;而缓冲字节要把每个响应都复制一遍,
 * 代价大得多,还要处理下载接口那种大响应。
 */
@RestControllerAdvice
public class CreatedResourceAdvice implements ResponseBodyAdvice<Object> {

    /** 审计拦截器从请求属性里读它 */
    public static final String ATTR_RESOURCE_ID = "dg.audit.resourceId";
    public static final String ATTR_RESOURCE_NAME = "dg.audit.resourceName";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }
        String method = servletRequest.getServletRequest().getMethod();
        // 只对写操作做这件事:读操作的响应里也有 id,但审计不记读操作
        if ("GET".equals(method) || "OPTIONS".equals(method)) {
            return body;
        }
        if (!(body instanceof ApiResponse<?> api) || api.data() == null) {
            return body;
        }

        Object data = api.data();
        String id = readString(data, "id");
        if (id != null) {
            servletRequest.getServletRequest().setAttribute(ATTR_RESOURCE_ID, id);
        }
        String name = readString(data, "name");
        if (name != null) {
            servletRequest.getServletRequest().setAttribute(ATTR_RESOURCE_NAME, name);
        }
        return body;
    }

    /**
     * 读一个字段。
     *
     * <p>record 的访问器是无参方法且与字段同名,普通 DTO 用 getXxx。两种都试,
     * 失败就当没有 —— 这个功能<b>不允许因为反射失败而影响响应</b>:审计是旁路,
     * 它坏了不该让业务请求也跟着坏。
     */
    private static String readString(Object target, String field) {
        for (String candidate : new String[]{field,
                "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1)}) {
            try {
                Method accessor = target.getClass().getMethod(candidate);
                Object value = accessor.invoke(target);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 没有这个访问器,或它不可访问 —— 试下一个候选
            }
        }
        return null;
    }
}
