package com.datagov.app.config;

import com.datagov.app.web.AuditInterceptor;
import com.datagov.app.web.AuthInterceptor;
import com.datagov.governance.service.AuditService;
import com.datagov.platform.config.SecurityProperties;
import com.datagov.platform.service.AuthService;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final SecurityProperties properties;
    private final AuditService auditService;

    public WebMvcConfig(AuthService authService,
                        WorkspaceService workspaceService,
                        PermissionService permissionService,
                        SecurityProperties properties,
                        AuditService auditService) {
        this.authService = authService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(
                        authService, workspaceService, permissionService, properties))
                .addPathPatterns("/api/**");
        // 审计拦截器排在鉴权之后:它的 afterCompletion 会先于 AuthInterceptor 的
        // 执行(Spring 逆序回调),因此还能读到 WorkspaceContext 里的调用者。
        // 顺序反过来的话,每条审计记录的用户都是空的。
        registry.addInterceptor(new AuditInterceptor(auditService))
                .addPathPatterns("/api/**");
    }

    /**
     * 开发期跨域。
     *
     * <p>生产部署应由前置网关统一处理,前后端同源时这段配置不生效也无妨。
     * 这里不开 {@code allowCredentials} —— 令牌走 Authorization 头而非 Cookie,
     * 不需要携带凭据的跨域请求。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
