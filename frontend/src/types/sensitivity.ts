export interface SensitivityRule {
  id: string
  name: string
  category: string
  level: string
  detectorType: string
  pattern?: string | null
  priority?: number | null
  enabled?: boolean | null
  workspaceId?: string | null
}

export interface SensitivityLabel {
  id: string
  datasetId: string
  columnId: string
  columnName: string
  category: string
  level: string
  confidence?: number | null
  status: string
  evidenceJson?: string | null
  detectedBy: string
  detectedAt: string
  reviewedBy?: string | null
  reviewedAt?: string | null
}

export interface SensitivityScanResult {
  runId: string
  scannedColumns: number
  findings: number
  durationMillis: number
}

export interface SensitivityReviewRequest {
  status: 'CONFIRMED' | 'REJECTED'
  level?: string
}
