import type { Workspace } from './workspace'
import type { MenuItem } from './permission'
import type { User } from './user'

// ── 登录 / 会话 ──────────────────────────────────────────────────────
// 用户实体本身定义在 ./user(与用户管理页共用),这里只放"登录会话"相关的
// 请求/响应形状。

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  user: User
  /** 该用户被授权访问的空间列表,供登录后 / 顶部下拉切换选择。 */
  workspaces: Workspace[]
}

export interface SwitchWorkspaceRequest {
  workspaceId: string
}

export interface SwitchWorkspaceResult {
  token: string
}

/** GET /auth/me —— 当前登录态在"当前空间"下的完整快照。 */
export interface MeResult {
  user: User
  workspace: Workspace
  permissions: string[]
  menus: MenuItem[]
}
