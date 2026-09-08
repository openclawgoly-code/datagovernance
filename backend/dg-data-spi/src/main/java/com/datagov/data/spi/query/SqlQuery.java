package com.datagov.data.spi.query;

import com.datagov.data.spi.catalog.CanonicalType;

import java.util.List;

/**
 * 自定义 SQL 查询的请求与结果模型(功能 7「支持自定义查询类的 SQL 语句」)。
 *
 * <p>集中在一个文件:这几个 record 只有数据没有行为,且总是被一起阅读。
 */
public final class SqlQuery {

    private SqlQuery() {
    }

    /**
     * 查询请求。
     *
     * @param sql            SQL 语句。必须通过 {@link ReadOnlySqlGuard} 校验
     * @param maxRows        最多返回多少行。<b>不是可选项</b> —— 没有上限的查询
     *                       会把一张亿级表整个拉进平台内存
     * @param timeoutSeconds 查询超时。同样不是可选项:一条慢查询能占住目标库
     *                       的连接与 CPU,平台不该有能力对别人的生产库这么做
     */
    public record Request(String sql, int maxRows, int timeoutSeconds) {

        public static final int DEFAULT_MAX_ROWS = 200;
        public static final int MAX_ALLOWED_ROWS = 2000;
        public static final int DEFAULT_TIMEOUT_SECONDS = 30;
        public static final int MAX_ALLOWED_TIMEOUT_SECONDS = 120;

        public Request {
            if (maxRows <= 0) {
                maxRows = DEFAULT_MAX_ROWS;
            }
            maxRows = Math.min(maxRows, MAX_ALLOWED_ROWS);
            if (timeoutSeconds <= 0) {
                timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            }
            timeoutSeconds = Math.min(timeoutSeconds, MAX_ALLOWED_TIMEOUT_SECONDS);
        }

        public static Request of(String sql) {
            return new Request(sql, DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT_SECONDS);
        }
    }

    /**
     * 结果列。
     *
     * @param canonicalType 规范化类型,与结构浏览用的是同一套映射 ——
     *                      同一列在"浏览表结构"和"查询结果"里必须显示同一个类型,
     *                      否则用户会怀疑其中一个是错的
     */
    public record Column(String name, String rawType, CanonicalType canonicalType) {
    }

    /**
     * 查询结果。
     *
     * @param rows      行数据。每个单元格已转成字符串 —— 平台只负责展示,
     *                  不承担把目标端类型映射成 Java 类型的责任,那会引入一堆
     *                  只有在特定驱动上才出现的转换异常
     * @param truncated 是否因为达到 maxRows 而被截断。<b>必须如实告知</b>:
     *                  用户看到 200 行却不知道后面还有,会据此得出错误结论
     */
    public record Result(
            List<Column> columns,
            List<List<String>> rows,
            int rowCount,
            boolean truncated,
            long elapsedMillis
    ) {
    }
}
