import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  CompileDiagnostic,
  CompileResponse,
  DdlPreviewRequest,
  DdlPreviewResponse,
  Execution,
  ExecutionDetail,
  ExecutionQuery,
  JobDefinition,
  JobRefTypeInfo,
  JobTypeInfo,
  JobUpsertRequest,
  ScheduleRequest,
  SchedulePreview,
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

  cancel(id: string): Promise<Execution> {
    return http.post<Execution>(`/executions/${id}/cancel`)
  },
}

/** 建表语句预览(功能 9「预览并修改建表语句」)。只生成不执行。 */
export const ddlApi = {
  preview(payload: DdlPreviewRequest): Promise<DdlPreviewResponse> {
    return http.post<DdlPreviewResponse>('/ddl/preview', payload)
  },
}
