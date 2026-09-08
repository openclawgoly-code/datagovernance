import type { TagType } from './format'
import type { ExecutionStatus, JobDefinitionStatus } from '@/types/job'

interface StatusMeta {
  label: string
  tagType: TagType
  color: string
  /** 用户看到这个状态时该做什么。空字符串表示无需动作。 */
  action: string
}

/**
 * 任务定义状态的展示元数据。
 *
 * 每个状态都带一句「该做什么」—— 与数据源状态同一条原则:光告诉用户
 * 「已下线」而不说「要重新编译发布才能再跑」,等于把判断推给用户自己猜。
 */
export const JOB_STATUS_META: Record<JobDefinitionStatus, StatusMeta> = {
  DRAFT: {
    label: '草稿',
    tagType: 'info',
    color: '#909399',
    action: '编译通过后才能发布',
  },
  VALIDATED: {
    label: '已校验',
    tagType: 'primary',
    color: '#409eff',
    action: '编译已通过,可以发布',
  },
  PUBLISHED: {
    label: '已发布',
    tagType: 'success',
    color: '#67c23a',
    action: '可手工执行,也可绑定周期调度',
  },
  SCHEDULING: {
    label: '调度中',
    tagType: 'success',
    color: '#67c23a',
    action: '按 Cron 自动触发',
  },
  PAUSED: {
    label: '已暂停',
    tagType: 'warning',
    color: '#e6a23c',
    // 这是与「已下线」最容易混淆的一处,必须说清楚
    action: '自动触发已停,手工执行仍可用;恢复调度即继续',
  },
  OFFLINE: {
    label: '已下线',
    tagType: 'info',
    color: '#909399',
    action: '不再触发;要重新编译并发布才能再跑',
  },
  ARCHIVED: {
    label: '已归档',
    tagType: 'info',
    color: '#c0c4cc',
    action: '终态,不可恢复',
  },
}

/**
 * 执行状态的展示元数据。
 *
 * FAILED 与 TIMEOUT 都是"没跑出结果",但处置完全不同 —— 一个查逻辑,
 * 一个查资源。CANCELED 更不是失败:它是人主动叫停的。
 */
export const EXECUTION_STATUS_META: Record<ExecutionStatus, StatusMeta> = {
  PENDING: { label: '待下发', tagType: 'info', color: '#909399', action: '等待执行器接收' },
  DISPATCHED: { label: '已下发', tagType: 'primary', color: '#409eff', action: '执行器已接收,尚未开始' },
  RUNNING: { label: '执行中', tagType: 'primary', color: '#409eff', action: '' },
  CANCELING: {
    label: '取消中',
    tagType: 'warning',
    color: '#e6a23c',
    // 取消不是瞬时的:执行器要收尾。不说明的话用户会以为按钮没生效而再点一次
    action: '取消已受理,等待执行器停止',
  },
  SUCCEEDED: { label: '成功', tagType: 'success', color: '#67c23a', action: '' },
  FAILED: {
    label: '失败',
    tagType: 'danger',
    color: '#f56c6c',
    action: '查看错误详情,多为配置或目标端数据问题',
  },
  CANCELED: {
    label: '已取消',
    tagType: 'info',
    color: '#909399',
    action: '由人主动停止,不计入失败率',
  },
  TIMEOUT: {
    label: '超时',
    tagType: 'danger',
    color: '#f56c6c',
    action: '检查目标端负载与超时配置,而不是任务逻辑',
  },
}

export function jobStatusMeta(status: JobDefinitionStatus | null | undefined): StatusMeta {
  return status ? JOB_STATUS_META[status] : JOB_STATUS_META.DRAFT
}

export function executionStatusMeta(status: ExecutionStatus | null | undefined): StatusMeta {
  return status ? EXECUTION_STATUS_META[status] : EXECUTION_STATUS_META.PENDING
}

/** 执行是否已结束。终态之外的都还在跑,列表要轮询它们。 */
export function isTerminal(status: ExecutionStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED'
    || status === 'CANCELED' || status === 'TIMEOUT'
}

/** 耗时的人类可读形式。毫秒数直接显示对超过一分钟的执行毫无意义。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) {
    return '-'
  }
  if (ms < 1000) {
    return `${ms} ms`
  }
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes} 分 ${seconds % 60} 秒`
  }
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

/** 行数的紧凑形式。同步动辄百万行,原样显示会把列撑开。 */
export function formatRows(rows: number | null | undefined): string {
  if (rows == null) {
    return '-'
  }
  if (rows < 10_000) {
    return String(rows)
  }
  if (rows < 100_000_000) {
    return `${(rows / 10_000).toFixed(1)} 万`
  }
  return `${(rows / 100_000_000).toFixed(2)} 亿`
}
