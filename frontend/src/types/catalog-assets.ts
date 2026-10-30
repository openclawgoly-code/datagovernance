/** 数据目录(P10.1/P10.2):规范化 catalog 资产。 */
export type CatalogSourceType = 'JDBC' | 'PARQUET_DIRECTORY' | 'HIVE_METASTORE' | 'GLUE'
  | 'ICEBERG_REST' | 'ENGINE_CATALOG'

export interface CatalogSource {
  id: string
  name: string
  sourceType: CatalogSourceType
  dataSourceId: string
  status: string
  lastRefreshedAt: string | null
  createdAt: string
  createdBy: string | null
}

export interface CatalogDataset {
  id: string
  sourceId: string
  namespaceId: string | null
  name: string
  qualifiedName: string
  kind: string
  format: string | null
  comment: string | null
  schemaHash: string | null
  columnCount: number | null
  rowCount?: number | null
  sizeBytes?: number | null
  location?: string | null
  propertiesJson?: string | null
  lastModifiedAt?: string | null
  updatedAt: string
}

export interface CatalogColumn {
  id: string
  name: string
  ordinal: number
  rawType: string | null
  canonicalType: string | null
  precision: number | null
  scale: number | null
  nullable: boolean | null
  primaryKey: boolean | null
  partitionKey: boolean | null
  comment: string | null
}

export interface CatalogPartition {
  id: string
  path: string
  valuesJson: string | null
  fileCount: number | null
  sizeBytes: number | null
  rowCount?: number | null
  lastModifiedAt: string | null
}

export interface CatalogDatasetDetail {
  dataset: CatalogDataset
  columns: CatalogColumn[]
  partitions: CatalogPartition[]
}

export interface CatalogRefreshResult {
  runId: string
  status: string
  namespaces: number
  datasets: number
  columns: number
  durationMillis: number
}

export interface CreateCatalogSourceRequest {
  name: string
  sourceType: CatalogSourceType
  dataSourceId: string
  configJson?: string
}
