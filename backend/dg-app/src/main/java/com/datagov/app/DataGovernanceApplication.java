package com.datagov.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据治理平台启动入口。
 *
 * <p>扫描范围显式列出各 Space 的包,而不是靠 {@code com.datagov} 一网打尽 ——
 * 这样新增一个 Space 必须在这里登记,装配关系始终是可见的。
 */
@SpringBootApplication
// 周期连通性检查(功能6)与 Control 的调度触发都要它。定时任务的开关与频率
// 归装配层:拆进程之后调度器可能只在其中一个副本上跑,那是部署决策。
@EnableScheduling
@ComponentScan(basePackages = {
        "com.datagov.app",
        "com.datagov.platform",
        "com.datagov.metadata",
        "com.datagov.runtime",
        "com.datagov.control",
        "com.datagov.data.connector"
})
public class DataGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataGovernanceApplication.class, args);
    }
}
