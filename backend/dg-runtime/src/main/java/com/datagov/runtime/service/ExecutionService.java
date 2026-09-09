package com.datagov.runtime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.domain.ExecutionLifecycle;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.dto.DispatchCommand;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.entity.RuntimeEntities.Execution;
import com.datagov.runtime.entity.RuntimeEntities.ExecutionAttempt;
import com.datagov.runtime.event.ExecutionEvents;
import com.datagov.runtime.mapper.ExecutionAttemptMapper;
import com.datagov.runtime.mapper.ExecutionMapper;
import com.datagov.runtime.spi.ExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;
import java.util.Map;

/**
 * 执行网关 —— Runtime Space 的核心。
 *
 * <p>它是 {@code rt_execution} 这张<b>全平台唯一执行事实表</b>的唯一写入方
 * (架构约束 R4)。所有状态迁移都经过 {@link ExecutionLifecycle} 校验,
 * 不存在"某处代码直接 setStatus"的旁路。
 *
 * <p><b>它不认识任何业务。</b> 整库迁移和离线同步在这里没有区别,都是一个
 * {@link DispatchCommand} 加一个 {@link JobRefType}。这不是抽象洁癖:一旦这里
 * 出现 {@code if (jobRefType == MIGRATION)},每加一种作业就要改执行网关,
 * 而执行网关是全平台最不该频繁改动的地方。
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /** 错误详情的存储上限。引擎堆栈能有几十 KB,整条存进去会把事实表撑爆。 */
    private static final int MAX_DETAIL_LENGTH = 8192;
    private static final int MAX_MESSAGE_LENGTH = 1024;

    private final ExecutionMapper executionMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final EngineRegistry engineRegistry;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    /**
     * 自身的代理引用。
     *
     * <p>需要它是因为提交后的两个回写方法(记录引擎作业 ID、提交失败)跑在
     * <b>事务提交之后</b>的回调里,而 Spring 的 {@code @Transactional} 靠代理生效 ——
     * 直接 {@code this.xxx()} 会绕过代理,那两个方法就成了无事务执行。
     * {@code @Lazy} 打断构造期的自我依赖。
     */
    private final ExecutionService self;

    public ExecutionService(ExecutionMapper executionMapper,
                            ExecutionAttemptMapper attemptMapper,
                            EngineRegistry engineRegistry,
                            ApplicationEventPublisher events,
                            ObjectMapper objectMapper,
                            @Lazy ExecutionService self) {
        this.executionMapper = executionMapper;
        this.attemptMapper = attemptMapper;
        this.engineRegistry = engineRegistry;
        this.events = events;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    // ── 下发 ────────────────────────────────────────────────────────────

    /**
     * DispatchExecution —— Control 唯一的入口。
     *
     * <p>落库与提交<b>分两步</b>:先把 PENDING 的执行事实写下来,再提交给引擎。
     * 顺序反过来的话,引擎已经在跑而事实表里还没有这一行,这段窗口内发生的崩溃
     * 会留下一个没人知道的孤儿作业。
     */
    @Transactional
    public ExecutionView dispatch(DispatchCommand command) {
        Instant now = Instant.now();

        Execution execution = new Execution();
        execution.setId(Ids.of("exec"));
        execution.setWorkspaceId(command.workspaceId());
        execution.setJobRefType(command.jobRefType());
        execution.setJobRefId(command.jobRefId());
        execution.setJobName(command.jobName());
        execution.setDefVersion(command.defVersion());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setParentExecutionId(command.parentExecutionId());
        execution.setTriggerType(command.triggerType());
        execution.setTriggeredBy(command.triggeredBy());
        execution.setPlanJson(writeJson(command.plan(), "物理计划"));
        execution.setRetryPolicyJson(writeJson(command.retryPolicy(), "重试策略"));
        execution.setTimeoutMs(command.timeoutMs());
        execution.setAttemptCount(0);
        execution.setSubmittedAt(now);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        executionMapper.insert(execution);

        events.publishEvent(new ExecutionEvents.ExecutionAccepted(
                execution.getWorkspaceId(), execution.getId(), execution.getJobRefType(),
                execution.getJobRefId(), execution.getJobName(), now));

        startAttempt(execution, command);
        return ExecutionView.from(execution);
    }

    /**
     * 开一次新尝试并提交给引擎。
     *
     * <p>首次执行与重试走同一条路径 —— 重试不是特殊情况,它就是"再来一次尝试"。
     * 两条路径分开写必然会漂移(常见的是重试忘了带超时或忘了写 attempt 行)。
     */
    private void startAttempt(Execution execution, DispatchCommand command) {
        int attemptNo = execution.getAttemptCount() + 1;
        Instant now = Instant.now();

        ExecutionEngine engine = engineRegistry.select(execution.getJobRefType());
        if (engine == null) {
            reject(execution, "没有支持 %s 的执行引擎".formatted(execution.getJobRefType()));
            return;
        }

        ExecutionAttempt attempt = new ExecutionAttempt();
        attempt.setId(Ids.of("att"));
        attempt.setExecutionId(execution.getId());
        attempt.setWorkspaceId(execution.getWorkspaceId());
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(ExecutionStatus.DISPATCHED);
        attempt.setExecutorId(engine.engineKind());
        attempt.setCreatedAt(now);
        attemptMapper.insert(attempt);

        transitionTo(execution, ExecutionStatus.DISPATCHED, "DispatchExecution");
        execution.setAttemptCount(attemptNo);
        executionMapper.updateById(execution);

        ExecutionEngine.EngineTask task = new ExecutionEngine.EngineTask(
                execution.getId(), attempt.getId(), execution.getWorkspaceId(),
                execution.getJobRefType(), command.plan(), command.timeoutMs());

        // ────────────────────────────────────────────────────────────────
        // 提交必须发生在<b>事务提交之后</b>。
        //
        // 引擎是异步的:submit 立刻返回,执行线程随即回调 onStarted。若在事务里
        // 提交,那个回调会在另一个线程、另一个事务里查这条 attempt —— 而它此刻
        // 还没提交,查不到,于是每一次执行都以「执行记录不存在」失败。
        //
        // 这不是可以靠"注意一点"避免的时序问题:两个线程的可见性由事务边界决定,
        // 只能靠把提交挪到边界之外来解决。
        // ────────────────────────────────────────────────────────────────
        submitAfterCommit(engine, task, attempt.getId(), execution.getId());
    }

    private void submitAfterCommit(ExecutionEngine engine, ExecutionEngine.EngineTask task,
                                   String attemptId, String executionId) {
        Runnable submit = () -> {
            try {
                String engineJobId = engine.submit(task, engineRegistry.callback());
                if (engineJobId != null) {
                    self.recordEngineJobId(attemptId, engineJobId);
                }
            } catch (RejectedExecutionException e) {
                // 执行器满了 —— 这是<b>容量问题,不是平台故障</b>,必须和"引擎坏了"
                // 分开记。混在一起的代价有两个,都不小:
                //
                //   值班的人看到 SYS_INTERNAL_ERROR 会去找开发查 bug,而真相是
                //   "队列满了,加机器或调大池子";
                //
                //   更要紧的是告警。TriggerType.DISPATCH_REJECTED(「下发被拒」)
                //   是用户可配的告警规则类型 —— 而线程池打满正是它在真实环境里
                //   最主要的成因。不走 reject() 就不发 DispatchRejected 事件,
                //   于是用户配了"执行器满了就告警",过载真发生时一条都收不到。
                //
                // RejectedExecutionException 的 message 是 FutureTask 的 toString,
                // 对人没有意义,所以这里自己给一句说得清的。
                log.warn("执行器已满,下发被拒 execution={} engine={}",
                        executionId, engine.engineKind());
                self.rejectSubmission(attemptId,
                        "执行器已满,下发被拒。当前并发已达上限,请稍后重试或调整 "
                                + "dg.runtime 的池子与队列容量");
            } catch (RuntimeException e) {
                // 引擎自己坏了(SPI 约定业务失败走回调,不抛异常)。对用户而言
                // 结果一样是"没跑成",所以照样落终态,而不是留在 DISPATCHED
                // 等一个永远不会来的回调。
                log.error("提交执行失败 execution={} engine={}", executionId, engine.engineKind(), e);
                self.failSubmission(attemptId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        // 事务回滚了,执行记录根本不存在,不该有东西被提交出去
                        log.warn("事务未提交,取消下发 execution={}", executionId);
                    }
                }
            });
        } else {
            // 没有事务时(测试、或从非事务上下文调用)直接提交
            submit.run();
        }
    }

    /** 引擎作业 ID 单独一个事务写回 —— 它发生在外层事务提交之后。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEngineJobId(String attemptId, String engineJobId) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || attempt.getStatus().isTerminal()) {
            return;     // 已经跑完了,写回一个作业 ID 没有意义
        }
        // 只写这一列。整行写回会把读出来之后、写回之前发生的状态迁移抹掉 ——
        // 这个方法与引擎线程的 onStarted 是并发的,那个窗口真实存在
        attemptMapper.update(null, new LambdaUpdateWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getId, attemptId)
                .set(ExecutionAttempt::getEngineJobId, engineJobId));
    }

    /** 提交阶段失败 —— 同样在外层事务之后,需要自己的事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failSubmission(String attemptId, RuntimeException cause) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || attempt.getStatus().isTerminal()) {
            return;
        }
        Execution execution = requireExecutionInternal(attempt.getExecutionId());
        finishAttempt(attempt, ExecutionStatus.FAILED, "提交执行失败: " + cause.getMessage(),
                ErrorCode.SYS_INTERNAL_ERROR.code(), stackSummary(cause), null);
        if (!execution.getStatus().isTerminal()) {
            finishExecution(execution, ExecutionStatus.FAILED, "ExecutionFailed", attempt);
        }
    }

    /**
     * 下发被执行器拒绝 —— 与 {@link #failSubmission} 的区别只在于<b>归因</b>:
     * 一个是容量不够(环境),一个是引擎坏了(平台)。两者都落 FAILED 终态,
     * 但错误码与事件不同,而下游的告警规则正是按事件区分的。
     *
     * <p>和 failSubmission 一样在外层事务之后执行,需要自己的事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectSubmission(String attemptId, String reason) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || attempt.getStatus().isTerminal()) {
            return;
        }
        Execution execution = requireExecutionInternal(attempt.getExecutionId());
        finishAttempt(attempt, ExecutionStatus.FAILED, reason,
                ErrorCode.RTM_DISPATCH_REJECTED.code(), null, null);
        if (!execution.getStatus().isTerminal()) {
            reject(execution, reason);
        }
    }

    private void reject(Execution execution, String reason) {
        transitionTo(execution, ExecutionStatus.FAILED, "DispatchRejected");
        execution.setMessage(truncate(reason, MAX_MESSAGE_LENGTH));
        execution.setErrorCode(ErrorCode.RTM_DISPATCH_REJECTED.code());
        execution.setFinishedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        executionMapper.updateById(execution);

        log.warn("下发被拒 execution={} 原因={}", execution.getId(), reason);
        events.publishEvent(new ExecutionEvents.DispatchRejected(
                execution.getWorkspaceId(), execution.getId(), reason, Instant.now()));
        publishFinished(execution);
    }

    // ── 引擎回调(由 EngineRegistry 转交)────────────────────────────────

    @Transactional
    public void onStarted(String attemptId, String engineJobId) {
        ExecutionAttempt attempt = requireAttempt(attemptId);
        Execution execution = requireExecutionInternal(attempt.getExecutionId());

        Instant now = Instant.now();
        attempt.setStatus(ExecutionStatus.RUNNING);
        attempt.setStartedAt(now);
        if (engineJobId != null) {
            attempt.setEngineJobId(engineJobId);
        }
        attemptMapper.updateById(attempt);

        // 取消已经受理时不要把状态拉回 RUNNING —— 那会让"正在取消"的提示消失,
        // 用户会以为自己的取消没生效而再点一次。
        if (execution.getStatus() == ExecutionStatus.DISPATCHED) {
            transitionTo(execution, ExecutionStatus.RUNNING, "ExecutionStarted");
            // 只有第一次尝试才算"开始时间";重试不该把首次开始时间抹掉,
            // 否则界面上的总耗时会随每次重试缩短
            if (execution.getStartedAt() == null) {
                execution.setStartedAt(now);
            }
            executionMapper.updateById(execution);
        }

        events.publishEvent(new ExecutionEvents.ExecutionStarted(
                execution.getWorkspaceId(), execution.getId(), attemptId,
                attempt.getAttemptNo(), now));
    }

    @Transactional
    public void onProgress(String attemptId, ExecutionEngine.EngineMetric metric) {
        ExecutionAttempt attempt = requireAttempt(attemptId);
        Execution execution = requireExecutionInternal(attempt.getExecutionId());

        // ────────────────────────────────────────────────────────────────
        // 只更新指标列,<b>绝不写 status</b>。
        //
        // 进度回调与取消是并发的:一个跑二十万行的同步任务每隔几批就回报一次
        // 进度。若这里用 updateById(整行覆盖),下面这个序列会把取消抹掉:
        //
        //   t0  进度回调读出 execution,此刻 status=RUNNING
        //   t1  用户点取消,status 落 CANCELING 并提交
        //   t2  进度回调 updateById 整行写回 —— status 变回 RUNNING
        //
        // 结果是任务继续跑到底,而界面上"取消中"闪一下就消失了。用户会以为
        // 自己没点上,再点一次,然后再被抹掉一次。
        //
        // 状态只能由状态机迁移改写,进度只该改进度。
        // ────────────────────────────────────────────────────────────────
        attemptMapper.update(null, new LambdaUpdateWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getId, attemptId)
                .set(ExecutionAttempt::getRowsRead, metric.rowsRead())
                .set(ExecutionAttempt::getRowsWritten, metric.rowsWritten())
                .set(ExecutionAttempt::getBytesProcessed, metric.bytesProcessed()));

        executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                .eq(Execution::getId, execution.getId())
                .set(Execution::getRowsRead, metric.rowsRead())
                .set(Execution::getRowsWritten, metric.rowsWritten())
                .set(Execution::getBytesProcessed, metric.bytesProcessed())
                .set(Execution::getUpdatedAt, Instant.now()));

        events.publishEvent(new ExecutionEvents.ExecutionProgressed(
                execution.getWorkspaceId(), execution.getId(), attemptId,
                metric.rowsRead(), metric.rowsWritten(), metric.bytesProcessed(), Instant.now()));
    }

    @Transactional
    public void onSucceeded(String attemptId, ExecutionEngine.EngineMetric metric) {
        ExecutionAttempt attempt = requireAttempt(attemptId);
        Execution execution = requireExecutionInternal(attempt.getExecutionId());

        finishAttempt(attempt, ExecutionStatus.SUCCEEDED, null, null, null, metric);
        finishExecution(execution, ExecutionStatus.SUCCEEDED, "ExecutionSucceeded", attempt);
    }

    @Transactional
    public void onFailed(String attemptId, String message, String errorCode, String detail) {
        ExecutionAttempt attempt = requireAttempt(attemptId);
        Execution execution = requireExecutionInternal(attempt.getExecutionId());

        finishAttempt(attempt, ExecutionStatus.FAILED, message, errorCode, detail, null);

        DispatchCommand.RetryPolicy policy = readRetryPolicy(execution);
        if (policy.allowsRetry(attempt.getAttemptNo())) {
            long backoff = policy.backoffMillisAfter(attempt.getAttemptNo());
            log.info("执行失败将重试 execution={} 第{}次尝试后等待{}ms",
                    execution.getId(), attempt.getAttemptNo(), backoff);
            events.publishEvent(new ExecutionEvents.ExecutionRetryScheduled(
                    execution.getWorkspaceId(), execution.getId(),
                    attempt.getAttemptNo() + 1, backoff, Instant.now()));
            // 实际的退避重投由 RetryScheduler 承担 —— 让一个事务方法 sleep 住
            // 会把数据库连接一起占着,退避越久占得越久。
            return;
        }
        finishExecution(execution, ExecutionStatus.FAILED, "ExecutionFailed", attempt);
    }

    @Transactional
    public void onCanceled(String attemptId) {
        ExecutionAttempt attempt = requireAttempt(attemptId);
        Execution execution = requireExecutionInternal(attempt.getExecutionId());

        finishAttempt(attempt, ExecutionStatus.CANCELED, "已取消", null, null, null);
        finishExecution(execution, ExecutionStatus.CANCELED, "ExecutionCanceled", attempt);
    }

    // ── 取消与超时 ──────────────────────────────────────────────────────

    /**
     * CancelExecution(序号 19/23 明确要求)。
     *
     * <p>取消是<b>两段式</b>的:先落 CANCELING,通知引擎,等引擎回报后才到 CANCELED。
     * 一步到位地写成 CANCELED 会产生一个谎言 —— 记录说已取消,而作业还在目标库上
     * 跑着,占着连接、写着数据。
     */
    @Transactional
    public ExecutionView cancel(String executionId) {
        Execution execution = requireExecution(executionId);

        if (execution.getStatus().isTerminal()) {
            throw new BizException(ErrorCode.RTM_EXECUTION_NOT_CANCELABLE,
                    "执行已处于终态 %s,无法取消".formatted(execution.getStatus().displayName()));
        }

        boolean wasDispatched = execution.getStatus().isDispatched();
        transitionTo(execution, wasDispatched ? ExecutionStatus.CANCELING : ExecutionStatus.CANCELED,
                "CancelExecution");

        if (!wasDispatched) {
            // 还没交给引擎,直接落终态,不必等任何人回话
            execution.setFinishedAt(Instant.now());
            execution.setMessage("下发前已取消");
            executionMapper.updateById(execution);
            publishFinished(execution);
            return ExecutionView.from(execution);
        }

        executionMapper.updateById(execution);
        ExecutionAttempt current = currentAttempt(executionId);
        if (current != null && current.getEngineJobId() != null) {
            ExecutionEngine engine = engineRegistry.select(execution.getJobRefType());
            if (engine != null) {
                engine.cancel(current.getEngineJobId());
            }
        }
        log.info("取消已受理 execution={}", executionId);
        return ExecutionView.from(execution);
    }

    /**
     * 能取消就取消,不能就算了 —— 不抛异常。
     *
     * <p>给"停止这个东西"这类调用方用(流任务停止、级联取消):它们要的是
     * <b>结果</b>,而"它已经自己结束了"同样满足那个结果。
     *
     * <p>为什么不是让调用方 catch:{@link #cancel} 是 {@code @Transactional} 的,
     * 它抛出 BizException 时 Spring 已经把<b>外层</b>事务标记成 rollback-only 了 ——
     * 调用方 catch 住异常照常返回,提交时却收到一个 UnexpectedRollbackException。
     * 那是一个 500,而且堆栈完全指不到真正的原因。所以判断必须发生在抛之前。
     *
     * @return true 表示这次调用确实受理了取消;false 表示它已经是终态
     */
    @Transactional
    public boolean cancelIfActive(String executionId) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null || execution.getStatus().isTerminal()) {
            return false;
        }
        cancel(executionId);
        return true;
    }

    /**
     * 把超时的执行标成 TIMEOUT。由 dg-app 的定时任务驱动。
     *
     * <p>超时判定放在 Runtime 而不是各引擎里:引擎可能整个失联,那时没人会来
     * 报告超时,而执行会永远停在 RUNNING 占着一个并发额度。
     *
     * <p>阈值<b>逐条取自 {@code timeoutMs}</b>,不是一个全局值 —— 整库迁移跑
     * 几小时是正常的,一次连通性检查超过十秒就该判死。因此这里先按最宽松的
     * 候选集捞出来,再在内存里逐条比对各自的阈值。
     */
    @Transactional
    public int sweepTimeouts(Instant now, int limit) {
        List<Execution> candidates = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .in(Execution::getStatus, ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING,
                        ExecutionStatus.CANCELING)
                // 先用一个粗筛把明显没到时间的挡在 SQL 层:再短的超时也不会小于 1 秒
                .lt(Execution::getSubmittedAt, now.minusSeconds(1))
                .orderByAsc(Execution::getSubmittedAt)
                .last("limit " + limit));

        List<Execution> stale = candidates.stream()
                .filter(e -> isOverdue(e, now))
                .toList();

        for (Execution execution : stale) {
            transitionTo(execution, ExecutionStatus.TIMEOUT, "ExecutionTimedOut");
            execution.setMessage("执行超时,已强制结束");
            execution.setErrorCode(ErrorCode.RTM_EXECUTION_TIMEOUT.code());
            stampFinish(execution);
            executionMapper.updateById(execution);

            ExecutionAttempt current = currentAttempt(execution.getId());
            if (current != null && !current.getStatus().isTerminal()) {
                finishAttempt(current, ExecutionStatus.TIMEOUT, "执行超时",
                        ErrorCode.RTM_EXECUTION_TIMEOUT.code(), null, null);
            }
            log.warn("执行超时 execution={} 提交于 {}", execution.getId(), execution.getSubmittedAt());
            publishFinished(execution);
        }
        return stale.size();
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * ListExecutions —— 五个「执行记录」页面共用的那一个查询。
     *
     * @param jobRefType null 表示不过滤,即序号 24 监控看到的全量
     */
    public PageResult<ExecutionView> list(long page, long size, JobRefType jobRefType,
                                          ExecutionStatus status, String jobRefId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();

        LambdaQueryWrapper<Execution> wrapper = new LambdaQueryWrapper<Execution>()
                .eq(Execution::getWorkspaceId, workspaceId)
                .eq(jobRefType != null, Execution::getJobRefType, jobRefType)
                .eq(status != null, Execution::getStatus, status)
                .eq(jobRefId != null && !jobRefId.isBlank(), Execution::getJobRefId, jobRefId)
                // 工作流节点默认不出现在列表里:它们属于父工作流的详情,
                // 平铺进来会让一个 20 节点的工作流把整页列表占满
                .isNull(jobRefType != JobRefType.WORKFLOW_NODE, Execution::getParentExecutionId)
                .orderByDesc(Execution::getSubmittedAt);

        Page<Execution> result = executionMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(ExecutionView::from).toList(),
                result.getTotal(), page, size);
    }

    public ExecutionView.Detail get(String executionId) {
        Execution execution = requireExecution(executionId);
        List<ExecutionView.AttemptView> attempts = attemptMapper.selectList(
                        new LambdaQueryWrapper<ExecutionAttempt>()
                                .eq(ExecutionAttempt::getExecutionId, executionId)
                                .orderByAsc(ExecutionAttempt::getAttemptNo)).stream()
                .map(ExecutionView.AttemptView::from).toList();
        return new ExecutionView.Detail(ExecutionView.from(execution), attempts);
    }

    /**
     * 只取状态。
     *
     * <p>给对账用(流任务运行态、工作流推进):它们每轮要看几十条执行的状态,
     * 而 {@link #get} 会连带把尝试列表全查出来。执行不存在时返回 null —— 对账
     * 场景里"记录没了"是要处理的情况之一,不是异常。
     */
    public ExecutionStatus statusOf(String executionId) {
        Execution execution = executionMapper.selectById(executionId);
        return execution == null ? null : execution.getStatus();
    }

    /**
     * 还没结束的工作流父执行。
     *
     * <p>编排器每轮拿它来推进。返回 (executionId, workspaceId) 而不是完整视图:
     * 推进本身会重新读需要的部分,这里只是一张待办清单。
     */
    public List<String[]> activeWorkflowExecutions(int limit) {
        return executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                        .eq(Execution::getJobRefType, JobRefType.WORKFLOW)
                        // CANCELING 必须在列表里:取消工作流之后,是编排器在下一轮
                        // 看到子执行都停了,才把父执行推到 CANCELED。漏掉它,
                        // 被取消的工作流会永远停在「取消中」
                        .in(Execution::getStatus, ExecutionStatus.PENDING,
                                ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING,
                                ExecutionStatus.CANCELING)
                        .orderByAsc(Execution::getSubmittedAt)
                        .last("limit " + Math.max(1, limit))).stream()
                .map(e -> new String[]{e.getId(), e.getWorkspaceId()})
                .toList();
    }

    /** 读回下发时的物理计划快照。工作流推进要从中取出 DAG。 */
    public Map<String, Object> planOf(String executionId) {
        Execution execution = requireExecution(executionId);
        if (execution.getPlanJson() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(execution.getPlanJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("物理计划解析失败 execution={}", executionId, e);
            return Map.of();
        }
    }

    // ── 工作流编排专用(序号 22/23)──────────────────────────────────────
    //
    // 这四个方法是给 Control 的编排器用的,Runtime 自己从不调用它们。
    // 它们存在的理由是:工作流父执行的终态<b>不由引擎决定</b> —— 引擎从头到尾
    // 不知道有"工作流"这回事,它只跑一个个节点。父执行什么时候算成功,是
    // 编排语义,归 Control。
    //
    // 把它们放在这里而不是让 Control 直接写 rt_execution,是因为 R4:
    // 这张表<b>只有一个写入者</b>。多一个写入口,状态机就多一处可能被绕过。

    /**
     * 下发一条<b>外部驱动</b>的执行 —— 不交给引擎,由调用方报告结束。
     *
     * <p>工作流父执行是这样的一条:它自己不跑任何东西,只是"那七个节点的容器"。
     * 交给引擎会立刻返回成功(没有活可干),而它其实应该一直 RUNNING 到最后
     * 一个节点结束。
     *
     * <p>这不是给工作流开的后门:任何"生命周期由平台之外的东西掌握"的执行都
     * 走这条路 —— 将来接入 Flink 时,一个提交上去就归集群管的作业也是如此。
     * 所以方法名说的是这个性质,而不是 dispatchWorkflow。
     */
    @Transactional
    public ExecutionView dispatchExternallyDriven(DispatchCommand command) {
        Instant now = Instant.now();

        Execution execution = new Execution();
        execution.setId(Ids.of("exec"));
        execution.setWorkspaceId(command.workspaceId());
        execution.setJobRefType(command.jobRefType());
        execution.setJobRefId(command.jobRefId());
        execution.setJobName(command.jobName());
        execution.setDefVersion(command.defVersion());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setParentExecutionId(command.parentExecutionId());
        execution.setTriggerType(command.triggerType());
        execution.setTriggeredBy(command.triggeredBy());
        execution.setPlanJson(writeJson(command.plan(), "物理计划"));
        execution.setTimeoutMs(command.timeoutMs());
        execution.setAttemptCount(0);
        execution.setSubmittedAt(now);
        execution.setStartedAt(now);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        executionMapper.insert(execution);

        // 直接推到 RUNNING:状态机仍然逐步走,不跳过中间态
        transitionTo(execution, ExecutionStatus.DISPATCHED, "DispatchExecution");
        transitionTo(execution, ExecutionStatus.RUNNING, "ExecutionStarted");
        executionMapper.updateById(execution);

        events.publishEvent(new ExecutionEvents.ExecutionAccepted(
                execution.getWorkspaceId(), execution.getId(), execution.getJobRefType(),
                execution.getJobRefId(), execution.getJobName(), now));

        return ExecutionView.from(execution);
    }

    /** 下发一个工作流节点。与普通下发的唯一区别是记下它是哪个节点。 */
    @Transactional
    public ExecutionView dispatchNode(DispatchCommand command, String workflowNodeId) {
        ExecutionView view = dispatch(command);
        // 同样只写这一列:dispatch 已经把执行提交给引擎了,而引擎线程此刻
        // 可能正在写 status
        executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                .eq(Execution::getId, view.id())
                .set(Execution::getWorkflowNodeId, workflowNodeId));
        return new ExecutionView(view.id(), view.jobRefType(), view.jobRefTypeDisplayName(),
                view.jobRefId(), view.jobName(), view.defVersion(), view.status(),
                view.statusDisplayName(), view.parentExecutionId(), workflowNodeId,
                view.triggerType(), view.triggeredBy(), view.attemptCount(), view.submittedAt(),
                view.startedAt(), view.finishedAt(), view.durationMs(), view.message(),
                view.errorCode(), view.rowsRead(), view.rowsWritten(), view.bytesProcessed());
    }

    /** 编排器判定工作流跑完了。 */
    @Transactional
    public void succeedFromOrchestrator(String executionId, String message) {
        finishFromOrchestrator(executionId, ExecutionStatus.SUCCEEDED, "ExecutionSucceeded",
                message, null);
    }

    /** 编排器判定工作流失败了(某个节点失败)。 */
    @Transactional
    public void failFromOrchestrator(String executionId, String message) {
        finishFromOrchestrator(executionId, ExecutionStatus.FAILED, "ExecutionFailed",
                message, ErrorCode.SYS_INTERNAL_ERROR.code());
    }

    /** 编排器判定工作流被取消(某个节点被取消)。 */
    @Transactional
    public void cancelFromOrchestrator(String executionId, String message) {
        Execution execution = requireExecution(executionId);
        // 父执行可能还在 RUNNING,取消要先过 CANCELING —— 状态机不因为
        // 调用方是编排器就放松
        if (ExecutionLifecycle.MACHINE.canTransition(execution.getStatus(),
                ExecutionStatus.CANCELING)) {
            transitionTo(execution, ExecutionStatus.CANCELING, "CancelExecution");
            executionMapper.updateById(execution);
        }
        finishFromOrchestrator(executionId, ExecutionStatus.CANCELED, "ExecutionCanceled",
                message, null);
    }

    private void finishFromOrchestrator(String executionId, ExecutionStatus status,
                                        String trigger, String message, String errorCode) {
        Execution execution = requireExecution(executionId);
        if (execution.getStatus().isTerminal()) {
            return;
        }
        // 父执行没有 attempt(它自己不跑任何东西),所以走的是这条不碰
        // attempt 的路径,而不是 finishExecution
        if (!ExecutionLifecycle.MACHINE.canTransition(execution.getStatus(), status)) {
            // RUNNING 之前先补一次 started:父执行是"下发即运行"的
            if (ExecutionLifecycle.MACHINE.canTransition(execution.getStatus(),
                    ExecutionStatus.RUNNING)) {
                transitionTo(execution, ExecutionStatus.RUNNING, "ExecutionStarted");
            } else {
                log.warn("编排器无法把执行 {} 从 {} 迁移到 {}",
                        executionId, execution.getStatus(), status);
                return;
            }
        }
        Instant now = Instant.now();
        transitionTo(execution, status, trigger);
        execution.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        execution.setErrorCode(errorCode);
        execution.setFinishedAt(now);
        execution.setDurationMs(elapsed(execution.getStartedAt() == null
                ? execution.getSubmittedAt() : execution.getStartedAt(), now));
        executionMapper.updateById(execution);
        log.info("工作流执行结束 execution={} status={} message={}", executionId, status, message);
    }

    /** 子执行(工作流节点)。父执行详情页用。 */
    public List<ExecutionView> listChildren(String parentExecutionId) {
        requireExecution(parentExecutionId);
        return executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                        .eq(Execution::getParentExecutionId, parentExecutionId)
                        .orderByAsc(Execution::getSubmittedAt)).stream()
                .map(ExecutionView::from).toList();
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 是否已超过自己的超时阈值。
     *
     * <p>计时从<b>提交</b>算起而不是从开始执行算起:一个卡在 DISPATCHED 上
     * 永远起不来的执行同样该被判超时,而它根本没有 startedAt。
     */
    private static boolean isOverdue(Execution execution, Instant now) {
        long timeout = execution.getTimeoutMs() == null || execution.getTimeoutMs() <= 0
                ? DispatchCommand.DEFAULT_TIMEOUT_MS
                : execution.getTimeoutMs();
        Instant from = execution.getSubmittedAt();
        return from != null && from.plusMillis(timeout).isBefore(now);
    }

    private void transitionTo(Execution execution, ExecutionStatus target, String trigger) {
        ExecutionLifecycle.MACHINE.checkTransition(execution.getStatus(), target);
        log.debug("执行状态迁移 {} {} -> {} ({})",
                execution.getId(), execution.getStatus(), target, trigger);
        execution.setStatus(target);
        execution.setUpdatedAt(Instant.now());
    }

    private void finishAttempt(ExecutionAttempt attempt, ExecutionStatus status, String message,
                               String errorCode, String detail, ExecutionEngine.EngineMetric metric) {
        Instant now = Instant.now();
        attempt.setStatus(status);
        attempt.setFinishedAt(now);
        attempt.setDurationMs(elapsed(attempt.getStartedAt(), now));
        attempt.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        attempt.setErrorCode(errorCode);
        attempt.setErrorDetail(truncate(detail, MAX_DETAIL_LENGTH));
        if (metric != null) {
            applyMetric(attempt, metric);
        }
        attemptMapper.updateById(attempt);
    }

    private void finishExecution(Execution execution, ExecutionStatus status, String trigger,
                                 ExecutionAttempt lastAttempt) {
        transitionTo(execution, status, trigger);
        execution.setMessage(lastAttempt.getMessage());
        execution.setErrorCode(lastAttempt.getErrorCode());
        // 指标取最后一次尝试的:重试之后前几次的读写行数不该累加进总数,
        // 那会让"同步了多少行"这个数字随重试次数虚增
        execution.setRowsRead(lastAttempt.getRowsRead());
        execution.setRowsWritten(lastAttempt.getRowsWritten());
        execution.setBytesProcessed(lastAttempt.getBytesProcessed());
        stampFinish(execution);
        executionMapper.updateById(execution);

        log.info("执行结束 execution={} type={} status={} 尝试{}次 耗时{}ms",
                execution.getId(), execution.getJobRefType(), status,
                execution.getAttemptCount(), execution.getDurationMs());
        publishFinished(execution);
    }

    private void stampFinish(Execution execution) {
        Instant now = Instant.now();
        execution.setFinishedAt(now);
        execution.setDurationMs(elapsed(
                execution.getStartedAt() != null ? execution.getStartedAt() : execution.getSubmittedAt(),
                now));
        execution.setUpdatedAt(now);
    }

    private void publishFinished(Execution e) {
        events.publishEvent(new ExecutionEvents.ExecutionFinished(
                e.getWorkspaceId(), e.getId(), e.getJobRefType(), e.getJobRefId(), e.getJobName(),
                e.getStatus(), e.getAttemptCount(), e.getDurationMs(),
                e.getRowsRead(), e.getRowsWritten(), e.getBytesProcessed(),
                e.getMessage(), e.getErrorCode(), Instant.now()));
    }

    private static void applyMetric(ExecutionAttempt a, ExecutionEngine.EngineMetric m) {
        a.setRowsRead(m.rowsRead());
        a.setRowsWritten(m.rowsWritten());
        a.setBytesProcessed(m.bytesProcessed());
    }

    private static void applyMetric(Execution e, ExecutionEngine.EngineMetric m) {
        e.setRowsRead(m.rowsRead());
        e.setRowsWritten(m.rowsWritten());
        e.setBytesProcessed(m.bytesProcessed());
        e.setUpdatedAt(Instant.now());
    }

    /** 当前(最后一次)尝试 */
    ExecutionAttempt currentAttempt(String executionId) {
        return attemptMapper.selectOne(new LambdaQueryWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getExecutionId, executionId)
                .orderByDesc(ExecutionAttempt::getAttemptNo)
                .last("limit 1"));
    }

    DispatchCommand.RetryPolicy readRetryPolicy(Execution execution) {
        if (execution.getRetryPolicyJson() == null || execution.getRetryPolicyJson().isBlank()) {
            return DispatchCommand.RetryPolicy.none();
        }
        try {
            return objectMapper.readValue(execution.getRetryPolicyJson(),
                    DispatchCommand.RetryPolicy.class);
        } catch (Exception e) {
            // 读不出来就当作不重试。反过来(默认重试)会让一个坏掉的策略
            // 变成对目标库的重复冲击。
            log.warn("重试策略解析失败,按不重试处理 execution={}", execution.getId(), e);
            return DispatchCommand.RetryPolicy.none();
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readPlan(Execution execution) {
        if (execution.getPlanJson() == null || execution.getPlanJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(execution.getPlanJson(), Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    "物理计划解析失败", e.getMessage(), e);
        }
    }

    /** 供 RetryScheduler 复投一次尝试。 */
    @Transactional
    public void retryNow(String executionId) {
        Execution execution = requireExecutionInternal(executionId);
        if (execution.getStatus().isTerminal()) {
            return;     // 期间已被取消或已落终态
        }
        DispatchCommand command = new DispatchCommand(
                execution.getWorkspaceId(), execution.getJobRefType(), execution.getJobRefId(),
                execution.getJobName(), execution.getDefVersion(), readPlan(execution),
                readRetryPolicy(execution), execution.getTimeoutMs(),
                execution.getParentExecutionId(), "RETRY", execution.getTriggeredBy());
        // 状态此刻是 DISPATCHED 或 RUNNING(上一次尝试失败没有改动 Execution 状态),
        // startAttempt 会重新走一遍下发
        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            execution.setStatus(ExecutionStatus.DISPATCHED);
        }
        startAttempt(execution, command);
    }

    /** 带租户校验的取用 —— 对外接口一律走这个 */
    private Execution requireExecution(String executionId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getId, executionId)
                .eq(Execution::getWorkspaceId, workspaceId));
        if (execution == null) {
            throw BizException.notFound(ErrorCode.RTM_EXECUTION_NOT_FOUND, executionId);
        }
        return execution;
    }

    /**
     * 不带租户校验的取用 —— <b>只给引擎回调用</b>。
     *
     * <p>回调来自执行器线程,那里没有 WorkspaceContext(它是 ThreadLocal,
     * 绑在 HTTP 请求线程上)。用 executionId 直取是安全的:这个 ID 是平台自己
     * 生成并交给引擎的,不是用户输入。
     */
    private Execution requireExecutionInternal(String executionId) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw BizException.notFound(ErrorCode.RTM_EXECUTION_NOT_FOUND, executionId);
        }
        return execution;
    }

    private ExecutionAttempt requireAttempt(String attemptId) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw BizException.notFound(ErrorCode.RTM_EXECUTION_NOT_FOUND, "尝试 " + attemptId);
        }
        return attempt;
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR,
                    label + "序列化失败", e.getMessage(), e);
        }
    }

    private static Long elapsed(Instant from, Instant to) {
        return from == null ? null : Duration.between(from, to).toMillis();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 只留前 10 帧。完整堆栈进日志,事实表里存的是够定位的那一段。 */
    private static String stackSummary(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        StackTraceElement[] trace = e.getStackTrace();
        for (int i = 0; i < Math.min(10, trace.length); i++) {
            sb.append("\n\tat ").append(trace[i]);
        }
        return sb.toString();
    }
}
