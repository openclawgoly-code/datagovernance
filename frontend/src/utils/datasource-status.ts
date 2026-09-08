import type { DataSourceStatus } from '@/types/datasource'

/**
 * 数据源状态的展示映射。
 *
 * 集中一处的意义不只是复用:六个状态里有两个(DRAFT / UNREACHABLE)都表示
 * "现在连不上",但用户该做的事完全不同。把这个区别写进 hint 而不是让每个
 * 页面各自措辞,才能保证列表页、详情页、工作台说的是同一句话。
 */
interface StatusMeta {
  label: string
  /** Element Plus 的 tag 类型 */
  type: 'success' | 'info' | 'warning' | 'danger' | 'primary'
  /** 悬浮提示:这个状态意味着什么、用户该做什么 */
  hint: string
}

const META: Record<DataSourceStatus, StatusMeta> = {
  DRAFT: {
    label: '待验证',
    type: 'info',
    hint: '尚未验证连通性,或连接配置刚被修改。请点「测试连接」;若失败,多半是主机、端口或账号填错了。',
  },
  TESTING: {
    label: '测试中',
    type: 'primary',
    hint: '正在测试连通性。',
  },
  AVAILABLE: {
    label: '可用',
    type: 'success',
    hint: '最近一次验证通过,可以浏览结构、被任务引用。',
  },
  UNREACHABLE: {
    label: '不可达',
    type: 'danger',
    hint: '曾经可用,周期检查发现连不上。配置多半没问题,建议检查网络、目标库是否停机或账号是否被回收。',
  },
  DISABLED: {
    label: '已停用',
    type: 'warning',
    hint: '已人工停用,不参与任何运行时行为。重新启用后需再次验证连通性。',
  },
  ARCHIVED: {
    label: '已归档',
    type: 'info',
    hint: '终态。保留历史与关联关系,不可再修改或启用。',
  },
}

export function statusMeta(status: DataSourceStatus): StatusMeta {
  return META[status] ?? { label: status, type: 'info', hint: '' }
}

export const ALL_STATUSES = Object.keys(META) as DataSourceStatus[]
