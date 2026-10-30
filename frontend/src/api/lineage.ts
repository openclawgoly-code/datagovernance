import { http } from './request'
import type { LineageGraph, LineageNode } from '@/types/lineage'

/** 字段级血缘。所有查询都走平台权限与 workspace。 */
export const lineageApi = {
  node(id: string): Promise<LineageNode> {
    return http.get<LineageNode>(`/metadata/lineage/nodes/${id}`)
  },
  upstream(columnId: string, depth = 3): Promise<LineageGraph> {
    return http.get<LineageGraph>(`/metadata/lineage/columns/${columnId}/upstream`, { depth })
  },
  downstream(columnId: string, depth = 3): Promise<LineageGraph> {
    return http.get<LineageGraph>(`/metadata/lineage/columns/${columnId}/downstream`, { depth })
  },
  impact(columnId: string, depth = 5): Promise<LineageGraph> {
    return http.get<LineageGraph>(`/metadata/lineage/columns/${columnId}/impact`, { depth })
  },
}
