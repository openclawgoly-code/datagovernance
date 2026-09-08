package com.datagov.metadata.domain;

/**
 * 数据源生命周期状态。取值与 {@code SPACE-MODEL.md} E.1 的 DataSourceDef 状态机一致。
 *
 * <p><b>为什么区分 DRAFT 与 UNREACHABLE</b> —— 这是本状态机最容易被做错的地方:
 * 两者都表示"现在连不上",但成因与用户该做的事完全不同。
 * <ul>
 *   <li>{@link #DRAFT}:从未验证成功过,或刚改完配置。<b>配置本身可能就是错的</b>,
 *       用户该做的是回去检查主机、端口、账号。</li>
 *   <li>{@link #UNREACHABLE}:曾经是 {@link #AVAILABLE},被周期探测(功能 6)发现连不上。
 *       <b>配置多半没问题,是环境出了状况</b>,用户该做的是找网管或 DBA。</li>
 * </ul>
 * 把二者合并成一个"失败"状态,等于把这条判断推给用户自己去猜。
 */
public enum DataSourceStatus {

    /** 草稿:新建后尚未验证连通性,或连接配置刚被修改而需要重新验证。 */
    DRAFT,

    /** 测试中:连通性测试已发起,结果未回。 */
    TESTING,

    /** 可用:最近一次验证通过,可被任务引用、可浏览结构。 */
    AVAILABLE,

    /** 不可达:曾经可用,周期探测发现连不上。仍可被任务引用,但执行会失败。 */
    UNREACHABLE,

    /** 已禁用:人工停用。不可被新任务引用。 */
    DISABLED,

    /** 已归档:终态。保留历史与血缘,不再参与任何运行时行为。 */
    ARCHIVED;

    /** 是否处于可对外提供服务的状态 —— 浏览结构、被任务引用的前置条件。 */
    public boolean isUsable() {
        return this == AVAILABLE;
    }

    public boolean isTerminal() {
        return this == ARCHIVED;
    }
}
