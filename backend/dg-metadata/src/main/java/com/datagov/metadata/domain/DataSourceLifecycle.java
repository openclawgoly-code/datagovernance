package com.datagov.metadata.domain;

import com.datagov.common.lifecycle.StateMachine;

import static com.datagov.metadata.domain.DataSourceStatus.ARCHIVED;
import static com.datagov.metadata.domain.DataSourceStatus.AVAILABLE;
import static com.datagov.metadata.domain.DataSourceStatus.DISABLED;
import static com.datagov.metadata.domain.DataSourceStatus.DRAFT;
import static com.datagov.metadata.domain.DataSourceStatus.TESTING;
import static com.datagov.metadata.domain.DataSourceStatus.UNREACHABLE;

/**
 * 数据源状态机。严格对应 {@code SPACE-MODEL.md} E.1:
 *
 * <pre>
 * DRAFT ──TestConnectivity──> TESTING ──ConnectivitySucceeded──> AVAILABLE
 *   ^                            │
 *   └────ConnectivityFailed──────┘
 *
 * AVAILABLE ──ProbeFailed(序号6 周期探测)──> UNREACHABLE ──ProbeSucceeded──> AVAILABLE
 * AVAILABLE | UNREACHABLE ──DisableDataSource──> DISABLED ──Archive──> ARCHIVED
 * </pre>
 *
 * <p>注意手工测试与周期探测走的是<b>不同的边</b>:手工测试失败回 {@link DataSourceStatus#DRAFT}
 * (配置可能就没配对),周期探测失败进 {@link DataSourceStatus#UNREACHABLE}(本来是好的)。
 * 见 {@link DataSourceStatus} 的说明。
 *
 * <p><b>两处对文档的补充,已在此显式标注</b>(文档未涵盖,但不补会导致功能不可用):
 * <ol>
 *   <li>{@code AVAILABLE|UNREACHABLE ──UpdateConnection──> DRAFT}:改了连接参数后,
 *       上一次验证的结论对新参数不再成立,必须重新验证。不打回 DRAFT 的话,
 *       用户可以把一个已验证的数据源改成任意错误配置而状态仍显示"可用"。</li>
 *   <li>{@code DISABLED ──EnableDataSource──> DRAFT}:文档只画了 DISABLED → ARCHIVED,
 *       没有回路。若严格照做,任何一次误禁用都不可撤销,只能归档后重建 ——
 *       这几乎肯定不是本意。回到 DRAFT 而非直接 AVAILABLE,是因为停用期间
 *       目标端可能已变化,重新启用必须重测。</li>
 * </ol>
 * 这两条已提请确认;若判定为不应补充,删掉对应的 {@code allow} 即可,
 * 其余逻辑不依赖它们。
 */
public final class DataSourceLifecycle {

    public static final StateMachine<DataSourceStatus> MACHINE =
            StateMachine.builder(DataSourceStatus.class, DRAFT)
                    // 手工连通性测试
                    .allow(DRAFT, TESTING, "TestConnectivity")
                    .allow(TESTING, AVAILABLE, "ConnectivitySucceeded")
                    .allow(TESTING, DRAFT, "ConnectivityFailed")
                    // 已可用的数据源允许再次手工重测
                    .allow(AVAILABLE, TESTING, "TestConnectivity")
                    .allow(UNREACHABLE, TESTING, "TestConnectivity")

                    // 周期探测(功能 6)—— 与手工测试是不同的边
                    .allow(AVAILABLE, UNREACHABLE, "ProbeFailed")
                    .allow(UNREACHABLE, AVAILABLE, "ProbeSucceeded")

                    // 人工停用与归档
                    .allow(AVAILABLE, DISABLED, "DisableDataSource")
                    .allow(UNREACHABLE, DISABLED, "DisableDataSource")
                    .allow(DRAFT, DISABLED, "DisableDataSource")
                    .allow(DISABLED, ARCHIVED, "Archive")

                    // ↓ 对文档的两处补充,理由见类注释
                    .allow(AVAILABLE, DRAFT, "UpdateConnection")
                    .allow(UNREACHABLE, DRAFT, "UpdateConnection")
                    .allow(DISABLED, DRAFT, "EnableDataSource")

                    .terminal(ARCHIVED)
                    .build();

    private DataSourceLifecycle() {
    }

    /**
     * 连通性测试的结果如何影响状态。
     *
     * @param probe true 表示来自周期探测(功能 6),false 表示用户手工点的「测试连接」
     */
    public static DataSourceStatus afterConnectivityResult(DataSourceStatus current,
                                                           boolean success,
                                                           boolean probe) {
        if (success) {
            return AVAILABLE;
        }
        // 周期探测失败 → 环境问题;手工测试失败 → 配置问题。见 DataSourceStatus 说明。
        return probe ? UNREACHABLE : DRAFT;
    }
}
