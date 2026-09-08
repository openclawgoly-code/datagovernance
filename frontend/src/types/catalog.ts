/**
 * 目录(库/模式/表/列/文件)结构模型。
 * 与 dg-data-spi 的 com.datagov.data.spi.catalog.CatalogModel 逐字段对应。
 */

/** 与 CanonicalType.java 枚举值逐一对应,规范化类型系统,P2 异构建表的中枢。 */
export type CanonicalType =
  | 'BOOLEAN'
  | 'TINYINT'
  | 'SMALLINT'
  | 'INT'
  | 'BIGINT'
  | 'FLOAT'
  | 'DOUBLE'
  | 'DECIMAL'
  | 'CHAR'
  | 'VARCHAR'
  | 'TEXT'
  | 'DATE'
  | 'TIME'
  | 'TIMESTAMP'
  | 'TIMESTAMP_TZ'
  | 'BINARY'
  | 'VARBINARY'
  | 'BLOB'
  | 'JSON'
  | 'ARRAY'
  | 'UNKNOWN'

export type TableKind = 'TABLE' | 'VIEW' | 'MATERIALIZED_VIEW' | 'EXTERNAL_TABLE' | 'OTHER'

export interface DatabaseInfo {
  name: string
  charset: string | null
  collation: string | null
  comment: string | null
}

/** 模式。MySQL/Doris 这类无独立 schema 层的引擎,后端可能返回与库同名的占位模式——
 *  但前端是否画这一层完全由 ConnectorCapabilities.hasSchemaLevel 决定,不看这里的数据。 */
export interface SchemaInfo {
  name: string
  owner: string | null
  comment: string | null
}

export interface TableInfo {
  name: string
  kind: TableKind
  comment: string | null
  approximateRowCount: number | null
  sizeInBytes: number | null
  createdAt: string | null
}

export interface ColumnInfo {
  name: string
  /** 目标端原始类型名(如 NUMBER(10,2)),排查类型映射错误时唯一的证据,必须原样展示。 */
  rawType: string
  canonicalType: CanonicalType
  precision: number | null
  scale: number | null
  nullable: boolean
  primaryKey: boolean
  defaultValue: string | null
  comment: string | null
  ordinalPosition: number
}

export interface FileEntry {
  name: string
  path: string
  directory: boolean
  sizeInBytes: number
  lastModified: string | null
}

/** 一次目录浏览的结果;按层级只有其中一个数组非空,其余为空数组。 */
export interface CatalogPage {
  databases: DatabaseInfo[]
  schemas: SchemaInfo[]
  tables: TableInfo[]
  columns: ColumnInfo[]
  files: FileEntry[]
}

/** GET /datasources/{id}/catalog 的查询参数,与 CatalogPath 的四个坐标字段对应。 */
export interface CatalogQuery {
  database?: string
  schema?: string
  table?: string
  path?: string
  refresh?: boolean
}
