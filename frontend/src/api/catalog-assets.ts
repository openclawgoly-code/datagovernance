import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  CatalogDataset,
  CatalogDatasetDetail,
  CatalogRefreshResult,
  CatalogSource,
  CatalogColumn,
  CatalogPartition,
  CreateCatalogSourceRequest,
} from '@/types/catalog-assets'

/** 数据目录(P10.1/P10.2):目录来源、数据集、字段、分区。 */
export const catalogAssetApi = {
  listSources(): Promise<CatalogSource[]> {
    return http.get<CatalogSource[]>('/metadata/catalog/sources')
  },

  createSource(payload: CreateCatalogSourceRequest): Promise<CatalogSource> {
    return http.post<CatalogSource>('/metadata/catalog/sources', payload)
  },

  removeSource(id: string): Promise<void> {
    return http.delete<void>(`/metadata/catalog/sources/${id}`)
  },

  refreshSource(id: string): Promise<CatalogRefreshResult> {
    return http.post<CatalogRefreshResult>(`/metadata/catalog/sources/${id}/refresh`)
  },

  listDatasets(
    sourceId: string,
    params: { page: number; size: number; keyword?: string },
  ): Promise<PageResult<CatalogDataset>> {
    return http.get<PageResult<CatalogDataset>>(
      `/metadata/catalog/sources/${sourceId}/datasets`,
      params,
    )
  },

  getDataset(id: string): Promise<CatalogDatasetDetail> {
    return http.get<CatalogDatasetDetail>(`/metadata/catalog/datasets/${id}`)
  },

  listColumns(id: string): Promise<CatalogColumn[]> {
    return http.get<CatalogColumn[]>(`/metadata/catalog/datasets/${id}/columns`)
  },

  listPartitions(id: string): Promise<CatalogPartition[]> {
    return http.get<CatalogPartition[]>(`/metadata/catalog/datasets/${id}/partitions`)
  },
}
