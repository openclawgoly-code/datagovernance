package com.datagov.app.config;

import com.datagov.app.web.AuthInterceptor;
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

    public WebMvcConfig(AuthService authService,
                        WorkspaceService workspaceService,
                        PermissionService permissionService,
                        SecurityProperties properties) {
        this.authService = authService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(
                        authService, workspaceService, permissionService, properties))
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
