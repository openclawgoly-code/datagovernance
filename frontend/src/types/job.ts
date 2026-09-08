/**
 * 任务定义与执行记录 —— Control Space 与 Runtime Space 的对外类型。
 *
 * 与数据源类型文件同一条规矩:UI 不按具体任务类型分支。要判断"能不能配调度"
 * 读 `JobTypeInfo.schedulable`,不写 `if (jobType === 'DB_MIGRATION')` ——
 * 后者会让每加一种任务就要改前端。
 */

/** 与后端 JobType 一一对应 */
export type JobType =
  | 'DB_MIGRATION'
  | 'OFFLINE_SYNC'
  | 'FILE_PARSE'
  | 'API_PARSE'
  | 'STREAMING'
  | 'BATCH'
  | 'WORKFLOW'

/**
 * 任务定义状态,与 SPACE-MODEL.md E.2 一致。
 *
 * PAUSED 与 OFFLINE 都表示"不再自动触发",但含义不同:
 *   PAUSED  临时停掉调度,随时可恢复,手工触发仍可用
 *   OFFLINE 已下线,要重新编译发布才能再跑
 * UI 必须把这个区别表达出来。
 */
export type JobDefinitionStatus =
  | 'DRAFT'
  | 'VALIDATED'
  | 'PUBLISHED'
  | 'SCHEDULING'
  | 'PAUSED'
  | 'OFFLINE'
  | 'ARCHIVED'

/** GET /jobs/types 的元素 —— 任务类型下拉与能力判断的唯一来源 */
export interface JobTypeInfo {
  type: JobType
  displayName: string
  /** 对应的 Runtime 作业种类 */
  runtimeType: string
  /** 能否绑定 Cron。整库迁移(一次性)与实时任务(常驻)为 false */
  schedulable: boolean
  /** 是否常驻作业。它的运行态状态机与批任务不同 */
  longRunning: boolean
}

/** 编译诊断的严重级别 */
export type DiagnosticSeverity = 'ERROR' | 'WARNING'

/**
 * 一条编译诊断。
 *
 * `location` 指向定义里出问题的那一处(字段名 / 节点 ID / 表名),
 * UI 靠它把错误标在对应的输入框上 —— 只显示 message 的话,一个二十字段的
 * 同步任务编译失败,用户只能逐个字段去猜。
 */
export interface CompileDiagnostic {
  severity: DiagnosticSeverity
  stage: string
  location: string | null
  message: string
  hint: string | null
}

export interface CompileResponse {
  succeeded: boolean
  summary: string
  diagnostics: CompileDiagnostic[]
}

export interface JobDefinition {
  id: string
  name: string
  jobType: JobType
  jobTypeDisplayName: string
  status: JobDefinitionStatus
  statusDisplayName: string
  description: string | null
  /** 类型特有的配置。结构由 jobType 决定,前端按类型渲染对应表单 */
  config: Record<string, unknown>
  version: number

  lastCompiledAt: string | null
  lastCompileSucceeded: boolean | null
  lastCompileMessage: string | null

  cronExpression: string | null
  cronTimezone: string | null
  misfirePolicy: string | null
  nextFireAt: string | null
  lastFireAt: string | null

  timeoutMs: number | null
  retryMaxAttempts: number | null
  retryBackoffSeconds: number | null

  /** 物理计划是否与当前定义版本一致。false = 改过定义还没重新编译,不能执行 */
  planUpToDate: boolean

  createdAt: string
  createdBy: string | null
  updatedAt: string
  updatedBy: string | null
}

export interface JobUpsertRequest {
  name: string
  jobType: JobType
  description?: string
  config: Record<string, unknown>
  cronExpression?: string
  cronTimezone?: string
  misfirePolicy?: string
  timeoutMs?: number
  retryMaxAttempts?: number
  retryBackoffSeconds?: number
}

export interface ScheduleRequest {
  cronExpression: string
  timezone?: string
  misfirePolicy?: string
}

/** 调度预览 —— 绑定前让用户确认自己写的 Cron 是什么意思 */
export interface SchedulePreview {
  cronExpression: string
  timezone: string
  misfirePolicy: string
  upcomingFireTimes: string[]
}

// ── 执行记录(Runtime)────────────────────────────────────────────────

/**
 * 作业种类。
 *
 * 序号 10/15/19/21/23 五个「执行记录」页面查的是同一张表,靠它区分 ——
 * 这是架构约束 R4 在前端的体现:五个菜单项是同一个列表加不同过滤。
 */
export type JobRefType =
  | 'MIGRATION'
  | 'OFFLINE_SYNC'
  | 'FILE_PARSE'
  | 'API_PARSE'
  | 'STREAMING_DEV'
  | 'BATCH_DEV'
  | 'WORKFLOW'
  | 'WORKFLOW_NODE'
  | 'CONNECTIVITY_PROBE'
  | 'PYTHON_JOB'

/**
 * 执行状态,与 SPACE-MODEL.md E.4 一致。
 *
 * 四个终态而不是两个:取消不是失败(是人主动叫停的),超时与业务报错的
 * 处置方式也完全不同(一个查资源,一个查逻辑)。
 */
export type ExecutionStatus =
  | 'PENDING'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'CANCELING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'
  | 'TIMEOUT'

export interface JobRefTypeInfo {
  type: JobRefType
  displayName: string
  longRunning: boolean
}

export interface Execution {
  id: string
  jobRefType: JobRefType
  jobRefTypeDisplayName: string
  jobRefId: string | null
  jobName: string | null
  defVersion: number | null
  status: ExecutionStatus
  statusDisplayName: string
  parentExecutionId: string | null
  triggerType: string
  triggeredBy: string | null
  attemptCount: number
  submittedAt: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  message: string | null
  errorCode: string | null
  rowsRead: number | null
  rowsWritten: number | null
  bytesProcessed: number | null
}

/** 一次尝试。重试新增尝试而不新建执行 —— 否则"执行总数"会被重试污染。 */
export interface ExecutionAttempt {
  id: string
  attemptNo: number
  status: ExecutionStatus
  statusDisplayName: string
  executorId: string | null
  engineJobId: string | null
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  message: string | null
  errorCode: string | null
  errorDetail: string | null
  rowsRead: number | null
  rowsWritten: number | null
  bytesProcessed: number | null
}

export interface ExecutionDetail {
  execution: Execution
  attempts: ExecutionAttempt[]
}

export interface ExecutionQuery {
  page: number
  size: number
  jobRefType?: JobRefType
  status?: ExecutionStatus
  jobRefId?: string
}

// ── 建表语句预览(功能 9)────────────────────────────────────────────

export interface DdlPreviewRequest {
  sourceDataSourceId: string
  sourceDatabase?: string
  sourceSchema?: string
  sourceTable: string
  targetDataSourceId: string
  targetDatabase?: string
  targetSchema?: string
  targetTable?: string
  tablePrefix?: string
  tableSuffix?: string
  lowercaseNames?: boolean
  options?: Record<string, string>
}

export interface DdlPreviewResponse {
  sourceTable: string
  targetTable: string
  targetType: string
  script: string
  statements: string[]
  /**
   * 类型降级等提醒。**必须显示** —— 整库迁移最常见的事故是某个字段悄悄变窄了,
   * 而这类降级只会在这里出现一次。
   */
  warnings: string[]
  supportedOptions: Record<string, string>
}
