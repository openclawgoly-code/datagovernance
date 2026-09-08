package com.datagov.data.spi.catalog;

/**
 * 目录定位路径。用一个对象承载"浏览到哪一层"的坐标,
 * 避免每个方法签名都拖着 (database, schema, table) 三个可空字符串。
 *
 * @param database 库;为 null 表示尚未下钻到库
 * @param schema   模式;为 null 表示尚未下钻到模式
 * @param table    表;为 null 表示尚未下钻到表
 * @param path     文件类数据源的目录路径,与上面三个字段互斥使用
 */
public record CatalogPath(
        String database,
        String schema,
        String table,
        String path
) {

    public static CatalogPath root() {
        return new CatalogPath(null, null, null, null);
    }

    public static CatalogPath ofDatabase(String database) {
        return new CatalogPath(database, null, null, null);
    }

    public static CatalogPath ofSchema(String database, String schema) {
        return new CatalogPath(database, schema, null, null);
    }

    public static CatalogPath ofTable(String database, String schema, String table) {
        return new CatalogPath(database, schema, table, null);
    }

    public static CatalogPath ofPath(String path) {
        return new CatalogPath(null, null, null, path);
    }

    /** 当前定位到的层级,决定 Gateway 该返回下一层的什么内容。 */
    public Level level() {
        if (path != null) {
            return Level.PATH;
        }
        if (table != null) {
            return Level.TABLE;
        }
        if (schema != null) {
            return Level.SCHEMA;
        }
        if (database != null) {
            return Level.DATABASE;
        }
        return Level.ROOT;
    }

    public enum Level {
        ROOT,
        DATABASE,
        SCHEMA,
        TABLE,
        PATH
    }

    /** 便于日志与错误信息的可读形式。 */
    public String display() {
        if (path != null) {
            return path;
        }
        StringBuilder sb = new StringBuilder();
        if (database != null) sb.append(database);
        if (schema != null) sb.append(sb.isEmpty() ? "" : ".").append(schema);
        if (table != null) sb.append(sb.isEmpty() ? "" : ".").append(table);
        return sb.isEmpty() ? "<root>" : sb.toString();
    }
}
