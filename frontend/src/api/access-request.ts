import { http } from './request'
import type {
  AccessGrant,
  AccessRequest,
  CreateAccessRequest,
} from '@/types/access-request'

/** 敏感数据临时访问申请（P2）。 */
export const accessRequestApi = {
  create(payload: CreateAccessRequest): Promise<AccessRequest> {
    return http.post<AccessRequest>('/metadata/access-requests', payload)
  },

  list(scope: 'mine' | 'pending', page = 1, size = 50): Promise<AccessRequest[]> {
    return http.get<AccessRequest[]>('/metadata/access-requests', { scope, page, size })
  },

  approve(id: string, note?: string): Promise<AccessRequest> {
    return http.post<AccessRequest>(`/metadata/access-requests/${id}/approve`, { note })
  },

  reject(id: string, note?: string): Promise<AccessRequest> {
    return http.post<AccessRequest>(`/metadata/access-requests/${id}/reject`, { note })
  },

  cancel(id: string): Promise<AccessRequest> {
    return http.post<AccessRequest>(`/metadata/access-requests/${id}/cancel`)
  },

  grants(): Promise<AccessGrant[]> {
    return http.get<AccessGrant[]>('/metadata/access-grants')
  },

  revoke(grantId: string): Promise<AccessGrant> {
    return http.post<AccessGrant>(`/metadata/access-grants/${grantId}/revoke`)
  },
}
