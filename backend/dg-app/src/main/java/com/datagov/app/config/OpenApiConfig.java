package com.datagov.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** 认证令牌 */
    private static final String BEARER = "bearerAuth";
    /** 当前空间(租户)—— 除登录与空间列表外,几乎所有接口都需要它 */
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";

    @Bean
    public OpenAPI dataGovernanceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("数据治理平台 API")
                        .version("0.1.0 (P1)")
                        .description("""
                                P1 阶段接口:租户身份、数据源定义与生命周期、连通性测试、库表结构浏览。

                                **认证**:除登录外全部接口需要 `Authorization: Bearer <token>`。

                                **空间隔离**:业务接口通过请求头 `X-Workspace-Id` 指定当前空间(租户)。
                                注意「空间」在本平台是租户概念,与架构文档中的 Space(边界)同名不同物。

                                **响应封装**:统一为 `ApiResponse`,失败时 `success=false` 且带 `traceId`,
                                排障时以 traceId 关联服务端日志。
                                """)
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes(WORKSPACE_HEADER, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(WORKSPACE_HEADER)))
                .addSecurityItem(new SecurityRequirement()
                        .addList(BEARER)
                        .addList(WORKSPACE_HEADER));
    }
}
