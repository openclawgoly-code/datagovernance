/** 资产质量（P2）：内置规则、运行与加权分数。 */
export type QualityRuleType =
  | 'ROW_COUNT_RANGE'
  | 'NOT_NULL'
  | 'UNIQUE'
  | 'FRESHNESS'
  | 'SCHEMA_DRIFT'

export type QualitySeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'

export interface QualityRule {
  id: string
  datasetId: string
  datasetName: string | null
  datasetQualifiedName: string | null
  columnId: string | null
  columnName: string | null
  ruleType: QualityRuleType
  config: Record<string, unknown>
  severity: QualitySeverity
  enabled: boolean
  createdAt: string
  updatedAt: string
  updatedBy: string | null
}

export interface QualityRuleRequest {
  datasetId: string
  columnId?: string | null
  ruleType: QualityRuleType
  config: Record<string, unknown>
  severity: QualitySeverity
  enabled?: boolean
}

export interface QualityRun {
  id: string
  ruleId: string
  ruleType: QualityRuleType | null
  datasetId: string
  datasetName: string | null
  datasetQualifiedName: string | null
  columnId: string | null
  columnName: string | null
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  passed: boolean
  metric: Record<string, unknown>
  violations: number | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  executedBy: string | null
}

export interface QualityRuleSummary {
  ruleId: string
  ruleType: QualityRuleType
  severity: QualitySeverity
  columnId: string | null
  columnName: string | null
  enabled: boolean
  latestStatus: 'NOT_RUN' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  latestPassed: boolean | null
  metric: Record<string, unknown>
  violations: number | null
  durationMs: number | null
  executedAt: string | null
  errorMessage: string | null
}

export interface QualitySummary {
  datasetId: string
  score: number | null
  totalRules: number
  evaluatedRules: number
  passedRules: number
  failedRules: number
  rules: QualityRuleSummary[]
}
