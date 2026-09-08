import { http } from './request'
import type { User, UserForm } from '@/types/user'

/** 用户管理 —— 功能 30。 */
export const userApi = {
  /** 后端返回数组而非分页:P1 的用户规模用不上分页 */
  list(keyword?: string): Promise<User[]> {
    return http.get<User[]>('/users', { keyword })
  },

  create(payload: UserForm & { platformAdmin?: boolean }): Promise<User> {
    return http.post<User>('/users', payload)
  },

  update(id: string, payload: Pick<UserForm, 'displayName' | 'email' | 'phone'>): Promise<User> {
    return http.put<User>(`/users/${id}`, payload)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/users/${id}`)
  },

  resetPassword(id: string, newPassword: string): Promise<void> {
    return http.post<void>(`/users/${id}/reset-password`, { newPassword })
  },

  /** 启用/停用。后端拒绝停用最后一个平台管理员 —— 那会把所有人锁在外面。 */
  setStatus(id: string, enabled: boolean): Promise<void> {
    return http.post<void>(`/users/${id}/status?enabled=${enabled}`)
  },

  /** 该用户在当前空间下的角色。同一用户在不同空间可以有不同角色。 */
  listRoleIds(id: string): Promise<string[]> {
    return http.get<string[]>(`/users/${id}/roles`)
  },

  assignRoles(id: string, roleIds: string[]): Promise<void> {
    return http.post<void>(`/users/${id}/roles`, { roleIds })
  },
}
