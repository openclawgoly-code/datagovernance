package com.datagov.data.spi.ddl;

import com.datagov.data.spi.catalog.CanonicalType;

import java.util.List;
import java.util.Map;

/**
 * 目标表的建表规格与生成结果(功能 9「预览并修改建表语句」)。
 *
 * <p><b>为什么要有「预览并修改」这一步:</b> 类型映射再周密也有平台猜不到的地方 ——
 * 目标端要不要分区、副本数几个、字符集用哪个、某个字段实际上只存两位小数。
 * 平台生成一份合理的初稿,人来定稿,这比两个极端都好:全自动会在生产上建出
 * 一堆需要事后改的表,全手工则让「整库迁移」名不副实。
 */
public final class TableDdl {

    private TableDdl() {
    }

    /**
     * 建表请求 —— 源表结构 + 目标端的命名与约定。
     *
     * @param targetDatabase 目标库;方言不需要时忽略
     * @param targetSchema   目标模式;方言不需要时忽略
     * @param targetTable    目标表名
     * @param columns        列定义,顺序即建表顺序
     * @param primaryKeys    主键列名;空表示不建主键
     * @param comment        表注释
     * @param options        方言特有选项(Doris 的 replication_num、MySQL 的 charset……)
     */
    public record CreateTableSpec(
            String targetDatabase,
            String targetSchema,
            String targetTable,
            List<ColumnSpec> columns,
            List<String> primaryKeys,
            String comment,
            Map<String, String> options
    ) {

        public CreateTableSpec {
            columns = columns == null ? List.of() : List.copyOf(columns);
            primaryKeys = primaryKeys == null ? List.of() : List.copyOf(primaryKeys);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    /**
     * 一列的目标定义。
     *
     * @param name          列名
     * @param canonicalType 规范类型 —— <b>不是</b>源端原始类型名
     * @param precision     数值精度 / 字符串长度;不适用时 null
     * @param scale         小数位;不适用时 null
     * @param nullable      是否可空
     * @param comment       列注释
     */
    public record ColumnSpec(
            String name,
            CanonicalType canonicalType,
            Integer precision,
            Integer scale,
            boolean nullable,
            String comment
    ) {
    }

    /**
     * 生成结果。
     *
     * @param statements 建表语句。可能不止一条 —— 有些方言的注释、索引要单独下
     * @param warnings   生成过程中的提醒(类型降级、方言不支持某个约束……)。
     *                   <b>必须呈现给用户</b>:整库迁移最常见的事故是某个字段悄悄
     *                   变窄了,而这类降级只会在这里出现一次
     */
    public record GeneratedDdl(List<String> statements, List<String> warnings) {

        public GeneratedDdl {
            statements = statements == null ? List.of() : List.copyOf(statements);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 拼成可编辑的一段文本,供 UI 直接放进编辑器 */
        public String asScript() {
            return String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";");
        }
    }
}
