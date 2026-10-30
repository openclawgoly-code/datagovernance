package com.datagov.data.connector.glue;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogProvider;
import com.datagov.data.spi.catalog.ExternalCatalogConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.GlueClientBuilder;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetPartitionsRequest;
import software.amazon.awssdk.services.glue.model.GetPartitionsResponse;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Partition;
import software.amazon.awssdk.services.glue.model.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS Glue Catalog Provider（P2）。
 *
 * <p>只读元数据，不读取表数据，也不通过 StarRocks 查询外部表。
 * 凭据二选一：未配置 credential 时走 AWS DefaultCredentialsProvider；
 * 配置了凭据则使用平台凭据中的 accessKey/secretKey（不写入 config_json）。
 *
 * <p>database/table 级别失败只记 warning；认证/区域错误直接抛出，
 * 让 refresh 失败并保留上一次成功快照。
 */
@Component
public class GlueCatalogProvider implements CatalogProvider {

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern VARCHAR = Pattern.compile("varchar\\(\\s*(\\d+)\\s*\\)");
    private static final int PAGE_SIZE = 100;

    private final ObjectMapper objectMapper;

    public GlueCatalogProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<CatalogAssets.CatalogSourceType> supportedTypes() {
        return Set.of(CatalogAssets.CatalogSourceType.GLUE);
    }

    @Override
    public CatalogAssets.Snapshot readCatalog(DataSourceType dataSourceType,
                                               ConnectionConfig config,
                                               String rootPath) {
        throw new UnsupportedOperationException("Glue 只走 readExternalCatalog");
    }

    @Override
    public CatalogAssets.Snapshot readExternalCatalog(ExternalCatalogConfig request) {
        GlueConfig config = parse(request.configJson());
        if (config.region() == null) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Glue 配置缺少 region");
        }
        boolean hasAccessKey = request.accessKey() != null && !request.accessKey().isBlank();
        boolean hasSecretKey = request.secretKey() != null && !request.secretKey().isBlank();
        if (hasAccessKey != hasSecretKey) {
            throw new BizException(ErrorCode.DAT_AUTH_FAILED,
                    "Glue 静态凭据必须同时提供 accessKey 与 secretKey");
        }
        GlueClient client = buildClient(config, hasAccessKey, request);
        List<String> warnings = new ArrayList<>();
        try {
            List<CatalogAssets.Namespace> namespaces = new ArrayList<>();
            List<CatalogAssets.Dataset> datasets = new ArrayList<>();
            String nextToken = null;
            do {
                GetDatabasesRequest.Builder builder = GetDatabasesRequest.builder()
                        .maxResults(PAGE_SIZE);
                if (config.catalogId() != null) {
                    builder.catalogId(config.catalogId());
                }
                if (nextToken != null) {
                    builder.nextToken(nextToken);
                }
                GetDatabasesResponse response = client.getDatabases(builder.build());
                for (Database database : response.databaseList()) {
                    if (!included(database.name(), config.includeDatabases(),
                            config.excludeDatabases())) {
                        continue;
                    }
                    namespaces.add(new CatalogAssets.Namespace(database.name(),
                            database.name(), "DATABASE", database.description()));
                    collectTables(client, config, database.name(), datasets, warnings);
                }
                nextToken = response.nextToken();
            } while (nextToken != null && !nextToken.isBlank());
            return new CatalogAssets.Snapshot(namespaces, datasets, warnings);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.DAT_CONNECT_FAILED,
                    "Glue 目录读取失败（region=" + config.region() + "）: " + rootMessage(ex),
                    null, ex);
        } finally {
            client.close();
        }
    }

    private GlueClient buildClient(GlueConfig config, boolean staticCredentials,
                                   ExternalCatalogConfig request) {
        GlueClientBuilder builder = GlueClient.builder().region(Region.of(config.region()));
        if (staticCredentials) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(request.accessKey().trim(),
                            request.secretKey().trim())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private void collectTables(GlueClient client, GlueConfig config, String database,
                               List<CatalogAssets.Dataset> datasets, List<String> warnings) {
        String nextToken = null;
        do {
            try {
                GetTablesRequest.Builder builder = GetTablesRequest.builder()
                        .databaseName(database).maxResults(PAGE_SIZE);
                if (config.catalogId() != null) {
                    builder.catalogId(config.catalogId());
                }
                if (nextToken != null) {
                    builder.nextToken(nextToken);
                }
                GetTablesResponse response = client.getTables(builder.build());
                for (Table summary : response.tableList()) {
                    try {
                        Table table = getTable(client, config, database, summary.name());
                        List<Partition> partitions = getPartitions(client, config, database,
                                summary.name(), warnings);
                        datasets.add(toDataset(database, table, partitions));
                    } catch (Exception ex) {
                        warnings.add("读取表 " + database + "." + summary.name()
                                + " 失败: " + rootMessage(ex));
                    }
                }
                nextToken = response.nextToken();
            } catch (Exception ex) {
                warnings.add("读取 database " + database + " 的表失败: " + rootMessage(ex));
                return;
            }
        } while (nextToken != null && !nextToken.isBlank());
    }

    private Table getTable(GlueClient client, GlueConfig config, String database, String table) {
        GetTableRequest.Builder builder = GetTableRequest.builder()
                .databaseName(database).name(table);
        if (config.catalogId() != null) {
            builder.catalogId(config.catalogId());
        }
        return client.getTable(builder.build()).table();
    }

    private List<Partition> getPartitions(GlueClient client, GlueConfig config, String database,
                                          String table, List<String> warnings) {
        List<Partition> partitions = new ArrayList<>();
        String nextToken = null;
        do {
            try {
                GetPartitionsRequest.Builder builder = GetPartitionsRequest.builder()
                        .databaseName(database).tableName(table).maxResults(PAGE_SIZE);
                if (config.catalogId() != null) {
                    builder.catalogId(config.catalogId());
                }
                if (nextToken != null) {
                    builder.nextToken(nextToken);
                }
                GetPartitionsResponse response = client.getPartitions(builder.build());
                partitions.addAll(response.partitions());
                nextToken = response.nextToken();
            } catch (Exception ex) {
                warnings.add("读取分区 " + database + "." + table + " 失败: " + rootMessage(ex));
                return partitions;
            }
        } while (nextToken != null && !nextToken.isBlank());
        return partitions;
    }

    CatalogAssets.Dataset toDataset(String database, Table table,
                                            List<Partition> partitions) {
        String qualifiedName = database + "." + table.name();
        String kind = tableKind(table.tableType());
        String location = table.storageDescriptor() == null
                ? null : table.storageDescriptor().location();
        List<String> partitionColumns = table.partitionKeys() == null
                ? List.of() : table.partitionKeys().stream().map(Column::name).toList();
        List<CatalogAssets.Column> columns = new ArrayList<>();
        int ordinal = 1;
        if (table.storageDescriptor() != null && table.storageDescriptor().columns() != null) {
            for (Column column : table.storageDescriptor().columns()) {
                columns.add(toColumn(column, ordinal++, false));
            }
        }
        if (table.partitionKeys() != null) {
            for (Column column : table.partitionKeys()) {
                columns.add(toColumn(column, ordinal++, true));
            }
        }
        Map<String, String> params = table.parameters() == null
                ? Map.of() : table.parameters();
        return new CatalogAssets.Dataset(database, table.name(), qualifiedName, kind, "GLUE",
                location, parseLong(params.get("numRows")), parseLong(params.get("totalSize")),
                table.updateTime(), partitionColumns, columns,
                partitions.stream().map(partition -> toPartition(partition, partitionColumns))
                        .toList());
    }

    private CatalogAssets.Column toColumn(Column column, int ordinal, boolean partitionKey) {
        String rawType = column.type();
        Integer precision = null;
        Integer scale = null;
        Matcher decimal = DECIMAL.matcher(rawType == null ? "" : rawType.toLowerCase());
        if (decimal.find()) {
            precision = Integer.parseInt(decimal.group(1));
            scale = Integer.parseInt(decimal.group(2));
        } else {
            Matcher varchar = VARCHAR.matcher(rawType == null ? "" : rawType.toLowerCase());
            if (varchar.find()) {
                precision = Integer.parseInt(varchar.group(1));
            }
        }
        return new CatalogAssets.Column(column.name(), ordinal, rawType, canonical(rawType),
                precision, scale, true, false, partitionKey, column.comment());
    }

    CatalogAssets.Partition toPartition(Partition partition, List<String> names) {
        String path = partition.storageDescriptor() == null
                ? null : partition.storageDescriptor().location();
        String valuesJson = valuesJson(names, partition.values());
        Map<String, String> params = partition.parameters() == null
                ? Map.of() : partition.parameters();
        Long epoch = parseLong(params.get("transient_lastDdlTime"));
        Instant lastModified = epoch == null ? null : Instant.ofEpochSecond(epoch);
        if (lastModified == null && partition.creationTime() != null) {
            lastModified = partition.creationTime();
        }
        return new CatalogAssets.Partition(path == null ? valuesJson : path, valuesJson,
                parseLong(params.get("numFiles")), parseLong(params.get("totalSize")),
                lastModified);
    }

    private static String tableKind(String tableType) {
        if (tableType == null) {
            return "TABLE";
        }
        return switch (tableType.toUpperCase()) {
            case "VIRTUAL_VIEW" -> "VIEW";
            case "MATERIALIZED_VIEW" -> "MATERIALIZED_VIEW";
            case "EXTERNAL_TABLE" -> "EXTERNAL_TABLE";
            default -> "TABLE";
        };
    }

    private static CanonicalType canonical(String rawType) {
        if (rawType == null) {
            return CanonicalType.UNKNOWN;
        }
        String type = rawType.trim().toLowerCase();
        if (type.startsWith("decimal")) {
            return CanonicalType.DECIMAL;
        }
        if (type.startsWith("varchar")) {
            return CanonicalType.VARCHAR;
        }
        if (type.startsWith("char")) {
            return CanonicalType.CHAR;
        }
        return switch (type) {
            case "boolean" -> CanonicalType.BOOLEAN;
            case "tinyint" -> CanonicalType.TINYINT;
            case "smallint" -> CanonicalType.SMALLINT;
            case "int", "integer" -> CanonicalType.INT;
            case "bigint" -> CanonicalType.BIGINT;
            case "float" -> CanonicalType.FLOAT;
            case "double" -> CanonicalType.DOUBLE;
            case "string" -> CanonicalType.TEXT;
            case "date" -> CanonicalType.DATE;
            case "timestamp" -> CanonicalType.TIMESTAMP;
            case "binary" -> CanonicalType.VARBINARY;
            case "array" -> CanonicalType.ARRAY;
            default -> CanonicalType.UNKNOWN;
        };
    }

    private static boolean included(String database, List<String> include, List<String> exclude) {
        if (exclude.contains(database)) {
            return false;
        }
        return include.isEmpty() || include.contains(database);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 只取根因消息，避免把 AWS SDK 的完整堆栈回传给用户。 */
    static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static String valuesJson(List<String> names, List<String> values) {
        if (values == null) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            String name = i < names.size() ? names.get(i) : "p" + i;
            json.append("\"").append(name.replace("\"", "\\\"")).append("\":\"")
                    .append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return json.append('}').toString();
    }

    private GlueConfig parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID, "Glue 配置不能为空");
        }
        try {
            Map<String, Object> value = objectMapper.readValue(configJson,
                    new TypeReference<>() {
                    });
            if (value.containsKey("accessKey") || value.containsKey("secretKey")
                    || value.containsKey("secret")) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "Glue 配置不能包含 accessKey/secretKey，请使用平台凭据");
            }
            return new GlueConfig(stringValue(value.get("region")),
                    stringValue(value.get("catalogId")),
                    stringList(value.get("includeDatabases")),
                    stringList(value.get("excludeDatabases")));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Glue 配置不是合法 JSON", ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String text) {
            return text.isBlank() ? List.of()
                    : java.util.Arrays.stream(text.split(","))
                            .map(String::trim).filter(item -> !item.isEmpty()).toList();
        }
        if (value instanceof List<?> list) {
            return ((List<Object>) list).stream()
                    .filter(item -> item != null && !item.toString().isBlank())
                    .map(item -> item.toString().trim()).toList();
        }
        return List.of();
    }

    private static String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private record GlueConfig(String region, String catalogId, List<String> includeDatabases,
                              List<String> excludeDatabases) {
    }
}
