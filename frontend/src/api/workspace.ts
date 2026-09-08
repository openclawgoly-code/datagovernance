import { http } from './request'
import type { Workspace, WorkspaceForm } from '@/types/workspace'

/**
 * 空间(租户)管理 —— 功能 28。
 *
 * 注意 GET /workspaces 返回的是<b>当前用户可访问的空间数组</b>,不是分页:
 * 一个用户能进的空间数量以个位数计,分页没有意义,而且这个接口在每次
 * 登录与切换空间时都要调,少一层信封更省事。
 */
export const workspaceApi = {
  listAccessible(): Promise<Workspace[]> {
    return http.get<Workspace[]>('/workspaces')
  },

  create(payload: WorkspaceForm): Promise<Workspace> {
    return http.post<Workspace>('/workspaces', payload)
  },

  update(id: string, payload: WorkspaceForm): Promise<Workspace> {
    return http.put<Workspace>(`/workspaces/${id}`, payload)
  },

  /** 授权用户的 ID 列表(功能 28 的「授权访问用户」) */
  listMemberIds(id: string): Promise<string[]> {
    return http.get<string[]>(`/workspaces/${id}/members`)
  },

  addMembers(id: string, userIds: string[]): Promise<void> {
    return http.post<void>(`/workspaces/${id}/members`, { userIds })
  },

  removeMember(id: string, userId: string): Promise<void> {
    return http.delete<void>(`/workspaces/${id}/members/${userId}`)
  },

  /**
   * 轮换鉴权密钥。
   *
   * 返回的明文 secretKey 是它<b>唯一一次</b>出现的机会,之后库里只有密文。
   * 调用方有责任把它展示给用户并提示妥善保存。
   */
  rotateSecret(id: string): Promise<string> {
    return http.post<string>(`/workspaces/${id}/rotate-secret`)
  },
}
