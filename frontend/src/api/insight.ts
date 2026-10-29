import { http } from './request'
import type {
  InsightAnswer,
  InsightDashboard,
  InsightDashboardDetail,
  InsightDashboardItem,
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
  queries(params: { page?: number; size?: number } = {}) {
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
}
