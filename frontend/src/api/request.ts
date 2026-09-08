import axios, { type AxiosRequestConfig } from 'axios'
// 显式导入而不依赖 unplugin-auto-import:auto-imports.d.ts 是 vite 构建期生成的,
// 而 vue-tsc 在它之前跑,靠自动导入会让类型检查报"找不到 ElMessage"。
// 何况这是个纯 .ts 模块,显式导入本来就更容易读。
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

/**
 * 全局唯一的 Axios 实例。
 *
 * request.ts / stores/auth.ts / router 三者之间存在 import 环:store 的
 * action 要调用这里导出的 http 请求方法,这里的拦截器又要读 store 里的
 * token/workspaceId、以及在 401 时用 router 跳转。ES Module 的循环引用
 * 在"只在回调函数体内部使用对方的绑定"时是安全的——所有拦截器函数都是
 * 在请求真正发出/响应真正返回的那一刻才执行,那时整个模块图早已加载完毕。
 * 唯一要避免的是在任何一个模块的顶层直接调用 useAuthStore(),这里没有。
 */
const service = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
})

service.interceptors.request.use((config) => {
  const authStore = useAuthStore()
  config.headers = config.headers ?? {}
  if (authStore.token) {
    config.headers.Authorization = `Bearer ${authStore.token}`
  }
  if (authStore.workspaceId) {
    config.headers['X-Workspace-Id'] = authStore.workspaceId
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    // 少数接口(如未来可能出现的文件下载)不一定走 ApiResponse 信封,
    // 只有能识别出 success 字段时才按信封语义处理,否则原样透传。
    if (body && typeof body === 'object' && typeof body.success === 'boolean') {
      if (!body.success) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(body)
      }
      return body.data
    }
    return response.data
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      if (error.response) {
        const { status, data } = error.response as { status: number; data?: ApiResponse<unknown> }
        const message = data?.message || `请求失败(HTTP ${status})`
        if (status === 401) {
          ElMessage.error(message || '登录已过期,请重新登录')
          const authStore = useAuthStore()
          authStore.clearSession()
          const current = router.currentRoute.value
          if (current.path !== '/login') {
            router.push({ path: '/login', query: { redirect: current.fullPath } })
          }
        } else {
          ElMessage.error(message)
        }
      } else {
        ElMessage.error('网络异常,请检查后端服务是否可用')
      }
    } else {
      ElMessage.error('请求发生未知错误')
    }
    return Promise.reject(error)
  },
)

/**
 * 统一的类型化请求方法。响应拦截器已经把 ApiResponse 拆开、直接返回 data,
 * 因此这里的返回类型是拆包后的 T,而不是 AxiosResponse<T>——`as unknown as`
 * 是为了让类型声明匹配拦截器改写过的实际运行时形状,不是绕过类型检查偷懒。
 */
function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request(config) as unknown as Promise<T>
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return request<T>({ url, method: 'GET', params })
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ url, method: 'POST', data })
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ url, method: 'PUT', data })
  },
  delete<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return request<T>({ url, method: 'DELETE', params })
  },
}

export default service
