package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.data.spi.catalog.CatalogModel;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.rule.RuleInterpreter;
import com.datagov.runtime.spi.ExecutionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件解析入库(功能 12)。
 *
 * <p>从 FTP/SFTP 拉取 CSV 或 JSON,逐行解析后写进目标表。<b>全程流式</b> ——
 * 文件可能有几百万行,先读进内存再写是走不通的。
 *
 * <p>CSV 解析器是手写的而不是引入 commons-csv:需要的只是"带引号的字段拆分"
 * 这一件事,而多一个依赖要跟着它的 CVE 走。手写的部分有对应的单元测试。
 */
@Component
public class FileParseRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(FileParseRunner.class);

    private static final int PROGRESS_INTERVAL = 5_000;

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;
    private final RowWriter rowWriter;
    private final RuleResolver ruleResolver;
    private final ObjectMapper objectMapper;
    private final Map<DataSourceType, FileCatalogReader> fileReaders;

    public FileParseRunner(DataSourceMapper dataSourceMapper,
                           ConnectionConfigAssembler assembler,
                           RowWriter rowWriter,
                           RuleResolver ruleResolver,
                           ObjectMapper objectMapper,
                           List<DataSourceConnector> connectors) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
        this.rowWriter = rowWriter;
        this.ruleResolver = ruleResolver;
        this.objectMapper = objectMapper;

        this.fileReaders = new java.util.EnumMap<>(DataSourceType.class);
        for (DataSourceConnector connector : connectors) {
            if (connector instanceof FileCatalogReader reader) {
                for (DataSourceType type : connector.supportedTypes()) {
                    fileReaders.put(type, reader);
                }
            }
        }
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.FILE_PARSE;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Map<String, Object> source = section(context, "source");
        Map<String, Object> target = section(context, "target");
        Map<String, String> mappings = stringMap(context.plan().get("fieldMappings"));
        Map<String, List<RuleInterpreter.Rule>> fieldRules =
                ruleResolver.resolve(context.workspaceId(), context.plan().get("fieldRules"));

        DataSourceEntity sourceDs = requireDataSource(str(source, "dataSourceId"), "文件");
        DataSourceEntity targetDs = requireDataSource(str(target, "dataSourceId"), "目标");

        FileCatalogReader reader = fileReaders.get(sourceDs.getType());
        if (reader == null) {
            throw new IllegalArgumentException(
                    "%s 不是文件型数据源".formatted(sourceDs.getType().displayName()));
        }

        List<String> files = resolveFiles(reader, sourceDs, source);
        if (files.isEmpty()) {
            throw new IllegalStateException("路径下没有匹配的文件: " + str(source, "path"));
        }
        log.info("文件解析开始 execution={} 匹配到 {} 个文件", context.executionId(), files.size());

        List<String> sourceFields = List.copyOf(mappings.keySet());
        List<String> targetColumns = sourceFields.stream().map(mappings::get).toList();
        Charset charset = Charset.forName(String.valueOf(
                source.getOrDefault("charset", "UTF-8")));

        long rowsRead = 0;
        try (RowWriter.Session session = rowWriter.open(targetDs, str(target, "database"),
                str(target, "schema"), str(target, "table"), sourceFields, targetColumns,
                fieldRules, str(target, "writeMode"), intValue(target.get("batchSize"), 1000))) {

            for (String file : files) {
                context.throwIfCanceled();
                ConnectionConfig config = assembler.assemble(sourceDs, sourceDs.getWorkspaceId());
                try (InputStream stream = reader.openFile(sourceDs.getType(), config, file);
                     BufferedReader lines = new BufferedReader(
                             new InputStreamReader(stream, charset))) {

                    rowsRead += "JSON".equals(str(source, "format"))
                            ? parseJson(lines, source, session, context, rowsRead)
                            : parseCsv(lines, source, session, context, rowsRead);
                }
                log.info("已解析 {}(累计 {} 行)", file, rowsRead);
            }
            long written = session.finish();
            log.info("文件解析完成 execution={} 读{}行 写{}行",
                    context.executionId(), rowsRead, written);
            return RunResult.of(rowsRead, written, 0);
        }
    }

    /** 展开文件清单:path 是文件就用它,是目录就按 filePattern 筛选。 */
    private List<String> resolveFiles(FileCatalogReader reader, DataSourceEntity ds,
                                      Map<String, Object> source) {
        String path = str(source, "path");
        String pattern = str(source, "filePattern");
        ConnectionConfig config = assembler.assemble(ds, ds.getWorkspaceId());

        List<CatalogModel.FileEntry> entries;
        try {
            entries = reader.listEntries(ds.getType(), config, path);
        } catch (RuntimeException e) {
            // 列不出来多半是因为 path 本身就是一个文件 —— 直接用它
            return List.of(path);
        }
        if (entries.isEmpty()) {
            return List.of(path);
        }
        // path 指名道姓写的就是一个文件时,连接器返回它自己那一条(path 与 target 相同)。
        // 此时 filePattern 不该再参与筛选 —— 用户已经把文件名写出来了,再拿模式筛
        // 一遍,只会在两者不一致时得到"匹配到 0 个文件"这种毫无线索的结果。
        if (entries.size() == 1 && !entries.get(0).directory()
                && entries.get(0).path().equals(path)) {
            return List.of(path);
        }
        return entries.stream()
                .filter(e -> !e.directory())
                .filter(e -> pattern == null || pattern.isBlank() || matches(e.name(), pattern))
                .map(CatalogModel.FileEntry::path)
                .toList();
    }

    /** 通配符匹配。只支持 * 与 ? —— 那是用户对"文件名模式"的全部期待。 */
    static boolean matches(String name, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    // ── CSV ─────────────────────────────────────────────────────────────

    private long parseCsv(BufferedReader lines, Map<String, Object> source,
                          RowWriter.Session session, RunContext context, long alreadyRead)
            throws Exception {
        char delimiter = String.valueOf(source.getOrDefault("delimiter", ",")).charAt(0);
        boolean hasHeader = Boolean.parseBoolean(
                String.valueOf(source.getOrDefault("hasHeader", "true")));
        List<String> declared = stringList(source.get("columns"));

        List<String> header = null;
        if (hasHeader) {
            String first = lines.readLine();
            if (first == null) {
                return 0;       // 空文件
            }
            // 去掉 UTF-8 BOM:Excel 导出的 CSV 几乎一定带它,而它会让第一列
            // 的列名变成 "﻿id" —— 于是所有映射都对不上,报错却指向别处
            header = splitCsvLine(stripBom(first), delimiter);
        }
        List<String> columns = header != null ? header : declared;
        if (columns.isEmpty()) {
            throw new IllegalStateException("无法确定 CSV 的列名:既没有表头也没有配置 columns");
        }

        long read = 0;
        String line;
        while ((line = lines.readLine()) != null) {
            context.throwIfCanceled();
            if (line.isBlank()) {
                continue;
            }
            List<String> values = splitCsvLine(line, delimiter);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), i < values.size() ? values.get(i) : null);
            }
            session.write(row);
            read++;
            if ((alreadyRead + read) % PROGRESS_INTERVAL == 0) {
                context.progress().accept(new ExecutionEngine.EngineMetric(
                        alreadyRead + read, session.rowsWritten(), 0));
            }
        }
        return read;
    }

    /**
     * 拆一行 CSV。
     *
     * <p>处理引号包裹与转义的双引号 —— 那是 CSV 里唯一真正麻烦的部分:
     * 一个含逗号的地址字段用 {@code split(",")} 会被拆成两列,而且错得毫无征兆。
     */
    static List<String> splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // "" 是一个转义的引号,不是引号的结束
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // ── JSON ────────────────────────────────────────────────────────────

    /**
     * 解析 JSON。
     *
     * <p>整个文件读进内存再解析 —— 与 CSV 的流式处理不同。这是 JSON 的固有代价:
     * 一个 JSON 数组只有读完最后一个字节才知道它合法。文件很大时该用 JSONL
     * (每行一个对象),那时按 CSV 那样逐行处理即可 —— 本方法会自动识别。
     */
    private long parseJson(BufferedReader lines, Map<String, Object> source,
                           RowWriter.Session session, RunContext context, long alreadyRead)
            throws Exception {
        String content = lines.lines().reduce("", (a, b) -> a + "\n" + b).strip();
        if (content.isEmpty()) {
            return 0;
        }

        // JSONL:每行一个对象。首字符是 { 且含换行时按它处理 ——
        // 大文件本就该用这个格式
        if (content.startsWith("{") && content.contains("\n")) {
            long read = 0;
            for (String line : content.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                context.throwIfCanceled();
                session.write(toRow(objectMapper.readTree(line)));
                read++;
            }
            return read;
        }

        JsonNode root = objectMapper.readTree(content);
        String jsonPath = str(source, "jsonPath");
        JsonNode array = root;
        if (jsonPath != null && !jsonPath.isBlank()) {
            for (String segment : jsonPath.split("\\.")) {
                array = array.path(segment);
            }
        }
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "JSON 路径「%s」指向的不是数组".formatted(jsonPath == null ? "(顶层)" : jsonPath));
        }

        long read = 0;
        for (JsonNode item : array) {
            context.throwIfCanceled();
            session.write(toRow(item));
            read++;
            if ((alreadyRead + read) % PROGRESS_INTERVAL == 0) {
                context.progress().accept(new ExecutionEngine.EngineMetric(
                        alreadyRead + read, session.rowsWritten(), 0));
            }
        }
        return read;
    }

    /** JSON 对象 → 行。嵌套对象序列化成字符串 —— 目标表是关系型的,没有嵌套。 */
    static Map<String, Object> toRow(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            row.put(entry.getKey(), switch (value.getNodeType()) {
                case NULL, MISSING -> null;
                case BOOLEAN -> value.asBoolean();
                // 两个分支必须各自装箱。写成 `cond ? asLong() : asDouble()` 会触发
                // Java 的三目数值提升 —— long 被静默加宽成 double,于是每个整数
                // 都变成 1.0 那样的浮点,写进 BIGINT 列时驱动要么报错要么悄悄取整。
                case NUMBER -> value.isIntegralNumber()
                        ? (Object) value.asLong()
                        : (Object) value.asDouble();
                case STRING -> value.asText();
                // 对象与数组落到关系型列里只能是文本。保留原始 JSON 而不是
                // toString(),这样目标端仍能用 JSON 函数查它
                default -> value.toString();
            });
        });
        return row;
    }

    @Override
    public String classify(Exception e) {
        if (e instanceof java.sql.SQLException) {
            return "DAT_QUERY_FAILED";
        }
        return e instanceof java.io.IOException ? "DAT_CONNECT_FAILED" : "SYS_INTERNAL_ERROR";
    }

    private DataSourceEntity requireDataSource(String id, String label) {
        DataSourceEntity entity = id == null ? null : dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("%s数据源不存在: %s".formatted(label, id));
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(RunContext context, String key) {
        Object value = context.plan().get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("物理计划缺少 " + key + " 段");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) ->
                result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return result;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
