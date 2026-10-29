import { http } from './request'
import type {
  InsightAnswer,
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
  queries(params: { page?: number; size?: number } = {}) {
    return http.get<InsightPage<InsightQueryLog>>('/insight/queries', params)
  },
}
