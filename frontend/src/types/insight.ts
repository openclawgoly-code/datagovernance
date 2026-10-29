export interface InsightColumn {
  name: string
  bizName?: string
  type?: string
}

export interface InsightAnswer {
  queryId?: string
  state: string
  sql?: string
  columns: InsightColumn[]
  rows: Record<string, unknown>[]
  rowCount: number
  truncated: boolean
  elapsedMs: number
  maskedColumns: string[]
  summary?: string
}

export interface InsightDataset {
  id: number
  name: string
  domain: string
}

export interface InsightQueryLog {
  id: string
  username?: string
  question: string
  queryState?: string
  generatedSql?: string
  rowCount?: number
  elapsedMs?: number
  maskedColumns?: string
  succeeded: boolean
  errorMessage?: string
  createdAt: string
}

export interface InsightDashboard {
  id: string
  name: string
  description?: string
  itemCount: number
  createdBy?: string
  updatedAt: string
}

export interface InsightDashboardItem {
  id: string
  question: string
  datasetId?: number
  chartType: string
  sortOrder: number
}

export interface InsightDashboardDetail {
  id: string
  name: string
  description?: string
  createdBy?: string
  updatedAt: string
  items: InsightDashboardItem[]
}

export interface InsightPage<T> {
  records: T[]
  total: number
  page: number
  size: number
}
