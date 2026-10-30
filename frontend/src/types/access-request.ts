/** 敏感数据临时访问申请（P2）。 */
export type AccessCategory = 'PII' | 'FINANCIAL' | 'MEDICAL' | 'CREDENTIAL'
export type AccessLevel = 'L1' | 'L2' | 'L3' | 'L4'
export type AccessRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
export type AccessGrantStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED'

export interface AccessGrant {
  id: string
  requestId: string
  username: string
  category: AccessCategory
  maxLevel: AccessLevel
  expiresAt: string
  revokedAt: string | null
  revokedBy: string | null
  createdAt: string
  status: AccessGrantStatus
  active: boolean
}

export interface AccessRequest {
  id: string
  requester: string
  category: AccessCategory
  maxLevel: AccessLevel
  reason: string
  durationHours: number
  status: AccessRequestStatus
  approver: string | null
  decidedAt: string | null
  decisionNote: string | null
  createdAt: string
  updatedAt: string
  grant: AccessGrant | null
}

export interface CreateAccessRequest {
  category: AccessCategory
  maxLevel: AccessLevel
  reason: string
  durationHours: number
}
