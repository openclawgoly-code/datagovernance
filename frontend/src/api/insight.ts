import { http } from './request'
import type {
  InsightAnswer,
  InsightDashboard,
  InsightDashboardDetail,
  InsightAclCandidate,
  InsightDashboardAcl,
  InsightDashboardItem,
  InsightDashboardItemUpdate,
  InsightDashboardRun,
  InsightDashboardVersion,
  InsightSupersonicMapping,
  InsightDataset,
  InsightPage,
  InsightQueryLog,
} from '@/types/insight'

export const insightApi = {
  datasets() {
    return http.get<InsightDataset[]>('/insight/datasets')
  },
  ask(payload: { question: string; dataSetId?: number }) {
    return http.post<InsightAnswer>('/insight/ask', payload)
  },
  exportCsv(payload: { question: string; dataSetId?: number }) {
    return http.postBlob('/insight/export', payload)
  },
  exportXlsx(payload: { question: string; dataSetId?: number }) {
    return http.postBlob('/insight/export/xlsx', payload)
  },
  queries(params: {
    page?: number
    size?: number
    keyword?: string
    succeeded?: boolean
    username?: string
  } = {}) {
    return http.get<InsightPage<InsightQueryLog>>('/insight/queries', params)
  },
  dashboards() {
    return http.get<InsightDashboard[]>('/insight/dashboards')
  },
  dashboard(id: string) {
    return http.get<InsightDashboardDetail>(`/insight/dashboards/${id}`)
  },
  createDashboard(payload: { name: string; description?: string }) {
    return http.post<InsightDashboard>('/insight/dashboards', payload)
  },
  addDashboardItem(id: string, payload: {
    question: string
    datasetId?: number
    chartType?: string
  }) {
    return http.post<InsightDashboardItem>(`/insight/dashboards/${id}/items`, payload)
  },
  deleteDashboard(id: string) {
    return http.delete<void>(`/insight/dashboards/${id}`)
  },
  deleteDashboardItem(id: string, itemId: string) {
    return http.delete<void>(`/insight/dashboards/${id}/items/${itemId}`)
  },
  runDashboard(id: string) {
    return http.post<InsightDashboardRun>(`/insight/dashboards/${id}/run`)
  },
  dashboardVersions(id: string) {
    return http.get<InsightDashboardVersion[]>(`/insight/dashboards/${id}/versions`)
  },
  dashboardAcl(id: string) {
    return http.get<InsightDashboardAcl[]>(`/insight/dashboards/${id}/acl`)
  },
  addDashboardAcl(id: string, payload: InsightDashboardAcl) {
    return http.post<InsightDashboardAcl>(`/insight/dashboards/${id}/acl`, payload)
  },
  removeDashboardAcl(id: string, username: string) {
    return http.delete<void>(`/insight/dashboards/${id}/acl/${encodeURIComponent(username)}`)
  },
  dashboardAclCandidates(id: string, keyword?: string) {
    return http.get<InsightAclCandidate[]>(`/insight/dashboards/${id}/acl/candidates`, {
      keyword,
    })
  },
  updateDashboardItem(id: string, itemId: string, payload: InsightDashboardItemUpdate) {
    return http.patch<InsightDashboardItem>(
      `/insight/dashboards/${id}/items/${itemId}`,
      payload,
    )
  },
  mappings() {
    return http.get<InsightSupersonicMapping[]>('/insight/mappings')
  },
  saveMapping(payload: {
    platformUsername: string
    supersonicUsername: string
    credentialId: string
  }) {
    return http.post<InsightSupersonicMapping>('/insight/mappings', payload)
  },
  deleteMapping(id: string) {
    return http.delete<void>(`/insight/mappings/${id}`)
  },
}
