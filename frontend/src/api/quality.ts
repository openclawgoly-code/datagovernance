import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  QualityRule,
  QualityRuleRequest,
  QualityRun,
  QualitySummary,
} from '@/types/quality'

/** 资产质量（P2）。 */
export const qualityApi = {
  listRules(params: {
    page: number
    size: number
    datasetId?: string
    ruleType?: string
  }): Promise<PageResult<QualityRule>> {
    return http.get<PageResult<QualityRule>>('/metadata/quality/rules', params)
  },

  createRule(payload: QualityRuleRequest): Promise<QualityRule> {
    return http.post<QualityRule>('/metadata/quality/rules', payload)
  },

  updateRule(id: string, payload: QualityRuleRequest): Promise<QualityRule> {
    return http.put<QualityRule>(`/metadata/quality/rules/${id}`, payload)
  },

  removeRule(id: string): Promise<void> {
    return http.delete<void>(`/metadata/quality/rules/${id}`)
  },

  runRule(id: string): Promise<QualityRun> {
    return http.post<QualityRun>(`/metadata/quality/rules/${id}/run`)
  },

  runDataset(datasetId: string): Promise<QualityRun[]> {
    return http.post<QualityRun[]>(`/metadata/quality/datasets/${datasetId}/run`)
  },

  summary(datasetId: string): Promise<QualitySummary> {
    return http.get<QualitySummary>(`/metadata/quality/datasets/${datasetId}/summary`)
  },

  listRuns(params: {
    page: number
    size: number
    status?: string
    ruleType?: string
    datasetId?: string
    passed?: boolean
    latest?: boolean
  }): Promise<PageResult<QualityRun>> {
    return http.get<PageResult<QualityRun>>('/metadata/quality/runs', params)
  },
}
