/**
 * 接口认证凭据 —— 对应 pf_credential。
 *
 * 硬约束:绝不展示或缓存凭据内容。payload_enc 是密文,列表/详情响应里
 * 根本不应该有这个字段,所以 Credential 类型只保留名称/类型/描述这些
 * "元数据",没有、也不会有任何一个字段能还原出认证内容。
 *
 * 契约缺口:payload 在不同 authType 下具体需要哪些字段,V1 建表注释只写了
 * "该认证方式所需的全部字段"(密文内部结构),没有定义明文请求 DTO 的形状。
 * 下面的 CredentialPayload 是按 authType 常规语义给出的最小合理猜测,
 * 后端落地时若字段名不同,只需改这一处类型 + CredentialFormDialog 的表单项。
 */
export type CredentialAuthType = 'NONE' | 'BASIC' | 'BEARER' | 'API_KEY' | 'OAUTH2_CLIENT'

export interface Credential {
  id: string
  workspaceId: string
  name: string
  authType: CredentialAuthType
  description: string | null
  createdAt: string
  updatedAt: string
}

export interface CredentialPayloadBasic {
  username: string
  password: string
}
export interface CredentialPayloadBearer {
  token: string
}
export interface CredentialPayloadApiKey {
  headerName: string
  apiKey: string
}
export interface CredentialPayloadOAuth2Client {
  tokenUrl: string
  clientId: string
  clientSecret: string
  scope?: string
}

export interface CredentialForm {
  name: string
  authType: CredentialAuthType
  description?: string
  /**
   * 认证载荷。编辑已有凭据时留空 = 不修改已保存的内容——因为后端从不
   * 回显 payload,表单也就没有"原值"可供比较,只能靠"是否填写"来判断
   * 是否要覆盖。
   */
  payload?:
    | CredentialPayloadBasic
    | CredentialPayloadBearer
    | CredentialPayloadApiKey
    | CredentialPayloadOAuth2Client
    | Record<string, never>
}
