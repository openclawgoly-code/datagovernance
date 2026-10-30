import { http } from './request'
import type {
  AssetGovernance,
  BusinessDomain,
  DomainForm,
  GovernanceMember,
  GovernanceUpdateRequest,
} from '@/types/catalog-governance'

/** 资产治理（P2）：业务域与资产 owner/认证。 */
export const governanceApi = {
  getAssetGovernance(datasetId: string): Promise<AssetGovernance> {
    return http.get<AssetGovernance>(`/metadata/catalog/datasets/${datasetId}/governance`)
  },

  updateAssetGovernance(
    datasetId: string,
    payload: GovernanceUpdateRequest,
  ): Promise<AssetGovernance> {
    return http.put<AssetGovernance>(
      `/metadata/catalog/datasets/${datasetId}/governance`,
      payload,
    )
  },

  listDomains(): Promise<BusinessDomain[]> {
    return http.get<BusinessDomain[]>('/metadata/domains')
  },

  createDomain(payload: DomainForm): Promise<BusinessDomain> {
    return http.post<BusinessDomain>('/metadata/domains', payload)
  },

  updateDomain(id: string, payload: DomainForm): Promise<BusinessDomain> {
    return http.put<BusinessDomain>(`/metadata/domains/${id}`, payload)
  },

  removeDomain(id: string): Promise<void> {
    return http.delete<void>(`/metadata/domains/${id}`)
  },

  listMembers(): Promise<GovernanceMember[]> {
    return http.get<GovernanceMember[]>('/metadata/domains/members')
  },
}
