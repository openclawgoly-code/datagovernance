import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  EdgeRequest,
  PublishVersionRequest,
  RegisterRequest,
  RegistryArtifact,
  RegistryEdge,
  RegistryVersion,
} from '@/types/registry'

/**
 * 注册中心(序号 34 的契约第 3、4 条)。
 *
 * 这套接口是给 Intelligence 平台调的 —— 当那个平台建起来时,它在这里注册
 * 产物,而不是自己再建一个注册中心。「不建第二注册中心」是契约的原话。
 */
export const registryApi = {
  list(params: { page: number; size: number; kind?: string; keyword?: string }):
      Promise<PageResult<RegistryArtifact>> {
    return http.get<PageResult<RegistryArtifact>>('/registry/artifacts', params)
  },

  get(id: string): Promise<RegistryArtifact> {
    return http.get<RegistryArtifact>(`/registry/artifacts/${id}`)
  },

  register(payload: RegisterRequest): Promise<RegistryArtifact> {
    return http.post<RegistryArtifact>('/registry/artifacts', payload)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/registry/artifacts/${id}`)
  },

  versions(id: string): Promise<RegistryVersion[]> {
    return http.get<RegistryVersion[]>(`/registry/artifacts/${id}/versions`)
  },

  /** 版本号由平台分配;已发布的版本没有修改接口 */
  publishVersion(id: string, payload: PublishVersionRequest): Promise<RegistryVersion> {
    return http.post<RegistryVersion>(`/registry/artifacts/${id}/versions`, payload)
  },

  edges(params: { fromType?: string; fromId?: string; relation?: string }):
      Promise<RegistryEdge[]> {
    return http.get<RegistryEdge[]>('/registry/edges', params)
  },

  addEdge(payload: EdgeRequest): Promise<RegistryEdge> {
    return http.post<RegistryEdge>('/registry/edges', payload)
  },

  removeEdge(id: string): Promise<void> {
    return http.delete<void>(`/registry/edges/${id}`)
  },
}
