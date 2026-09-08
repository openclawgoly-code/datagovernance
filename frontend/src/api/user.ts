import { http } from './request'
import type { PageResult } from '@/types/api'
import type { User, UserForm, ResetPasswordRequest, AssignRolesRequest } from '@/types/user'

/**
 * 契约缺口:GET /users 的返回形状未在接口清单里明确标注 PageResult<>
 * (只有 workspaces / datasources 明确写了)。用户是平台级资源、规模可能
 * 较大,这里按分页实现;若后端最终给的是普通数组,只需要改这一处签名与
 * UserListView 里消费 total 的地方。
 */
export interface UserQuery {
  page: number
  size: number
  keyword?: string
}

export function listUsers(query: UserQuery): Promise<PageResult<User>> {
  return http.get<PageResult<User>>('/users', { ...query })
}

export function createUser(payload: UserForm): Promise<User> {
  return http.post<User>('/users', payload)
}

export function updateUser(id: string, payload: UserForm): Promise<User> {
  return http.put<User>(`/users/${id}`, payload)
}

export function deleteUser(id: string): Promise<void> {
  return http.delete<void>(`/users/${id}`)
}

export function resetPassword(id: string, payload: ResetPasswordRequest): Promise<void> {
  return http.post<void>(`/users/${id}/reset-password`, payload)
}

/** 在"当前空间"(X-Workspace-Id)下为该用户分配角色。 */
export function assignUserRoles(id: string, payload: AssignRolesRequest): Promise<void> {
  return http.post<void>(`/users/${id}/roles`, payload)
}
