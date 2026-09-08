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

/**
 * 数据源生命周期状态,与 SPACE-MODEL.md E.1 的六态一致。
 *
 * DRAFT 与 UNREACHABLE 都表示"现在连不上",但成因与用户该做的事完全不同:
 *   DRAFT       从未验证成功过,或刚改完配置 —— 配置本身可能就是错的,去检查主机端口账号
 *   UNREACHABLE 曾经可用,被周期探测发现连不上 —— 配置多半没问题,去找网管或 DBA
 * UI 必须把这个区别表达出来,否则等于把判断推给用户自己猜。
 */
export type DataSourceStatus =
  | 'DRAFT'
  | 'TESTING'
  | 'AVAILABLE'
  | 'UNREACHABLE'
  | 'DISABLED'
  | 'ARCHIVED'

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
  /** 是否 JDBC 类型。FTP/SFTP/RestAPI 为 false。 */
  jdbc: boolean
  capabilities: ConnectorCapabilities
}

/**
 * 附加节点(功能2:Doris / StarRocks 多 FE)。
 *
 * 只对 family === 'MPP' 有意义,后端对其它 family 会直接报错而不是静默忽略。
 * 主节点仍走 host/port —— 节点列表里放的是<b>额外</b>的 FE,不含主节点本身。
 */
export interface ConnectionNode {
  host: string
  port: number
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

/**
 * 数据源实体。
 *
 * 这里没有任何口令字段,而且不该有:凭据只存在于 Platform Space,
 * 数据源持有的是 credentialId —— 一个不可解密的引用。后端的 DataSourceView
 * 在类型上就没有 password 分量,前端照抄这个事实即可。
 */
export interface DataSource {
  id: string
  name: string
  /** DataSourceType 枚举名,如 'MYSQL' —— 仅用于展示与下发请求,不参与 UI 结构分支。 */
  type: string
  typeDisplayName: string
  family: DataSourceFamily
  status: DataSourceStatus
  description: string | null

  /** 所属目录(功能5);null 表示未分类 */
  catalogId: string | null

  /** 周期连通性检查(功能6) */
  probeEnabled: boolean | null
  probeIntervalMinutes: number | null
  lastProbeAt: string | null

  host: string | null
  port: number | null
  databaseName: string | null
  username: string | null
  /** 驱动扩展参数,后端把 properties_json 解析为对象再下发。 */
  properties: Record<string, string> | null
  /** 附加节点(功能2);非 MPP 类型恒为空数组 */
  nodes: ConnectionNode[] | null
  jdbcUrlOverride: string | null
  baseUrl: string | null
  /** 指向 pf_credential.id,唯一的凭据通路 */
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

/** 认证方式,与 pf_credential.auth_type 一致。 */
export type AuthType = 'NONE' | 'BASIC' | 'TOKEN' | 'PASSWORD'

/**
 * 表单里填的口令。
 *
 * 仅在提交时传输,任何响应都不会返回它,前端也不得缓存。
 * 后端收到后会先在 Platform 创建或更新一条 Credential,数据源只保存其引用。
 */
export interface InlineSecret {
  authType: AuthType
  username?: string
  secret?: string
}

/** 创建 / 编辑 / 未保存试连(POST /datasources/test)共用的表单载荷。 */
export interface DataSourceForm {
  name: string
  type: string
  description?: string
  catalogId?: string
  host?: string
  port?: number
  databaseName?: string
  username?: string
  properties?: Record<string, string>
  /** 附加节点(功能2);仅 MPP 类型可填 */
  nodes?: ConnectionNode[]
  jdbcUrlOverride?: string
  baseUrl?: string
  /** 复用已有凭据 */
  credentialId?: string
  /** 新建凭据。编辑时留空表示沿用已保存的口令,而不是改成空口令。 */
  inlineSecret?: InlineSecret
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

/**
 * 数据源目录树节点(功能5)。
 *
 * 注意与 catalog.ts 里的库表结构区分:这个是人工维护的组织结构(文件夹),
 * 那个是从目标库探测来的库表结构。需求文档里两者都叫"目录"。
 */
export interface DataSourceCatalogNode {
  id: string
  parentId: string | null
  name: string
  description: string | null
  sortOrder: number | null
  /** 该目录直接挂载的数据源数量,不含子目录 */
  dataSourceCount: number
  children: DataSourceCatalogNode[]
}

export interface DataSourceCatalogTree {
  nodes: DataSourceCatalogNode[]
  uncategorizedCount: number
}
