/**
 * 用户 —— 对应 pf_user。password_hash 是单向散列,任何情况下都不会、
 * 也不需要出现在前端类型里。
 */
export type UserStatus = 'ACTIVE' | 'DISABLED'

export interface User {
  id: string
  username: string
  displayName: string | null
  email: string | null
  phone: string | null
  status: UserStatus
  platformAdmin: boolean
  lastLoginAt: string | null
  /** 当前空间下已分配的角色 id 列表,用于"用户管理"页回显勾选状态。 */
  roleIds?: string[]
  createdAt: string
  updatedAt: string
}

export interface UserForm {
  username: string
  /** 仅新建时必填;编辑时留空表示不修改密码。 */
  password?: string
  displayName?: string
  email?: string
  phone?: string
  status?: UserStatus
}

export interface ResetPasswordRequest {
  newPassword: string
}

export interface AssignRolesRequest {
  roleIds: string[]
}
