import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  BatchCreateRequest,
  BatchCreateResult,
  CancelResult,
  CatalogNodeRequest,
  CatalogTree,
  Artifact,
  CompileDiagnostic,
  CompileResponse,
  DdlPreviewRequest,
  DdlPreviewResponse,
  Execution,
  ExecutionDetail,
  ExecutionQuery,
  Executor,
  ExecutorRegisterRequest,
  ExecutorStatusInfo,
  JobDefinition,
  JobRefTypeInfo,
  JobTypeInfo,
  JobUpsertRequest,
  ScheduleRequest,
  SchedulePreview,
  StreamingMeta,
  StreamingState,
  TaskCatalogNode,
} from '@/types/job'

/**
 * 任务定义 —— 序号 9、11-14、16、18、20、22 共用一套接口。
 *
 * 菜单上它们是七个不同的入口,但那是<b>投影</b>:同一个列表接口加不同的
 * jobType 过滤即可(SPACE-MODEL.md I.2「菜单与模块是多对多」)。
 */
export const jobApi = {
  /** 任务类型元数据。类型下拉与"能不能配调度"的判断都读它,不硬编码 */
  types(): Promise<JobTypeInfo[]> {
    return http.get<JobTypeInfo[]>('/jobs/types')
  },

  list(params: {
    page: number
    size: number
    jobType?: string
    status?: string
    keyword?: string
    /** 任务目录过滤(功能 16);'__none__' 表示只看未分类 */
    catalogId?: string
  }): Promise<PageResult<JobDefinition>> {
    return http.get<PageResult<JobDefinition>>('/jobs', params)
  },

  get(id: string): Promise<JobDefinition> {
    return http.get<JobDefinition>(`/jobs/${id}`)
  },

  create(payload: JobUpsertRequest): Promise<JobDefinition> {
    return http.post<JobDefinition>('/jobs', payload)
  },

  update(id: string, payload: JobUpsertRequest): Promise<JobDefinition> {
    return http.put<JobDefinition>(`/jobs/${id}`, payload)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/jobs/${id}`)
  },

  /**
   * 编译。
   *
   * 注意它**失败也返回 200** —— succeeded=false 加完整诊断列表。所以调用方
   * 不能靠 catch 判断成败,必须看 succeeded 字段。
   */
  compile(id: string): Promise<CompileResponse> {
    return http.post<CompileResponse>(`/jobs/${id}/compile`)
  },

  diagnostics(id: string): Promise<CompileDiagnostic[]> {
    return http.get<CompileDiagnostic[]>(`/jobs/${id}/diagnostics`)
  },

  publish(id: string): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/publish`)
  },

  offline(id: string): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/offline`)
  },

  archive(id: string): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/archive`)
  },

  // ── 调度(功能 16)──────────────────────────────────────────────────

  /** 预览 Cron 的接下来几次触发时间。绑定前先让用户确认。 */
  previewSchedule(payload: ScheduleRequest): Promise<SchedulePreview> {
    return http.post<SchedulePreview>('/jobs/schedule-preview', payload)
  },

  bindSchedule(id: string, payload: ScheduleRequest): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/schedule`, payload)
  },

  unbindSchedule(id: string): Promise<JobDefinition> {
    return http.delete<JobDefinition>(`/jobs/${id}/schedule`)
  },

  pauseSchedule(id: string): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/schedule/pause`)
  },

  resumeSchedule(id: string): Promise<JobDefinition> {
    return http.post<JobDefinition>(`/jobs/${id}/schedule/resume`)
  },

  /** 立即执行一次 */
  run(id: string): Promise<Execution> {
    return http.post<Execution>(`/jobs/${id}/run`)
  },

  /**
   * 批量创建同步任务(功能 14)。
   *
   * 部分成功是正常结果 —— 20 张表里 3 张重名,另外 17 张照常创建。所以这个
   * 调用几乎不会 reject,要看的是返回值里的 failed 列表。
   */
  createBatch(payload: BatchCreateRequest): Promise<BatchCreateResult> {
    return http.post<BatchCreateResult>('/jobs/batch', payload)
  },
}

/** 任务目录(功能 16)。与数据源目录同构,但删除保护与计数口径不同。 */
export const taskCatalogApi = {
  tree(): Promise<CatalogTree> {
    return http.get<CatalogTree>('/jobs/catalog')
  },

  create(payload: CatalogNodeRequest): Promise<TaskCatalogNode> {
    return http.post<TaskCatalogNode>('/jobs/catalog', payload)
  },

  update(id: string, payload: CatalogNodeRequest): Promise<TaskCatalogNode> {
    return http.put<TaskCatalogNode>(`/jobs/catalog/${id}`, payload)
  },

  /** 非空目录会被后端拒绝(409),不做前端预判 —— 判断依据在服务端 */
  remove(id: string): Promise<void> {
    return http.delete<void>(`/jobs/catalog/${id}`)
  },
}

/**
 * 执行记录 —— 序号 10/15/19/21/23 五个页面共用的<b>一套</b>接口。
 *
 * 五张表意味着序号 24 的监控没有单一事实源(架构约束 R4)。既然只有一张表,
 * 自然也只该有一套接口 —— 否则很快会有人在其中一套上加了字段而忘了另外四套。
 */
export const executionApi = {
  jobTypes(): Promise<JobRefTypeInfo[]> {
    return http.get<JobRefTypeInfo[]>('/executions/job-types')
  },

  list(query: ExecutionQuery): Promise<PageResult<Execution>> {
    return http.get<PageResult<Execution>>('/executions', { ...query })
  },

  get(id: string): Promise<ExecutionDetail> {
    return http.get<ExecutionDetail>(`/executions/${id}`)
  },

  children(id: string): Promise<Execution[]> {
    return http.get<Execution[]>(`/executions/${id}/children`)
  },

  /**
   * 取消执行。
   *
   * 取消工作流会级联取消所有还在跑的节点(序号 23),所以返回值里带
   * canceledChildren —— 用户需要知道"我这一下停掉了几个正在跑的东西"。
   */
  cancel(id: string): Promise<CancelResult> {
    return http.post<CancelResult>(`/executions/${id}/cancel`)
  },
}

/** 建表语句预览(功能 9「预览并修改建表语句」)。只生成不执行。 */
export const ddlApi = {
  preview(payload: DdlPreviewRequest): Promise<DdlPreviewResponse> {
    return http.post<DdlPreviewResponse>('/ddl/preview', payload)
  },
}

/**
 * 实时任务的启停(功能 18)。
 *
 * 与 jobApi.run 是两套语义相反的动词:那个是"跑一次然后结束",这个是
 * "让它一直活着"。放在同一个对象上会让调用方以为它们只是叫法不同。
 */
export const streamingApi = {
  /** 运行态元数据。状态标签与按钮可用性都读它,不硬编码 */
  meta(): Promise<StreamingMeta> {
    return http.get<StreamingMeta>('/streaming-jobs/states')
  },

  list(): Promise<StreamingState[]> {
    return http.get<StreamingState[]>('/streaming-jobs')
  },

  get(id: string): Promise<StreamingState> {
    return http.get<StreamingState>(`/streaming-jobs/${id}`)
  },

  /** 已在运行时后端返回 409 —— 重复启动多半意味着有人以为它没起来 */
  start(id: string): Promise<StreamingState> {
    return http.post<StreamingState>(`/streaming-jobs/${id}/start`)
  },

  stop(id: string): Promise<StreamingState> {
    return http.post<StreamingState>(`/streaming-jobs/${id}/stop`)
  },
}

/** 执行器(功能 31)。菜单在「基础配置」下,归属却是 Runtime(R2) */
export const executorApi = {
  statuses(): Promise<ExecutorStatusInfo[]> {
    return http.get<ExecutorStatusInfo[]>('/executors/statuses')
  },

  list(): Promise<Executor[]> {
    return http.get<Executor[]>('/executors')
  },

  register(payload: ExecutorRegisterRequest): Promise<Executor> {
    return http.post<Executor>('/executors', payload)
  },

  /** 排空:不再派新活,等手上的跑完。这是下线执行器的唯一正确入口 */
  drain(id: string): Promise<Executor> {
    return http.post<Executor>(`/executors/${id}/drain`)
  },

  resume(id: string): Promise<Executor> {
    return http.post<Executor>(`/executors/${id}/resume`)
  },

  remove(id: string): Promise<Executor> {
    return http.post<Executor>(`/executors/${id}/remove`)
  },
}

/** 作业制品(功能 32)。制品不可变:同名同版本只能上传一次 */
export const artifactApi = {
  list(params: { page: number; size: number; type?: string; keyword?: string }):
      Promise<PageResult<Artifact>> {
    return http.get<PageResult<Artifact>>('/artifacts', params)
  },

  /**
   * 上传。
   *
   * 走 FormData 而不是 JSON:一个 JAR 转成 base64 会膨胀三分之一,
   * 而制品上限是 512MB。
   */
  upload(file: File, meta: { name: string; version?: string; type?: string; description?: string }):
      Promise<Artifact> {
    const form = new FormData()
    form.append('file', file)
    form.append('name', meta.name)
    if (meta.version) form.append('version', meta.version)
    if (meta.type) form.append('type', meta.type)
    if (meta.description) form.append('description', meta.description)
    return http.upload<Artifact>('/artifacts', form)
  },

  /** 被任务引用的制品不可删,后端返回 409 —— 前端不做预判 */
  remove(id: string): Promise<void> {
    return http.delete<void>(`/artifacts/${id}`)
  },

  downloadUrl(id: string): string {
    return `/api/v1/artifacts/${id}/content`
  },
}
