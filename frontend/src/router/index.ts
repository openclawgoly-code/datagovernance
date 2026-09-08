import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由表。
 *
 * <b>路由是静态声明的,菜单是后端下发的</b> —— 两者职责不同,不要混为一谈:
 * 路由决定"这个 URL 渲染哪个组件",菜单决定"用户在侧边栏里看得见什么"。
 * 若把路由也做成后端下发,一次后端故障就会让整个前端连 404 页都渲染不出来。
 *
 * 每条路由用 meta.permission 声明它需要的权限码,守卫据此拦截直接输 URL 的访问 ——
 * 只藏菜单是不够的,菜单看不见不等于 URL 进不去。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/workbench',
    children: [
      {
        path: 'workbench',
        name: 'workbench',
        component: () => import('@/views/workbench/WorkbenchView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'metadata/datasources',
        name: 'datasources',
        component: () => import('@/views/metadata/DataSourceListView.vue'),
        meta: { title: '数据源', permission: 'metadata:datasource:read' },
      },
      {
        path: 'integration/jobs',
        name: 'jobs',
        component: () => import('@/views/integration/JobListView.vue'),
        meta: { title: '任务管理', permission: 'control:job:read' },
      },
      // ── 数据开发(序号 18-23)────────────────────────────────────────
      {
        path: 'dev/streaming',
        name: 'streaming-jobs',
        component: () => import('@/views/dev/StreamingJobListView.vue'),
        meta: { title: '实时开发', permission: 'control:job:read' },
      },
      // 离线开发与工作流编排复用任务管理页,靠 meta.jobType 钉死类型。
      // 菜单与模块是多对多(SPACE-MODEL.md I.2):它们是同一个列表的三个投影,
      // 复制三份组件只会让"给列表加一列"变成要改三个地方。
      {
        path: 'dev/batch',
        name: 'batch-jobs',
        component: () => import('@/views/integration/JobListView.vue'),
        meta: { title: '离线开发', permission: 'control:job:read', jobType: 'BATCH' },
      },
      {
        path: 'dev/workflows',
        name: 'workflows',
        component: () => import('@/views/integration/JobListView.vue'),
        meta: { title: '工作流编排', permission: 'control:job:read', jobType: 'WORKFLOW' },
      },
      {
        path: 'ops/executions',
        name: 'executions',
        component: () => import('@/views/integration/ExecutionListView.vue'),
        meta: { title: '执行记录', permission: 'runtime:execution:read' },
      },
      // ── 运维监控 · 治理(序号 24-27、33)──────────────────────────────
      {
        path: 'ops/monitor',
        name: 'monitor',
        component: () => import('@/views/ops/MonitorView.vue'),
        meta: { title: '任务监控', permission: 'governance:monitor:read' },
      },
      {
        path: 'ops/alert-rules',
        name: 'alert-rules',
        component: () => import('@/views/ops/AlertRuleListView.vue'),
        meta: { title: '告警规则', permission: 'governance:rule:read' },
      },
      {
        path: 'ops/alerts',
        name: 'alerts',
        component: () => import('@/views/ops/AlertListView.vue'),
        meta: { title: '告警信息', permission: 'governance:alert:read' },
      },
      {
        path: 'ops/audit',
        name: 'audit',
        component: () => import('@/views/ops/AuditListView.vue'),
        meta: { title: '审计日志', permission: 'governance:audit:read' },
      },
      {
        path: 'settings/channels',
        name: 'alert-channels',
        component: () => import('@/views/settings/AlertChannelListView.vue'),
        meta: { title: '告警渠道', permission: 'governance:channel:read' },
      },
      {
        path: 'settings/executors',
        name: 'executors',
        component: () => import('@/views/settings/ExecutorListView.vue'),
        meta: { title: '执行器管理', permission: 'runtime:executor:read' },
      },
      {
        path: 'settings/artifacts',
        name: 'artifacts',
        component: () => import('@/views/settings/ArtifactListView.vue'),
        meta: { title: '文件管理', permission: 'runtime:artifact:read' },
      },
      {
        path: 'settings/workspaces',
        name: 'workspaces',
        component: () => import('@/views/settings/WorkspaceListView.vue'),
        meta: { title: '空间管理', permission: 'platform:workspace:read' },
      },
      {
        path: 'settings/roles',
        name: 'roles',
        component: () => import('@/views/settings/RoleListView.vue'),
        meta: { title: '角色管理', permission: 'platform:role:read' },
      },
      {
        path: 'settings/users',
        name: 'users',
        component: () => import('@/views/settings/UserListView.vue'),
        meta: { title: '用户管理', permission: 'platform:user:read' },
      },
      {
        path: 'settings/credentials',
        name: 'credentials',
        component: () => import('@/views/settings/CredentialListView.vue'),
        meta: { title: '凭据管理', permission: 'platform:credential:read' },
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { public: true, title: '无权访问' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    return true
  }
  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 刷新页面后 store 是空的,只有 localStorage 里的 token。
  // 权限不做持久化(会让撤权延迟生效),所以每次冷启动都要重新拉一次会话。
  if (!auth.hydrated) {
    try {
      const { fetchMe } = await import('@/api/auth')
      auth.applySession(await fetchMe())
    } catch {
      // 拉取失败通常是令牌过期,拦截器已经清了会话并提示过
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const required = to.meta.permission as string | undefined
  if (required && !auth.hasPermission(required)) {
    return { path: '/403' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 数据治理平台` : '数据治理平台'
})

export default router
