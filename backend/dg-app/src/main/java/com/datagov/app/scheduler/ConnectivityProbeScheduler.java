package com.datagov.app.scheduler;

import com.datagov.data.spi.ConnectivityResult;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.service.DataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 周期连通性检查的触发器(功能 6 的后半句「支持开启或关闭周期连通性检查」)。
 *
 * <p><b>这个类将来要搬走。</b> 按 {@code docs/function-space-matrix.md} 的划分,
 * 功能 6 横跨三个 Space:开关是<b>定义</b>(Metadata)、触发是<b>调度</b>(Control)、
 * 探测本身是<b>执行</b>(Data)、失败告警是 Governance。P1 还没有 Control Space,
 * 所以触发暂时寄居在装配层 —— 装配层是唯一有资格临时承担"还没有归宿的职责"的地方,
 * 因为它本来就依赖所有 Space。
 *
 * <p>P2 建立 Control 之后,这里的逻辑整体迁入,{@code DataSourceService.probe}
 * 与 {@code findDueForProbe} 的签名不需要改 —— 那两个方法表达的是领域行为,
 * 与谁来触发无关。
 *
 * <p><b>为什么周期检查这件事本身重要</b>:没有它,状态机里
 * {@code AVAILABLE ──ProbeFailed──> UNREACHABLE} 这条边永远走不到,
 * UNREACHABLE 就成了一个写了却进不去的状态 —— 状态机有一条边是死的。
 */
@Component
@ConfigurationProperties(prefix = "dg.probe")
public class ConnectivityProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityProbeScheduler.class);

    private final DataSourceService dataSourceService;

    /** 总开关。单机部署无所谓,多实例部署时应只让一个实例开着,或改用分布式锁。 */
    private boolean enabled = true;

    /**
     * 每轮最多探测多少个数据源。
     *
     * <p>限流的意义不在保护平台,而在保护<b>目标端</b>:上百个数据源同时被探测,
     * 对某些共用一台数据库服务器的场景就是一次小型压测。宁可探测得慢一点。
     */
    private int batchSize = 20;

    public ConnectivityProbeScheduler(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 每分钟看一次有没有到期的。
     *
     * <p>扫描频率与探测间隔是两回事:这里每分钟醒一次只是为了有足够的时间分辨率,
     * 真正是否探测由每个数据源自己的 {@code probeIntervalMinutes} 决定
     * (下限 5 分钟)。
     */
    @Scheduled(fixedDelayString = "${dg.probe.scan-interval-ms:60000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        List<DataSourceEntity> due;
        try {
            due = dataSourceService.findDueForProbe(Instant.now(), batchSize);
        } catch (RuntimeException e) {
            // 调度线程里任何未捕获异常都会让后续调度停摆,必须兜住
            log.error("扫描待探测数据源失败,本轮跳过", e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }

        log.debug("本轮待探测数据源 {} 个", due.size());
        for (DataSourceEntity entity : due) {
            probeOne(entity);
        }
    }

    /**
     * 单个数据源的探测。
     *
     * <p>逐个 try/catch:一个数据源探测失败(比如凭据被删了导致解密报错)
     * 不该让同一轮里其余数据源都探测不到。
     */
    private void probeOne(DataSourceEntity entity) {
        try {
            ConnectivityResult result = dataSourceService.probe(entity);
            if (!result.success()) {
                log.info("周期探测失败 workspace={} id={} name={} 原因={}",
                        entity.getWorkspaceId(), entity.getId(), entity.getName(), result.message());
            }
        } catch (RuntimeException e) {
            log.warn("周期探测出现异常 workspace={} id={} name={}",
                    entity.getWorkspaceId(), entity.getId(), entity.getName(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
