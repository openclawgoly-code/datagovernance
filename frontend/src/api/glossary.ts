import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  AssetGlossaryTerm,
  GlossaryBinding,
  GlossaryBindingRequest,
  GlossaryTerm,
  GlossaryTermRequest,
} from '@/types/glossary'

/** 业务术语（P2）：术语、发布、资产/字段绑定。 */
export const glossaryApi = {
  listTerms(params: {
    page: number
    size: number
    keyword?: string
    domainId?: string
    status?: string
  }): Promise<PageResult<GlossaryTerm>> {
    return http.get<PageResult<GlossaryTerm>>('/metadata/glossary/terms', params)
  },

  createTerm(payload: GlossaryTermRequest): Promise<GlossaryTerm> {
    return http.post<GlossaryTerm>('/metadata/glossary/terms', payload)
  },

  updateTerm(id: string, payload: GlossaryTermRequest): Promise<GlossaryTerm> {
    return http.put<GlossaryTerm>(`/metadata/glossary/terms/${id}`, payload)
  },

  publish(id: string): Promise<GlossaryTerm> {
    return http.post<GlossaryTerm>(`/metadata/glossary/terms/${id}/publish`)
  },

  deprecate(id: string): Promise<GlossaryTerm> {
    return http.post<GlossaryTerm>(`/metadata/glossary/terms/${id}/deprecate`)
  },

  listBindings(termId: string): Promise<GlossaryBinding[]> {
    return http.get<GlossaryBinding[]>(`/metadata/glossary/terms/${termId}/bindings`)
  },

  createBinding(termId: string, payload: GlossaryBindingRequest): Promise<GlossaryBinding> {
    return http.post<GlossaryBinding>(`/metadata/glossary/terms/${termId}/bindings`, payload)
  },

  deleteBinding(termId: string, bindingId: string): Promise<void> {
    return http.delete<void>(`/metadata/glossary/terms/${termId}/bindings/${bindingId}`)
  },

  assetGlossary(datasetId: string): Promise<AssetGlossaryTerm[]> {
    return http.get<AssetGlossaryTerm[]>(`/metadata/catalog/datasets/${datasetId}/glossary`)
  },
}
