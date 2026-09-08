package com.datagov.app.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明某个接口需要的权限码。
 *
 * <p>权限码取值来自 {@code V2__seed_permissions_and_roles.sql} —— 与前端
 * {@code v-permission} 指令用的是同一套码。同一个事实只在数据库里定义一次,
 * 前后端各自引用,不存在两份需要同步的清单。
 *
 * <p>不加这个注解的接口<b>仍然需要登录</b>(见 {@code AuthInterceptor}),
 * 只是不做细粒度权限判断。完全公开的接口走配置里的 {@code dg.security.permit-paths}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限码,例如 {@code metadata:datasource:create}。 */
    String value();
}
