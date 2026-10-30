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
  datasetId?: number | null
}

export interface InsightDashboard {
  id: string
  name: string
  description?: string
  itemCount: number
  version?: number
  visibility?: string
  createdBy?: string
  updatedAt: string
}

export interface InsightDashboardItem {
  id: string
  question: string
  datasetId?: number
  chartType: string
  sortOrder: number
  layoutJson?: string
}

export interface InsightDashboardDetail {
  id: string
  name: string
  description?: string
  version?: number
  visibility?: string
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

export interface InsightDashboardRunItem {
  itemId: string
  question: string
  succeeded: boolean
  answer?: InsightAnswer
  error?: string
}

export interface InsightDashboardRun {
  dashboardId: string
  name: string
  refreshedAt: string
  items: InsightDashboardRunItem[]
}

export interface InsightDashboardVersion {
  version: number
  createdBy?: string
  createdAt: string
  itemCount: number
}

export interface InsightSupersonicMapping {
  id: string
  platformUsername: string
  supersonicUsername: string
  credentialId: string
  enabled: boolean
  createdBy?: string
  updatedAt: string
}

export interface InsightDashboardAcl {
  username: string
  permission: 'READ' | 'MANAGE'
}

export interface InsightDashboardItemUpdate {
  sortOrder?: number
  layoutJson?: string
}
