/**
 * 凭据 —— 对应 pf_credential。
 *
 * 凭据只存在于 Platform Space,数据源持有的是 credentialId(不可解密的引用)。
 * 因此这里的 Credential 类型<b>没有</b> payload 字段,连密文也没有:
 * 后端的 CredentialView 在类型上就不存在那个分量,前端照抄这个事实。
 */

/** 与 pf_credential.auth_type 一致。 */
export type CredentialAuthType = 'NONE' | 'BASIC' | 'TOKEN' | 'PASSWORD'

export const CREDENTIAL_AUTH_TYPE_LABELS: Record<CredentialAuthType, string> = {
  NONE: '无认证',
  BASIC: '基础认证',
  TOKEN: 'Token 认证',
  PASSWORD: '数据库口令',
}

export interface Credential {
  id: string
  name: string
  authType: CredentialAuthType
  description: string | null
  createdAt: string
  updatedAt: string
}

/**
 * 新建 / 编辑凭据的表单载荷。
 *
 * secret 留空在编辑场景下表示<b>保持原口令不变</b>,而不是改成空口令 ——
 * 混同两者会让用户改个描述就意外清掉了口令。
 */
export interface CredentialForm {
  name: string
  authType: CredentialAuthType
  username?: string
  secret?: string
  description?: string
}
