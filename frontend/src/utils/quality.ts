import type { QualityRuleType, QualitySeverity } from '@/types/quality'

export const QUALITY_RULE_LABELS: Record<QualityRuleType, string> = {
  ROW_COUNT_RANGE: '行数区间',
  NOT_NULL: '非空率',
  UNIQUE: '唯一性',
  FRESHNESS: '新鲜度',
  SCHEMA_DRIFT: 'Schema 漂移',
}

export const QUALITY_SEVERITY_LABELS: Record<QualitySeverity, string> = {
  CRITICAL: '严重',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export const QUALITY_SEVERITY_TAG: Record<QualitySeverity, 'danger' | 'warning' | 'primary' | 'info'> = {
  CRITICAL: 'danger',
  HIGH: 'warning',
  MEDIUM: 'primary',
  LOW: 'info',
}

export function qualityRunStatusMeta(status: string | null | undefined): {
  label: string
  type: 'success' | 'danger' | 'warning' | 'info'
} {
  switch (status) {
    case 'SUCCEEDED':
      return { label: '成功', type: 'success' }
    case 'FAILED':
      return { label: '失败', type: 'danger' }
    case 'RUNNING':
      return { label: '运行中', type: 'warning' }
    default:
      return { label: '未运行', type: 'info' }
  }
}

export function metricSummary(ruleType: string, metric: Record<string, unknown> | null | undefined): string {
  if (!metric || Object.keys(metric).length === 0) {
    return '-'
  }
  const value = (key: string): unknown => metric[key]
  switch (ruleType) {
    case 'ROW_COUNT_RANGE': {
      const count = value('rowCount')
      const min = value('min')
      const max = value('max')
      const range = [min == null ? '' : `≥${min}`, max == null ? '' : `≤${max}`]
        .filter(Boolean)
        .join(' / ')
      return `行数 ${formatNumber(count)}${range ? `（${range}）` : ''}`
    }
    case 'NOT_NULL':
      return `空值 ${formatNumber(value('nullRows'))} 行，比例 ${formatPercent(value('nullRatio'))}`
    case 'UNIQUE':
      return `重复 ${formatNumber(value('duplicateRows'))} 行，比例 ${formatPercent(value('duplicateRatio'))}`
    case 'FRESHNESS':
      return `最新 ${value('latest') ?? '-'}，${formatNumber(value('ageHours'))} 小时前`
    case 'SCHEMA_DRIFT':
      return value('baselineSchemaHash') === value('currentSchemaHash')
        ? 'Schema 与基线一致'
        : `基线 ${shortHash(value('baselineSchemaHash'))} / 当前 ${shortHash(value('currentSchemaHash'))}`
    default:
      return JSON.stringify(metric)
  }
}

function formatNumber(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : String(value)
}

function formatPercent(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : String(value)
}

function shortHash(value: unknown): string {
  if (value === null || value === undefined) {
    return '-'
  }
  const text = String(value)
  return text.length > 10 ? `${text.slice(0, 10)}…` : text
}
