package com.datagov.data.connector.hive;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.catalog.CatalogAssets;
import com.datagov.data.spi.catalog.CatalogProvider;
import com.datagov.data.spi.catalog.ExternalCatalogConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.security.UserGroupInformation;
import org.springframework.stereotype.Component;

import java.security.PrivilegedExceptionAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hive Metastore Catalog Provider（P2）。
 *
 * <p>只读元数据：database/table/view/column/partition，不访问表数据。
 * 没有 SASL 时直接连接；提供 principal + keytab 时走 Kerberos UGI。
 * keytab 路径属于运维配置，不是秘密内容；真正的口令仍由部署环境保护。
 *
 * <p>单个 database/table 读取失败只记录 warning，其他对象继续采集；
 * 连接/认证失败直接抛出，让 refresh 整体失败并保留上一次成功快照。
 */
@Component
public class HmsCatalogProvider implements CatalogProvider {

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern VARCHAR = Pattern.compile("varchar\\(\\s*(\\d+)\\s*\\)");

    private final ObjectMapper objectMapper;

    public HmsCatalogProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<CatalogAssets.CatalogSourceType> supportedTypes() {
        return Set.of(CatalogAssets.CatalogSourceType.HIVE_METASTORE);
    }

    @Override
    public CatalogAssets.Snapshot readCatalog(DataSourceType dataSourceType,
                                               com.datagov.data.spi.ConnectionConfig config,
                                               String rootPath) {
        throw new UnsupportedOperationException("HMS 只走 readExternalCatalog");
    }

    @Override
    public CatalogAssets.Snapshot readExternalCatalog(ExternalCatalogConfig request) {
        HmsConfig config = parse(request.configJson());
        if (config.uris().isEmpty()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Hive Metastore 配置缺少 uris");
        }
        Configuration conf = new Configuration();
        conf.set("hive.metastore.uris", String.join(",", config.uris()));
        conf.setInt("hive.metastore.client.socket.timeout", Math.max(1, config.timeoutSeconds()));
        if (config.warehouse() != null) {
            conf.set("hive.metastore.warehouse.dir", config.warehouse());
        }
        try {
            if (config.kerberosPrincipal() != null && config.keytabPath() != null) {
                conf.set("hadoop.security.authentication", "kerberos");
                conf.setBoolean("hive.metastore.sasl.enabled", true);
                UserGroupInformation.setConfiguration(conf);
                UserGroupInformation ugi = UserGroupInformation
                        .loginUserFromKeytabAndReturnUGI(config.kerberosPrincipal(),
                                config.keytabPath());
                return ugi.doAs((PrivilegedExceptionAction<CatalogAssets.Snapshot>)
                        () -> read(conf, config));
            }
            if (config.kerberosPrincipal() != null || config.keytabPath() != null) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "HMS Kerberos 需要同时提供 kerberosPrincipal 与 keytabPath");
            }
            return read(conf, config);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.DAT_CONNECT_FAILED,
                    "Hive Metastore 连接/认证失败: " + rootMessage(ex),
                    "uris=" + config.uris(), ex);
        }
    }

    private CatalogAssets.Snapshot read(Configuration conf, HmsConfig config) throws Exception {
        HiveMetaStoreClient client = new HiveMetaStoreClient(conf);
        List<String> warnings = new ArrayList<>();
        try {
            List<CatalogAssets.Namespace> namespaces = new ArrayList<>();
            List<CatalogAssets.Dataset> datasets = new ArrayList<>();
            List<String> databases = client.getAllDatabases();
            for (String database : databases) {
                if (!included(database, config.includeDatabases(), config.excludeDatabases())) {
                    continue;
                }
                namespaces.add(new CatalogAssets.Namespace(database, database,
                        "DATABASE", null));
                List<String> tables;
                try {
                    tables = client.getAllTables(database);
                } catch (Exception ex) {
                    warnings.add("读取 database " + database + " 的表失败: " + rootMessage(ex));
                    continue;
                }
                for (String tableName : tables) {
                    try {
                        Table table = client.getTable(database, tableName);
                        List<Partition> partitions = readPartitions(client, database, tableName,
                                warnings);
                        datasets.add(toDataset(database, table, partitions));
                    } catch (Exception ex) {
                        warnings.add("读取表 " + database + "." + tableName
                                + " 失败: " + rootMessage(ex));
                    }
                }
            }
            return new CatalogAssets.Snapshot(namespaces, datasets, warnings);
        } finally {
            client.close();
        }
    }

    private List<Partition> readPartitions(HiveMetaStoreClient client, String database,
                                           String table, List<String> warnings) {
        try {
            return client.listPartitions(database, table, (short) -1);
        } catch (Exception ex) {
            warnings.add("读取分区 " + database + "." + table + " 失败: " + rootMessage(ex));
            return List.of();
        }
    }

    CatalogAssets.Dataset toDataset(String database, Table table,
                                            List<Partition> partitions) {
        String qualifiedName = database + "." + table.getTableName();
        String kind = tableKind(table.getTableType());
        String location = table.getSd() == null ? null : table.getSd().getLocation();
        List<CatalogAssets.Column> columns = new ArrayList<>();
        int ordinal = 1;
        if (table.getSd() != null && table.getSd().getCols() != null) {
            for (FieldSchema field : table.getSd().getCols()) {
                columns.add(toColumn(field, ordinal++, false));
            }
        }
        if (table.getPartitionKeys() != null) {
            for (FieldSchema field : table.getPartitionKeys()) {
                columns.add(toColumn(field, ordinal++, true));
            }
        }
        List<String> partitionColumns = table.getPartitionKeys() == null
                ? List.of()
                : table.getPartitionKeys().stream().map(FieldSchema::getName).toList();
        Map<String, String> params = table.getParameters() == null
                ? Map.of() : table.getParameters();
        Long rowCount = parseLong(params.get("numRows"));
        Long sizeBytes = parseLong(params.get("totalSize"));
        return new CatalogAssets.Dataset(database, table.getTableName(), qualifiedName,
                kind, "HIVE", location, rowCount, sizeBytes, null, partitionColumns,
                columns, partitions.stream()
                        .map(partition -> toPartition(partition, partitionColumns)).toList());
    }

    private CatalogAssets.Column toColumn(FieldSchema field, int ordinal, boolean partitionKey) {
        String rawType = field.getType();
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
        return new CatalogAssets.Column(field.getName(), ordinal, rawType,
                canonical(rawType), precision, scale, true, false, partitionKey,
                field.getComment());
    }

    CatalogAssets.Partition toPartition(Partition partition, List<String> partitionColumns) {
        String path = partition.getSd() == null ? null : partition.getSd().getLocation();
        String valuesJson = valuesJson(partitionColumns, partition.getValues());
        Map<String, String> params = partition.getParameters() == null
                ? Map.of() : partition.getParameters();
        Long fileCount = parseLong(params.get("numFiles"));
        Long sizeBytes = parseLong(params.get("totalSize"));
        Long rowCount = parseLong(params.get("numRows"));
        Instant lastModified = null;
        Long epoch = parseLong(params.get("transient_lastDdlTime"));
        if (epoch != null) {
            lastModified = Instant.ofEpochSecond(epoch);
        }
        return new CatalogAssets.Partition(path == null ? valuesJson : path, valuesJson,
                fileCount, sizeBytes, lastModified);
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
            case "timestamp with local time zone" -> CanonicalType.TIMESTAMP_TZ;
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

    /** 只取根因消息，避免把 Thrift/Hadoop 的完整堆栈回传给用户。 */
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

    private HmsConfig parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Hive Metastore 配置不能为空");
        }
        try {
            Map<String, Object> value = objectMapper.readValue(configJson,
                    new TypeReference<>() {
                    });
            List<String> uris = stringList(value.get("uris"));
            return new HmsConfig(uris,
                    stringValue(value.get("warehouse")),
                    stringList(value.get("includeDatabases")),
                    stringList(value.get("excludeDatabases")),
                    stringValue(value.get("kerberosPrincipal")),
                    stringValue(value.get("keytabPath")),
                    intValue(value.get("timeoutMs"), 10_000));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "Hive Metastore 配置不是合法 JSON", ex.getMessage(), ex);
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

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record HmsConfig(List<String> uris, String warehouse, List<String> includeDatabases,
                             List<String> excludeDatabases, String kerberosPrincipal,
                             String keytabPath, int timeoutMs) {
        int timeoutSeconds() {
            return Math.max(1, timeoutMs / 1000);
        }
    }
}
