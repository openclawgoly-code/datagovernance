/**
 * 与 dg-common ApiResponse<T> / PageResult<T> 严格对应。
 *
 * 字段名保持 camelCase —— 后端 application.yml 只开了
 * mybatis-plus.map-underscore-to-camel-case(数据库列到 Java 字段),
 * 没有配置 Jackson 的命名策略,序列化时 Java 字段名原样输出,
 * 因此 JSON 与 Java record 分量名一致,前端类型也应一致。
 */
export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string | null
  data: T
  traceId: string | null
  timestamp: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 列表类查询的通用分页入参。 */
export interface PageQuery {
  page: number
  size: number
}
