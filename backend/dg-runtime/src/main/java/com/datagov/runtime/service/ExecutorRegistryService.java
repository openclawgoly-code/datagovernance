package com.datagov.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.domain.ExecutorStatus;
import com.datagov.runtime.entity.RuntimeEntities.Executor;
import com.datagov.runtime.mapper.ExecutorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 执行器注册表(序号 31「执行器管理」)。
 *
 * <p><b>菜单在「基础配置」下,归属却是 Runtime</b>(架构风险 R2)。这不是分类
 * 上的挑剔:执行器不是配置项,它是<b>有状态的资源</b> —— 有心跳、有并发额度、
 * 有正在跑的任务。把它当配置项管理,就会有人在界面上"改一下"然后发现三十个
 * 任务失败了。
 *
 * <p>{@code DRAINING} 是这里最重要的状态:下线一个执行器必须等它手上的任务
 * 跑完。直接摘掉等于把正在跑的任务连同它们的执行记录一起丢掉,而那些记录是
 * 序号 24 监控的事实来源。
 */
@Service
public class ExecutorRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistryService.class);

    /**
     * 心跳超时。
     *
     * <p>超过它没心跳就判 UNHEALTHY。取 90 秒是因为常见的心跳间隔是 30 秒 ——
     * 容忍连续丢两次,第三次才判死。只丢一次就判死会让一次网络抖动引发
     * 大面积的任务重调度。
     */
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);

    private final ExecutorMapper executorMapper;

    public ExecutorRegistryService(ExecutorMapper executorMapper) {
        this.executorMapper = executorMapper;
    }

    /** 执行器视图。 */
    public record ExecutorView(
            String id,
            String name,
            String kind,
            ExecutorStatus status,
            String statusDisplayName,
            String workspaceId,
            /** null 的 workspaceId 表示平台共享 */
            boolean shared,
            String endpoint,
            Integer maxConcurrency,
            Integer runningCount,
            /** 还能接几个任务。DRAINING / UNHEALTHY 时恒为 0 */
            int availableSlots,
            Instant lastHeartbeatAt,
            /** 心跳是否已经超时 —— 状态字段可能还没被巡检更新 */
            boolean heartbeatStale,
            Instant createdAt,
            Instant updatedAt
    ) {

        static ExecutorView from(Executor e, Instant now) {
            int max = e.getMaxConcurrency() == null ? 0 : e.getMaxConcurrency();
            int running = e.getRunningCount() == null ? 0 : e.getRunningCount();
            ExecutorStatus status = e.getStatus();
            return new ExecutorView(e.getId(), e.getName(), e.getKind(), status,
                    status == null ? null : status.displayName(),
                    e.getWorkspaceId(), e.getWorkspaceId() == null, e.getEndpoint(),
                    max, running,
                    status != null && status.acceptsWork() ? Math.max(0, max - running) : 0,
                    e.getLastHeartbeatAt(), isStale(e.getLastHeartbeatAt(), now),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /**
     * 列出可见的执行器。
     *
     * <p>平台共享的 + 本空间专属的。看不见别的空间的专属执行器 —— 那会泄露
     * 其他租户的部署规模。
     */
    public List<ExecutorView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Instant now = Instant.now();
        return executorMapper.selectList(new LambdaQueryWrapper<Executor>()
                        .ne(Executor::getStatus, ExecutorStatus.REMOVED)
                        .and(w -> w.eq(Executor::getWorkspaceId, workspaceId)
                                .or().isNull(Executor::getWorkspaceId))
                        .orderByAsc(Executor::getName)).stream()
                .map(e -> ExecutorView.from(e, now))
                .toList();
    }

    public ExecutorView get(String id) {
        return ExecutorView.from(requireVisible(id), Instant.now());
    }

    @Transactional
    public ExecutorView register(String name, String kind, String endpoint,
                                 Integer maxConcurrency, boolean shared) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "执行器名称不能为空");
        }
        if (shared && !WorkspaceContext.require().platformAdmin()) {
            throw BizException.forbidden("只有平台管理员能注册共享执行器");
        }
        Long dup = executorMapper.selectCount(new LambdaQueryWrapper<Executor>()
                .eq(Executor::getName, name.trim()));
        if (dup != null && dup > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "执行器名称已存在: " + name);
        }

        Instant now = Instant.now();
        Executor executor = new Executor();
        executor.setId(Ids.of("exec_r"));
        executor.setName(name.trim());
        executor.setKind(kind == null || kind.isBlank() ? "LOCAL" : kind.trim().toUpperCase());
        // 注册完是 REGISTERED 而不是 HEALTHY:还没收到过一次心跳,凭什么说它健康
        executor.setStatus(ExecutorStatus.REGISTERED);
        executor.setWorkspaceId(shared ? null : workspaceId);
        executor.setEndpoint(endpoint);
        executor.setMaxConcurrency(maxConcurrency == null || maxConcurrency < 1
                ? 4 : maxConcurrency);
        executor.setRunningCount(0);
        executor.setCreatedAt(now);
        executor.setUpdatedAt(now);
        executorMapper.insert(executor);

        log.info("执行器已注册 id={} name={} kind={} shared={}",
                executor.getId(), executor.getName(), executor.getKind(), shared);
        return ExecutorView.from(executor, now);
    }

    /** 心跳。第一次心跳把 REGISTERED 转成 HEALTHY。 */
    @Transactional
    public ExecutorView heartbeat(String id, Integer runningCount) {
        Executor executor = requireVisible(id);
        Instant now = Instant.now();
        executor.setLastHeartbeatAt(now);
        if (runningCount != null && runningCount >= 0) {
            executor.setRunningCount(runningCount);
        }
        // 排空中的执行器收到心跳不该被拉回 HEALTHY —— 那会让它重新开始接活,
        // 而管理员正等着它空下来
        if (executor.getStatus() == ExecutorStatus.REGISTERED
                || executor.getStatus() == ExecutorStatus.UNHEALTHY) {
            executor.setStatus(ExecutorStatus.HEALTHY);
        }
        executor.setUpdatedAt(now);
        executorMapper.updateById(executor);
        return ExecutorView.from(executor, now);
    }

    /**
     * 排空:不再派新活,等手上的跑完。
     *
     * <p>这是下线执行器的<b>唯一</b>正确入口。没有"直接移除"的快捷方式 ——
     * 那个按钮存在的话,迟早有人在有任务在跑的时候点它。
     */
    @Transactional
    public ExecutorView drain(String id) {
        Executor executor = requireVisible(id);
        if (executor.getStatus() == ExecutorStatus.REMOVED) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION, "执行器已移除");
        }
        executor.setStatus(ExecutorStatus.DRAINING);
        executor.setUpdatedAt(Instant.now());
        executorMapper.updateById(executor);
        log.info("执行器进入排空 id={} 手上还有 {} 个任务", id, executor.getRunningCount());
        return ExecutorView.from(executor, Instant.now());
    }

    /** 排空后移除。手上还有任务时拒绝。 */
    @Transactional
    public ExecutorView remove(String id) {
        Executor executor = requireVisible(id);
        int running = executor.getRunningCount() == null ? 0 : executor.getRunningCount();
        if (executor.getStatus() != ExecutorStatus.DRAINING
                && executor.getStatus() != ExecutorStatus.UNHEALTHY) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "执行器当前为「%s」,请先排空".formatted(executor.getStatus().displayName()),
                    "排空会让它不再接新任务,等手上的跑完再移除");
        }
        if (running > 0) {
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "执行器上还有 %d 个任务在跑".formatted(running),
                    "等它们结束后再移除 —— 现在移除会把这些执行记录一起丢掉");
        }
        // 保留记录而不是删行:历史执行记录里还指着这个执行器
        executor.setStatus(ExecutorStatus.REMOVED);
        executor.setUpdatedAt(Instant.now());
        executorMapper.updateById(executor);
        log.info("执行器已移除 id={}", id);
        return ExecutorView.from(executor, Instant.now());
    }

    /** 恢复一个排空中的执行器 —— 管理员改主意了。 */
    @Transactional
    public ExecutorView resume(String id) {
        Executor executor = requireVisible(id);
        if (executor.getStatus() != ExecutorStatus.DRAINING) {
            throw new BizException(ErrorCode.SYS_ILLEGAL_STATE_TRANSITION,
                    "只有排空中的执行器可以恢复,当前为「%s」"
                            .formatted(executor.getStatus().displayName()));
        }
        // 回到 REGISTERED 而不是 HEALTHY:等下一次心跳再确认它确实还活着
        executor.setStatus(ExecutorStatus.REGISTERED);
        executor.setUpdatedAt(Instant.now());
        executorMapper.updateById(executor);
        return ExecutorView.from(executor, Instant.now());
    }

    /**
     * 心跳巡检:把失联的标记为 UNHEALTHY。
     *
     * <p>没有它,一台断电的执行器会永远显示"健康",而调度器会一直往它身上派活。
     *
     * @return 本轮标记的数量
     */
    @Transactional
    public int sweepHeartbeats(Instant now) {
        List<Executor> healthy = executorMapper.selectList(new LambdaQueryWrapper<Executor>()
                .in(Executor::getStatus, ExecutorStatus.HEALTHY, ExecutorStatus.REGISTERED));
        int marked = 0;
        for (Executor executor : healthy) {
            if (!isStale(executor.getLastHeartbeatAt(), now)) {
                continue;
            }
            // 从没上报过心跳的 LOCAL 执行器不算失联:内置执行器与应用同生共死,
            // 它不需要向自己汇报
            if (executor.getLastHeartbeatAt() == null && "LOCAL".equals(executor.getKind())) {
                continue;
            }
            executor.setStatus(ExecutorStatus.UNHEALTHY);
            executor.setUpdatedAt(now);
            executorMapper.updateById(executor);
            marked++;
            log.warn("执行器心跳超时,标记为不健康 id={} name={} 最后心跳={}",
                    executor.getId(), executor.getName(), executor.getLastHeartbeatAt());
        }
        return marked;
    }

    private static boolean isStale(Instant lastHeartbeat, Instant now) {
        return lastHeartbeat != null && lastHeartbeat.plus(HEARTBEAT_TIMEOUT).isBefore(now);
    }

    private Executor requireVisible(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        List<Executor> found = executorMapper.selectList(new LambdaQueryWrapper<Executor>()
                .eq(Executor::getId, id)
                .and(w -> w.eq(Executor::getWorkspaceId, workspaceId)
                        .or().isNull(Executor::getWorkspaceId)));
        if (found.isEmpty()) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "执行器 " + id);
        }
        return found.get(0);
    }
}
