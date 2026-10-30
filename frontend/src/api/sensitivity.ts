import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  SensitivityBatchReviewRequest,
  SensitivityBatchReviewResult,
  SensitivityLabel,
  SensitivityReviewRequest,
  SensitivityRule,
  SensitivityScanResult,
} from '@/types/sensitivity'

export const sensitivityApi = {
  rules(): Promise<SensitivityRule[]> {
    return http.get<SensitivityRule[]>('/metadata/sensitivity/rules')
  },
  labels(params: {
    page: number
    size: number
    level?: string
    status?: string
    datasetId?: string
  }): Promise<PageResult<SensitivityLabel>> {
    return http.get<PageResult<SensitivityLabel>>('/metadata/sensitivity/columns', params)
  },
  scan(datasetId?: string): Promise<SensitivityScanResult> {
    return http.post<SensitivityScanResult>('/metadata/sensitivity/scan', datasetId ? { datasetId } : {})
  },
  review(id: string, payload: SensitivityReviewRequest): Promise<SensitivityLabel> {
    return http.post<SensitivityLabel>(`/metadata/sensitivity/columns/${id}/review`, payload)
  },
  batchReview(payload: SensitivityBatchReviewRequest): Promise<SensitivityBatchReviewResult> {
    return http.post<SensitivityBatchReviewResult>(
      '/metadata/sensitivity/columns/batch-review',
      payload,
    )
  },
}
