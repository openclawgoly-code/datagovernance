package com.datagov.app.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV / JSON 解析的纯函数部分(功能 12)。
 *
 * <p>这些是手写解析器里唯一真正容易错的地方,所以单独测 —— 不需要 FTP 服务器。
 */
@DisplayName("文件解析")
class FileParseRunnerTest {

    @Test
    @DisplayName("普通 CSV 行按分隔符拆开")
    void plainLine() {
        assertThat(FileParseRunner.splitCsvLine("a,b,c", ','))
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("引号里的分隔符不拆 —— 一个含逗号的地址字段拆错了毫无征兆")
    void quotedDelimiterIsNotSplit() {
        assertThat(FileParseRunner.splitCsvLine("1,\"北京市,朝阳区\",3", ','))
                .containsExactly("1", "北京市,朝阳区", "3");
    }

    @Test
    @DisplayName("两个连续引号是一个转义的引号")
    void escapedQuote() {
        assertThat(FileParseRunner.splitCsvLine("1,\"他说\"\"你好\"\"\",3", ','))
                .containsExactly("1", "他说\"你好\"", "3");
    }

    @Test
    @DisplayName("空字段保留为空串,不被跳过 —— 跳过会让后面的列全部错位")
    void emptyFieldsArePreserved() {
        assertThat(FileParseRunner.splitCsvLine("a,,c", ','))
                .containsExactly("a", "", "c");
        assertThat(FileParseRunner.splitCsvLine("a,b,", ','))
                .containsExactly("a", "b", "");
    }

    @Test
    @DisplayName("支持非逗号分隔符")
    void customDelimiter() {
        assertThat(FileParseRunner.splitCsvLine("a|b|c", '|'))
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("去掉 UTF-8 BOM —— Excel 导出的 CSV 几乎一定带它")
    void bomIsStripped() {
        // 不去掉的话第一列的列名会变成 "﻿id",于是所有映射都对不上,
        // 而报错会指向别处
        assertThat(FileParseRunner.stripBom("﻿id,name")).isEqualTo("id,name");
        assertThat(FileParseRunner.stripBom("id,name")).isEqualTo("id,name");
    }

    @Test
    @DisplayName("文件名通配只支持 * 与 ?")
    void wildcardMatching() {
        assertThat(FileParseRunner.matches("orders.csv", "*.csv")).isTrue();
        assertThat(FileParseRunner.matches("orders.json", "*.csv")).isFalse();
        assertThat(FileParseRunner.matches("2026-01.csv", "2026-??.csv")).isTrue();
        // . 被转义,不当成"任意字符"
        assertThat(FileParseRunner.matches("ordersXcsv", "*.csv")).isFalse();
    }

    @Test
    @DisplayName("JSON 对象转行:嵌套结构序列化为文本,数值保持数值")
    void jsonToRow() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> row = FileParseRunner.toRow(mapper.readTree("""
                {"id": 1, "ratio": 1.5, "ok": true, "name": "x",
                 "nested": {"a": 1}, "list": [1,2], "empty": null}
                """));

        assertThat(row.get("id")).isEqualTo(1L);
        assertThat(row.get("ratio")).isEqualTo(1.5);
        assertThat(row.get("ok")).isEqualTo(true);
        assertThat(row.get("name")).isEqualTo("x");
        assertThat(row.get("empty")).isNull();
        // 关系型列存不下嵌套结构,保留原始 JSON 而不是 toString() ——
        // 这样目标端仍能用 JSON 函数查它
        assertThat(String.valueOf(row.get("nested"))).isEqualTo("{\"a\":1}");
        assertThat(String.valueOf(row.get("list"))).isEqualTo("[1,2]");
    }

    @Test
    @DisplayName("JSON 路径逐段下钻,取不到时返回 null 而不是抛异常")
    void jsonPathExtraction() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree("{\"data\":{\"items\":[1,2,3],\"next\":\"abc\"}}");

        assertThat(ApiParseRunner.extractNode(root, "data.items").size()).isEqualTo(3);
        assertThat(ApiParseRunner.extractNode(root, "data.next").asText()).isEqualTo("abc");
        assertThat(ApiParseRunner.extractNode(root, "data.missing")).isNull();
        assertThat(ApiParseRunner.extractNode(root, null)).isSameAs(root);
    }
}
