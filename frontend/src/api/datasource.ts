import { http } from './request'
import type { PageResult } from '@/types/api'
import type {
  ConnectivityResult,
  DataSource,
  DataSourceCatalogTree,
  DataSourceForm,
  DataSourceQuery,
  DataSourceTypeInfo,
  DataSourceVersion,
} from '@/types/datasource'
import type { CatalogPage } from '@/types/catalog'

/** 数据源(功能 1-4、6、8)与数据源目录(功能 5)、库表结构浏览(功能 7)。 */
export const dataSourceApi = {
  /**
   * 平台可用的数据源类型及其能力。
   *
   * 只返回驱动确实就绪的类型 —— 信创环境里达梦等驱动常需现场安装,
   * 缺失时该类型不会出现在这里,而不是等用户建完数据源才报错。
   */
  types(): Promise<DataSourceTypeInfo[]> {
    return http.get<DataSourceTypeInfo[]>('/datasource-types')
  },

  list(query: DataSourceQuery): Promise<PageResult<DataSource>> {
    return http.get<PageResult<DataSource>>('/datasources', { ...query })
  },

  get(id: string): Promise<DataSource> {
    return http.get<DataSource>(`/datasources/${id}`)
  },

  create(form: DataSourceForm): Promise<DataSource> {
    return http.post<DataSource>('/datasources', form)
  },

  update(id: string, form: DataSourceForm): Promise<DataSource> {
    return http.put<DataSource>(`/datasources/${id}`, form)
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/datasources/${id}`)
  },

  /** 已保存数据源的连通性测试。成功进 AVAILABLE,失败回 DRAFT。 */
  test(id: string): Promise<ConnectivityResult> {
    return http.post<ConnectivityResult>(`/datasources/${id}/test`)
  },

  /** 未保存试连 —— 不落库、不改状态,用于新建表单里的即时验证。 */
  testTransient(form: DataSourceForm): Promise<ConnectivityResult> {
    return http.post<ConnectivityResult>('/datasources/test', form)
  },

  enable(id: string): Promise<DataSource> {
    return http.post<DataSource>(`/datasources/${id}/enable`)
  },

  disable(id: string): Promise<DataSource> {
    return http.post<DataSource>(`/datasources/${id}/disable`)
  },

  archive(id: string): Promise<DataSource> {
    return http.post<DataSource>(`/datasources/${id}/archive`)
  },

  /** 开启/关闭周期连通性检查(功能 6)。间隔下限 5 分钟。 */
  setProbeSchedule(id: string, enabled: boolean, intervalMinutes?: number): Promise<DataSource> {
    return http.post<DataSource>(`/datasources/${id}/probe-schedule`, { enabled, intervalMinutes })
  },

  versions(id: string): Promise<DataSourceVersion[]> {
    return http.get<DataSourceVersion[]>(`/datasources/${id}/versions`)
  },

  /**
   * 浏览库表结构(功能 7)。
   *
   * 返回哪一层由路径参数与该数据源的 capabilities 共同决定,调用方只管往下传路径。
   */
  browseCatalog(
    id: string,
    path: { database?: string; schema?: string; table?: string; path?: string },
    refresh = false,
  ): Promise<CatalogPage> {
    return http.get<CatalogPage>(`/datasources/${id}/catalog`, { ...path, refresh })
  },
}

/** 数据源目录(功能 5)—— 人工维护的组织结构,不是库表结构。 */
export const dataSourceCatalogApi = {
  tree(): Promise<DataSourceCatalogTree> {
    return http.get<DataSourceCatalogTree>('/datasource-catalogs')
  },

  create(body: {
    parentId?: string | null
    name: string
    description?: string
    sortOrder?: number
  }): Promise<unknown> {
    return http.post('/datasource-catalogs', body)
  },

  update(
    id: string,
    body: { name: string; description?: string; sortOrder?: number },
  ): Promise<unknown> {
    return http.put(`/datasource-catalogs/${id}`, body)
  },

  move(id: string, newParentId: string | null): Promise<void> {
    return http.post<void>(`/datasource-catalogs/${id}/move`, { newParentId })
  },

  remove(id: string): Promise<void> {
    return http.delete<void>(`/datasource-catalogs/${id}`)
  },
}
