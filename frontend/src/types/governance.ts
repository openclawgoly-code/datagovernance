/**
 * Governance Space 的对外类型 —— 监控(24)、告警规则(25)、告警信息(26)、
 * 审计日志(27)、告警渠道(33)。
 *
 * 单独一个文件而不是并进 job.ts:它们属于不同的 Space,而 job.ts 已经
 * 承载了 Control 与 Runtime 两个。三个 Space 挤在一个类型文件里,"改这个
 * 类型会影响谁"就再也说不清了。
 */

// ── 任务监控(功能 24)────────────────────────────────────────────────

export interface TypeBreakdown {
  jobRefType: string
  displayName: string
  total: number
  failed: number
  rowsWritten: number
}

export interface RecentFailure {
  executionId: string
  jobName: string | null
  jobRefType: string
  status: string
  message: string | null
  errorCode: string | null
  finishedAt: string | null
}

/**
 * 监控面板。
 *
 * 前五个字段是需求原文的五个口径。它们能一次算出来,是因为执行事实只有
 * 一张表(架构约束 R4)—— 否则每个口径都会是一个跨五张表的 UNION。
 */
export interface MonitorDashboard {
  /** 任务实例执行总数 */
  totalExecutions: number
  /** 失败数 */
  failedExecutions: number
  /** 今日新增抽取(行) */
  todayRowsExtracted: number
  /** 总计抽取(行) */
  totalRowsExtracted: number
  /** 任务时延 —— 平均耗时(毫秒) */
  avgLatencyMs: number

  failureRatePercent: number
  runningExecutions: number
  byJobType: TypeBreakdown[]
  recentFailures: RecentFailure[]
  since: string | null
  generatedAt: string
}

// ── 告警规则(功能 25)────────────────────────────────────────────────

export interface TriggerInfo {
  type: string
  displayName: string
  /** 需要阈值的触发方式。UI 据此决定显不显示阈值输入框 */
  needsThreshold: boolean
}

export interface AlertRule {
  id: string
  name: string
  description: string | null
  triggerType: string
  triggerDisplayName: string
  /** ALL(全部任务)/ SPECIFIC(指定任务)—— 需求原文就是这两种 */
  scope: string
  targetJobIds: string[]
  thresholdMs: number | null
  channelIds: string[]
  /** 需求里的「告警频率」:通知之后多久内不再通知。0 = 不抑制 */
  suppressWindowSeconds: number
  status: string
  enabled: boolean
  createdAt: string
  createdBy: string | null
  updatedAt: string
}

export interface AlertRuleRequest {
  name: string
  description?: string
  triggerType: string
  scope: string
  targetJobIds?: string[]
  thresholdMs?: number | null
  channelIds?: string[]
  suppressWindowSeconds?: number
}

// ── 告警信息(功能 26)────────────────────────────────────────────────

export type AlertStatus =
  | 'TRIGGERED'
  | 'NOTIFYING'
  | 'NOTIFIED'
  | 'SUPPRESSED'
  | 'NOTIFY_FAILED'
  | 'ACKNOWLEDGED'
  | 'RESOLVED'

export interface Alert {
  id: string
  ruleId: string
  ruleName: string | null
  status: AlertStatus
  statusDisplayName: string
  severity: string
  title: string
  content: string | null
  executionId?: string | null
  jobRefId?: string | null
  jobName?: string | null
  triggeredAt: string
  notifiedAt?: string | null
  /** 被哪一条压住了。顺着它能找到同一轮故障的第一条 */
  suppressedBy?: string | null
  acknowledgedBy?: string | null
  acknowledgedAt?: string | null
  resolvedAt?: string | null
  resolveNote?: string | null
  notifyError?: string | null
}

export interface AlertSummary {
  todayCount: number
  openCount: number
  /** 今日被抑制数 —— 它告诉值班的人"实际发生次数远不止你收到的" */
  suppressedTodayCount: number
}

// ── 告警渠道(功能 33)────────────────────────────────────────────────

export interface AlertChannel {
  id: string
  name: string
  type: string
  status: string
  /** 脱敏后的目标描述 */
  target: string
  lastTestedAt?: string | null
  lastTestSucceeded?: boolean | null
  lastTestMessage?: string | null
  createdAt: string
  createdBy: string | null
}

export interface ChannelRequest {
  name: string
  type: string
  config: Record<string, unknown>
}

export interface ChannelTestResult {
  succeeded: boolean
  message: string
  testedAt: string
}

// ── 审计日志(功能 27)────────────────────────────────────────────────

export interface AuditRecord {
  id: string
  userId: string | null
  username: string | null
  clientIp: string | null
  action: string
  resourceType: string
  resourceId?: string | null
  resourceName?: string | null
  ownerSpace?: string | null
  succeeded: boolean
  errorCode?: string | null
  requestSummary?: string | null
  detail?: string | null
  occurredAt: string
}

export interface AuditQuery {
  page: number
  size: number
  userId?: string
  action?: string
  resourceType?: string
  resourceId?: string
  succeeded?: boolean
  from?: string
  to?: string
}
