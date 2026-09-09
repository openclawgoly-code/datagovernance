package com.datagov.app.runner;

import java.util.Locale;

/**
 * 整库迁移(功能 9)的目标端命名规则。
 *
 * <p><b>单独一个类,是因为它有两个调用方</b>:执行期的 {@link DbMigrationRunner},
 * 和「预览并修改建表语句」的 TableDdlController。两边各写一份必然漂移,而漂移
 * 的表现是<b>预览给你看的建表语句,和实际建出来的表不一样</b> —— 那正好把
 * 预览这个功能存在的意义抵消掉。
 *
 * <p>命名规则本身归 Metadata(功能 9 的备注:「命名规则归 Metadata,方言归 Data」),
 * 这里只是在装配层执行它。
 */
public final class TargetNaming {

    private TargetNaming() {
    }

    /** 目标表名:前缀 + 源表名 + 后缀,再按开关转小写。 */
    public static String table(String sourceTable, String prefix, String suffix,
                               boolean lowercase) {
        String name = (prefix == null ? "" : prefix)
                + sourceTable
                + (suffix == null ? "" : suffix);
        return lowercase ? lower(name) : name;
    }

    /**
     * 目标列名。<b>与表名共用同一个开关</b>。
     *
     * <p>为什么列名也要跟着转:PostgreSQL 里不加引号的标识符会折成小写,所以一个
     * 建成 {@code "Name"} 的列,从此每一次查询都得写引号 ——
     * {@code SELECT name FROM genre} 直接报错。把 MySQL 版 Chinook(表名列名都是
     * PascalCase)迁进 PostgreSQL 时,表名转了、列名没转,是最难受的一种半套:
     * 用户连约定都猜不出来,而平台自己的字段映射、清洗规则也全都得跟着写引号。
     */
    public static String column(String columnName, boolean lowercase) {
        return lowercase ? lower(columnName) : columnName;
    }

    /**
     * 转小写<b>必须锁定 Locale.ROOT</b>。
     *
     * <p>默认 Locale 下 {@code "ID".toLowerCase()} 在土耳其语环境会得到 {@code "ıd"}
     * —— 无点的 ı,一个和 i 不同的字符。那样建出来的列名与源端对不上,而这台
     * 机器上跑得好好的同一份任务,换一台就悄悄建错。这不是杞人忧天:本项目的
     * Pagila 夹具里就有一个叫 {@code bıgınt} 的域,正是这个字符。
     */
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
