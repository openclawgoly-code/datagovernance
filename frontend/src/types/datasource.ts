/**
 * 数据源 —— 对应 md_datasource,及 dg-data-spi 里与之配套的连接器契约类型。
 *
 * 全文件没有任何一处按"具体类型"分支的逻辑——这本身就是硬约束的一部分:
 * UI 只允许读 family / capabilities 来决定渲染什么,不允许写
 * `if (type === 'MYSQL')`。把这条规则也写在类型文件里,是为了让后来者
 * 在给 DataSource 加字段时,第一眼就能看到"为什么没有 isMysql 这种字段"。
 */

/** 与 DataSourceType.Family 逐一对应,决定 UI 该渲染哪种表单/浏览器,而不是具体类型名。 */
export type DataSourceFamily = 'RELATIONAL' | 'MPP' | 'FILE' | 'HTTP'

export type DataSourceStatus = 'DRAFT' | 'TESTING' | 'ACTIVE' | 'UNREACHABLE' | 'DISABLED'

/**
 * 连接器能力声明,与 ConnectorCapabilities.java 的 8 个布尔量逐一对应。
 * 前端所有"要不要显示这个字段 / 这一级树"的判断都读这里,不读 type。
 */
export interface ConnectorCapabilities {
  canTestConnection: boolean
  canBrowseCatalog: boolean
  canBrowseFiles: boolean
  hasDatabaseLevel: boolean
  hasSchemaLevel: boolean
  canReadData: boolean
  canWriteData: boolean
  canCreateTable: boolean
}

/** GET /datasource-types 的元素——类型选择下拉的唯一数据来源。 */
export interface DataSourceTypeInfo {
  type: string
  displayName: string
  family: DataSourceFamily
  defaultPort: number
  capabilities: ConnectorCapabilities
}

/** 与 ConnectivityResult.java 逐字段对应。errorCode 是 ErrorCode 枚举的 name(),成功时为 null。 */
export interface ConnectivityResult {
  success: boolean
  latencyMillis: number
  serverVersion: string | null
  message: string
  errorCode: string | null
  detail: string | null
}

/** 数据源实体。password 字段在这里永不出现——它只活在 password_enc 密文里,
 *  连成功创建后的响应都不应该回显明文或密文,前端也就没有理由声明这个字段。 */
export interface DataSource {
  id: string
  workspaceId: string
  name: string
  /** DataSourceType 枚举名,如 'MYSQL' —— 仅用于展示 displayName 与下发请求,不参与 UI 结构分支。 */
  type: string
  family: DataSourceFamily
  status: DataSourceStatus
  description: string | null

  host: string | null
  port: number | null
  databaseName: string | null
  username: string | null
  /** 驱动扩展参数,后端把 properties_json 解析为对象再下发。 */
  properties: Record<string, string> | null
  jdbcUrlOverride: string | null
  baseUrl: string | null
  credentialId: string | null
  connectTimeoutMs: number
  readTimeoutMs: number

  lastTestAt: string | null
  lastTestSuccess: boolean | null
  lastTestMessage: string | null
  lastTestLatencyMs: number | null

  version: number
  createdAt: string
  createdBy: string | null
  updatedAt: string
  updatedBy: string | null
}

/** 创建 / 编辑 / 未保存试连(POST /datasources/test)共用的表单载荷。 */
export interface DataSourceForm {
  name: string
  type: string
  description?: string
  host?: string
  port?: number
  databaseName?: string
  username?: string
  /** 仅新建或需要修改口令时携带;编辑时留空表示沿用已保存的密码。 */
  password?: string
  properties?: Record<string, string>
  jdbcUrlOverride?: string
  baseUrl?: string
  credentialId?: string
  connectTimeoutMs?: number
  readTimeoutMs?: number
}

export interface DataSourceQuery {
  page: number
  size: number
  type?: string
  keyword?: string
}

/** 数据源版本历史条目——对应 md_datasource_version,GET /datasources/{id}/versions。 */
export interface DataSourceVersion {
  id: string
  datasourceId: string
  version: number
  changeType: 'CREATED' | 'UPDATED' | 'STATUS_CHANGED' | 'DISABLED'
  changeSummary: string | null
  changedAt: string
  changedBy: string | null
}
