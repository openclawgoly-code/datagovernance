import type { DataSourceStatus } from '@/types/datasource'
import type { WorkspaceStatus } from '@/types/workspace'
import type { UserStatus } from '@/types/user'

/** el-tag 的 type 属性取值——Element Plus 恰好内置 5 种语义色,与数据源的 5 种状态一一对应,
 *  不需要再引入自定义配色。 */
export type TagType = '' | 'success' | 'warning' | 'danger' | 'info'

interface StatusMeta {
  label: string
  tagType: TagType
  /** 与 el-tag 对应语义色的实际色值一致,供不经过 el-tag 的场景(如工作台的分布条)使用,
   *  保证同一个状态在页面任何位置看起来都是同一个颜色。 */
  color: string
}

/**
 * 数据源 5 种状态的展示元数据。
 * DRAFT(蓝,新建未测) → TESTING(橙,进行中) → ACTIVE(绿,正常) 是主干路径;
 * UNREACHABLE(红,故障)与 DISABLED(灰,人为停用)是两个独立的旁支,
 * 颜色分配刻意让"故障"与"停用"区分开,不能让用户把"连不上"和"我关掉了"混为一谈。
 */
export const DATASOURCE_STATUS_META: Record<DataSourceStatus, StatusMeta> = {
  DRAFT: { label: '草稿', tagType: '', color: '#409eff' },
  TESTING: { label: '测试中', tagType: 'warning', color: '#e6a23c' },
  ACTIVE: { label: '已启用', tagType: 'success', color: '#67c23a' },
  UNREACHABLE: { label: '不可达', tagType: 'danger', color: '#f56c6c' },
  DISABLED: { label: '已禁用', tagType: 'info', color: '#909399' },
}

export const WORKSPACE_STATUS_META: Record<WorkspaceStatus, StatusMeta> = {
  ACTIVE: { label: '正常', tagType: 'success', color: '#67c23a' },
  DISABLED: { label: '已禁用', tagType: 'info', color: '#909399' },
}

export const USER_STATUS_META: Record<UserStatus, StatusMeta> = {
  ACTIVE: { label: '正常', tagType: 'success', color: '#67c23a' },
  DISABLED: { label: '已禁用', tagType: 'info', color: '#909399' },
}

/** 统一的日期时间展示格式;不引入 dayjs/date-fns —— P1 只需要一种固定格式,不值得加依赖。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 文件浏览(FTP/SFTP)里展示文件大小用。 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`
}
