/** 资产治理（P2）：业务域、负责人、管家与认证状态。 */
export type CertificationStatus = 'NONE' | 'CERTIFIED' | 'DEPRECATED'

export interface BusinessDomain {
  id: string
  code: string
  name: string
  description: string | null
  ownerUsername: string | null
  assetCount: number
  updatedAt: string | null
}

export interface DomainForm {
  code: string
  name: string
  description?: string
  ownerUsername?: string
}

export interface GovernanceMember {
  id: string
  username: string
  displayName: string | null
}

export interface AssetGovernance {
  datasetId: string
  datasetName: string
  datasetQualifiedName: string
  domainId: string | null
  domainName: string | null
  ownerUsername: string | null
  stewardUsername: string | null
  certificationStatus: CertificationStatus
  certifiedBy: string | null
  certifiedAt: string | null
  certificationNote: string | null
  reviewDueAt: string | null
  ownerActive: boolean
  stewardActive: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface GovernanceUpdateRequest {
  domainId?: string | null
  ownerUsername?: string | null
  stewardUsername?: string | null
  certificationStatus: CertificationStatus
  certificationNote?: string | null
  reviewDueAt?: string | null
}
