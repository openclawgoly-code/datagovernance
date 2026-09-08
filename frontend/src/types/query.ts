import type { CanonicalType } from './catalog'

/** 自定义 SQL 查询(功能 7)。与后端 SqlQuery.Request / SqlQuery.Result 对应。 */

export interface SqlQueryRequest {
  sql: string
  /** 行数上限。后端有硬上限,超过会被夹到最大值 */
  maxRows?: number
  timeoutSeconds?: number
}

export interface SqlResultColumn {
  name: string
  rawType: string
  canonicalType: CanonicalType
}

export interface SqlQueryResult {
  columns: SqlResultColumn[]
  /** 单元格一律是字符串或 null —— 后端不做类型转换,展示层也不需要 */
  rows: (string | null)[][]
  rowCount: number
  /** 因达到行数上限而截断。为 true 时界面必须显式提示,否则用户会以为这就是全部 */
  truncated: boolean
  elapsedMillis: number
}
