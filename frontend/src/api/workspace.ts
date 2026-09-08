import { http } from './request'
import type { PageQuery, PageResult } from '@/types/api'
import type { Workspace, WorkspaceForm, WorkspaceMember } from '@/types/workspace'

export function listWorkspaces(query: PageQuery): Promise<PageResult<Workspace>> {
  return http.get<PageResult<Workspace>>('/workspaces', { ...query })
}

export function createWorkspace(payload: WorkspaceForm): Promise<Workspace> {
  return http.post<Workspace>('/workspaces', payload)
}

export function updateWorkspace(id: string, payload: WorkspaceForm): Promise<Workspace> {
  return http.put<Workspace>(`/workspaces/${id}`, payload)
}

export function deleteWorkspace(id: string): Promise<void> {
  return http.delete<void>(`/workspaces/${id}`)
}

export function listWorkspaceMembers(id: string): Promise<WorkspaceMember[]> {
  return http.get<WorkspaceMember[]>(`/workspaces/${id}/members`)
}

export function addWorkspaceMembers(id: string, userIds: string[]): Promise<void> {
  return http.post<void>(`/workspaces/${id}/members`, { userIds })
}

export function removeWorkspaceMember(id: string, userId: string): Promise<void> {
  return http.delete<void>(`/workspaces/${id}/members/${userId}`)
}
