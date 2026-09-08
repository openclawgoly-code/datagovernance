import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  Alert,
  AlertChannel,
  AlertRule,
  AlertRuleRequest,
  AlertStatus,
  AlertSummary,
  AuditQuery,
  AuditRecord,
  ChannelRequest,
  ChannelTestResult,
  MonitorDashboard,
  TriggerInfo,
} from '@/types/governance'

/** 任务监控(功能 24)。五个口径一次返回 —— 它们本来就是同一次聚合算出来的。 */
export const monitorApi = {
  dashboard(days?: number): Promise<MonitorDashboard> {
    return http.get<MonitorDashboard>('/governance/monitor', days ? { days } : undefined)
  },
}

/** 告警规则(功能 25)。 */
export const alertRuleApi = {
  /** 触发方式元数据。前端不硬编码这五种,也不硬编码"哪些要填阈值" */
  triggerTypes(): Promise<TriggerInfo[]> {
    return http.get<TriggerInfo[]>('/governance/alert-rules/trigger-types')
  },

  list(): Promise<AlertRule[]> {
    return http.get<AlertRule[]>('/governance/alert-rules')
  },

  create(payload: AlertRuleRequest): Promise<AlertRule> {
    return http.post<AlertRule>('/governance/alert-rules', payload)
  },

  update(id: string, payload: AlertRuleRequest): Promise<AlertRule> {
    return http.put<AlertRule>(`/governance/alert-rules/${id}`, payload)
  },

  setEnabled(id: string, enabled: boolean): Promise<AlertRule> {
    return http.post<AlertRule>(`/governance/alert-rules/${id}/${enabled ? 'enable' : 'disable'}`)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/governance/alert-rules/${id}`)
  },
}

/** 告警信息(功能 26)。 */
export const alertApi = {
  summary(): Promise<AlertSummary> {
    return http.get<AlertSummary>('/governance/alerts/summary')
  },

  list(params: {
    page: number
    size: number
    status?: AlertStatus
    severity?: string
    /** true 即需求里的「今日告警」 */
    today?: boolean
  }): Promise<PageResult<Alert>> {
    return http.get<PageResult<Alert>>('/governance/alerts', params)
  },

  get(id: string): Promise<Alert> {
    return http.get<Alert>(`/governance/alerts/${id}`)
  },

  acknowledge(id: string): Promise<Alert> {
    return http.post<Alert>(`/governance/alerts/${id}/acknowledge`)
  },

  resolve(id: string, note?: string): Promise<Alert> {
    return http.post<Alert>(`/governance/alerts/${id}/resolve`, { note })
  },
}

/** 告警渠道(功能 33)。菜单在「基础配置」下,归属却是 Governance(R2)。 */
export const channelApi = {
  list(): Promise<AlertChannel[]> {
    return http.get<AlertChannel[]>('/governance/channels')
  },

  create(payload: ChannelRequest): Promise<AlertChannel> {
    return http.post<AlertChannel>('/governance/channels', payload)
  },

  update(id: string, payload: ChannelRequest): Promise<AlertChannel> {
    return http.put<AlertChannel>(`/governance/channels/${id}`, payload)
  },

  /** 真的发一条测试消息出去,不是检查配置格式 */
  test(id: string): Promise<ChannelTestResult> {
    return http.post<ChannelTestResult>(`/governance/channels/${id}/test`)
  },

  setEnabled(id: string, enabled: boolean): Promise<AlertChannel> {
    return http.post<AlertChannel>(`/governance/channels/${id}/${enabled ? 'enable' : 'disable'}`)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/governance/channels/${id}`)
  },
}

/**
 * 审计日志(功能 27)。
 *
 * 只有查询 —— 没有 create / update / delete。一条能被修改的审计记录不是
 * 审计记录,所以这个对象上不该出现那些方法,哪怕后端也没有实现它们。
 */
export const auditApi = {
  actions(): Promise<string[]> {
    return http.get<string[]>('/governance/audit/actions')
  },

  search(query: AuditQuery): Promise<PageResult<AuditRecord>> {
    return http.get<PageResult<AuditRecord>>('/governance/audit', { ...query })
  },
}
