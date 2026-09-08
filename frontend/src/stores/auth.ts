import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { MenuItem } from '@/types/permission'
import type { User } from '@/types/user'
import type { Workspace } from '@/types/workspace'
import type { LoginResult, SessionView } from '@/types/auth'

const TOKEN_KEY = 'dg.token'
const WORKSPACE_KEY = 'dg.workspaceId'

/**
 * 会话状态。
 *
 * <b>只持久化 token 与 workspaceId</b>,不持久化权限码与用户信息:
 * 权限随时可能被管理员撤销,把它缓存在 localStorage 里会让"撤权"最长
 * 要等到用户主动刷新才生效。刷新页面后由 /auth/me 重新拉一份,
 * 多一次往返换取权限的即时性,这个交换是值得的。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(readStorage(TOKEN_KEY))
  const workspaceId = ref<string>(readStorage(WORKSPACE_KEY))
  const user = ref<User | null>(null)
  const workspaces = ref<Workspace[]>([])
  const permissions = ref<Set<string>>(new Set())
  const menus = ref<MenuItem[]>([])
  /** 会话是否已经从后端拉取过。用于路由守卫判断要不要先恢复会话。 */
  const hydrated = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))
  const currentWorkspace = computed(
    () => workspaces.value.find((w) => w.id === workspaceId.value) ?? null,
  )

  /**
   * 权限判定。
   *
   * 注意这里<b>不</b>做 platformAdmin 的特判:后端解析权限时已经把平台管理员
   * 展开成全部权限码了。前端再判一次等于把同一条规则实现两遍,
   * 两份实现迟早会不一致。
   */
  function hasPermission(code: string): boolean {
    return permissions.value.has(code)
  }

  function applySession(payload: LoginResult | SessionView) {
    if ('token' in payload && payload.token) {
      token.value = payload.token
      writeStorage(TOKEN_KEY, payload.token)
    }
    user.value = payload.user
    workspaces.value = payload.workspaces ?? []
    permissions.value = new Set(payload.permissions ?? [])
    menus.value = payload.menus ?? []

    const nextWorkspace =
      'currentWorkspaceId' in payload
        ? payload.currentWorkspaceId
        : (payload as SessionView).workspace?.id ?? null

    const stillAccessible = workspaces.value.some(
      (w) => w.id === workspaceId.value && w.status === 'ACTIVE',
    )

    if (nextWorkspace) {
      setWorkspace(nextWorkspace)
    } else if (stillAccessible) {
      // 后端在多空间时不自动选中,但用户上次选过 —— 保留它,避免刷新后被清空
    } else {
      // 有多个可访问空间且用户还没选过(或上次选的那个已被停用)。
      // 后端不替用户做这个决定是对的(它无从判断哪个更合适),但前端<b>必须</b>选一个:
      // 不带 X-Workspace-Id 的请求会被后端拒绝,表现是页面一片空白而没有任何提示,
      // 用户根本意识不到自己少做了一步。
      //
      // 只在已启用的空间里挑:停用的空间任何业务请求都会 403,自动选中它等于
      // 把用户直接送进一个处处报错的界面。全都停用时留空,让顶栏显式提示。
      const active = workspaces.value.find((w) => w.status === 'ACTIVE')
      setWorkspace(active ? active.id : '')
    }
    hydrated.value = true
  }

  function setWorkspace(id: string) {
    workspaceId.value = id
    writeStorage(WORKSPACE_KEY, id)
  }

  function clearSession() {
    token.value = ''
    workspaceId.value = ''
    user.value = null
    workspaces.value = []
    permissions.value = new Set()
    menus.value = []
    hydrated.value = false
    writeStorage(TOKEN_KEY, '')
    writeStorage(WORKSPACE_KEY, '')
  }

  return {
    token,
    workspaceId,
    user,
    workspaces,
    permissions,
    menus,
    hydrated,
    isAuthenticated,
    currentWorkspace,
    hasPermission,
    applySession,
    setWorkspace,
    clearSession,
  }
})

// localStorage 在隐私模式或被策略禁用时会抛异常,读写都要兜住 ——
// 存不下最多是每次刷新要重新登录,不该让整个应用起不来。
function readStorage(key: string): string {
  try {
    return localStorage.getItem(key) ?? ''
  } catch {
    return ''
  }
}

function writeStorage(key: string, value: string) {
  try {
    if (value) {
      localStorage.setItem(key, value)
    } else {
      localStorage.removeItem(key)
    }
  } catch {
    /* 忽略:降级为仅内存会话 */
  }
}
