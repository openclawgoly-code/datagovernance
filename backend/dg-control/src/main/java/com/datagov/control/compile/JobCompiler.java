package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.data.spi.DataSourceType;

import java.util.Map;

/**
 * 一种任务的编译器。
 *
 * <p>每种 {@link JobType} 一个实现。这样切分而不是写一个带 switch 的大编译器,
 * 是因为编译规则是各类型里<b>最容易发散</b>的部分:整库迁移要校验两个库的类型
 * 映射矩阵,工作流要校验 DAG 有没有环,两者除了"都叫编译"以外没有共同之处。
 * 塞进一个类,那个类会在半年内变成谁也不敢改的东西。
 *
 * <p>共同的部分(结构校验、依赖校验、调度校验)由 {@link CompilerSupport} 提供,
 * 各实现调用它,而不是继承一个抽象基类 —— 继承会诱导后来者往基类里加"只有
 * 某两种类型才用得上"的方法。
 */
public interface JobCompiler {

    JobType jobType();

    /**
     * 编译一份定义。
     *
     * <p><b>不得抛异常表达编译失败</b> —— 失败是编译的正常结果之一,要通过
     * {@link CompileResult} 带着错误定位返回。异常只留给"编译器自己坏了"。
     */
    CompileResult compile(CompileContext context);

    /**
     * 编译上下文。
     *
     * <p>{@code metadataLookup} 是编译器<b>唯一</b>的外部信息来源。Control 的
     * must_not_do 第一条是「不得直接读写业务数据」——要知道源表有哪些字段,
     * 向 Metadata 要它的目录快照,而不是自己开一条 JDBC 连接去查。
     */
    record CompileContext(
            JobDefinition definition,
            Map<String, Object> config,
            MetadataLookup metadata
    ) {
    }

    /**
     * 编译期能向 Metadata 问的问题。
     *
     * <p>刻意做得很窄:只有这三个方法。宽泛的"给我 Metadata 的 Service"会让编译器
     * 逐渐用上 Metadata 的写接口,而编译是一个纯读的过程 —— 编译一次不该改变任何
     * 定义的状态。
     */
    interface MetadataLookup {

        /** 数据源当前是否可用(AVAILABLE)。不可用则依赖校验不过。 */
        boolean isDataSourceAvailable(String workspaceId, String dataSourceId);

        /** 数据源名,用于把 ID 翻译成人看得懂的报错 */
        String dataSourceName(String workspaceId, String dataSourceId);

        /**
         * 表的字段列表,来自目录快照。
         *
         * @return 字段名 → 规范类型({@code CanonicalType} 的 name());
         *         表不存在或没有快照时返回空 Map
         */
        Map<String, String> tableColumns(String workspaceId, String dataSourceId,
                                         String database, String schema, String table);

        /**
         * 数据源的类型(方言)。
         *
         * <p>编译期要它做什么:同一份配置在不同方言上的<b>结果</b>可能不同,而不只是
         * 语法不同。最典型的是 UPSERT —— Doris/StarRocks 没有 upsert 语法,按主键
         * 覆盖靠的是表模型,目标表若建成了明细模型,任务会成功、数据会重复,
         * 没有任何报错。这类事必须在保存任务时就说出来。
         *
         * @return 拿不到数据源时返回 null
         */
        DataSourceType dataSourceType(String workspaceId, String dataSourceId);
    }
}
