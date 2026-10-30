package com.datagov.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端 SPA 入口回退。
 *
 * <p>后端与前端同源部署时,Vue Router 的 history 模式需要把非 API 路径
 * 回退到 index.html;否则刷新 /insight/ask 会落到后端 404。
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/",
            "/login",
            "/workbench",
            "/workbench/**",
            "/insight/**",
            "/metadata/**",
            "/integration/**",
            "/dev/**",
            "/ops/**",
            "/settings/**",
            "/403"
    })
    public String index() {
        return "forward:/index.html";
    }
}
