package com.datagov.app.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整库迁移目标端命名规则的单元测试。
 *
 * <p>这些断言的来历:拿官方 MySQL 版 Chinook(表名列名都是 PascalCase)迁进
 * PostgreSQL 时,{@code lowercaseNames} 只转了表名,列名原样保留 —— 建出来的
 * {@code genre} 表里躺着一个叫 {@code "Name"} 的列,而 PostgreSQL 里
 * {@code SELECT name FROM genre} 会直接报错。
 */
class TargetNamingTest {

    @Nested
    @DisplayName("表名")
    class Tables {

        @Test
        @DisplayName("前缀后缀拼接后再转小写,而不是分别转")
        void prefixSuffixThenLowercase() {
            assertThat(TargetNaming.table("Track", "ODS_", "_DELTA", true))
                    .isEqualTo("ods_track_delta");
        }

        @Test
        @DisplayName("开关关掉时原样保留")
        void keepsCaseWhenDisabled() {
            assertThat(TargetNaming.table("Track", null, null, false)).isEqualTo("Track");
        }

        @Test
        @DisplayName("前缀后缀为 null 时按空串处理")
        void nullAffixesAreEmpty() {
            assertThat(TargetNaming.table("Track", null, null, true)).isEqualTo("track");
        }
    }

    @Nested
    @DisplayName("列名")
    class Columns {

        @Test
        @DisplayName("列名跟着表名一起转 —— 只转一半等于没转")
        void lowercasesColumns() {
            assertThat(TargetNaming.column("GenreId", true)).isEqualTo("genreid");
            assertThat(TargetNaming.column("Name", true)).isEqualTo("name");
        }

        @Test
        @DisplayName("开关关掉时原样保留")
        void keepsCaseWhenDisabled() {
            assertThat(TargetNaming.column("GenreId", false)).isEqualTo("GenreId");
        }
    }

    @Test
    @DisplayName("土耳其语环境下 ID 不会变成 ıd(无点 i)")
    void isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            // 这不是杞人忧天:土耳其语的 i/I 大小写映射与其它语言不同,
            // "ID".toLowerCase() 在这个 Locale 下得到的是 "ıd" —— 一个和 id
            // 完全不同的标识符。同一份任务在两台机器上会建出不同的列名。
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThat(TargetNaming.column("ID", true)).isEqualTo("id");
            assertThat(TargetNaming.column("InvoiceID", true)).isEqualTo("invoiceid");
            assertThat(TargetNaming.table("Invoice", null, null, true)).isEqualTo("invoice");
        } finally {
            Locale.setDefault(original);
        }
    }
}
