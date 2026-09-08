package com.datagov.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 数据治理平台启动入口。
 *
 * <p>扫描范围显式列出各 Space 的包,而不是靠 {@code com.datagov} 一网打尽 ——
 * 这样新增一个 Space 必须在这里登记,装配关系始终是可见的。
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.datagov.app",
        "com.datagov.platform",
        "com.datagov.metadata",
        "com.datagov.data.connector"
})
public class DataGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataGovernanceApplication.class, args);
    }
}
