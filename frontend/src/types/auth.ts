import type { MenuItem } from './permission'
import type { Workspace } from './workspace'
import type { User } from './user'

/**
 * 认证与会话 —— 对应后端 LoginResult / SessionView。
 *
 * 登录一次就把令牌、可访问空间、当前空间、权限码与菜单树全拿到,
 * 省掉登录后立刻再打一次 /auth/me 的往返。
 */

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  user: User
  workspaces: Workspace[]
  /**
   * 只有一个可访问空间时后端自动选中;多个时为 null,前端应引导用户选择。
   * 注意后端 ApiResponse 用 NON_NULL 序列化,为 null 时该字段直接不出现。
   */
  currentWorkspaceId: string | null
  permissions: string[]
  menus: MenuItem[]
}

export interface SwitchWorkspaceRequest {
  workspaceId: string
}

/** 切换空间返回的结构与登录一致 —— 都会重新签发令牌并重新解析权限。 */
export type SwitchWorkspaceResult = LoginResult

/** GET /auth/me,供前端刷新页面后恢复状态。 */
export interface SessionView {
  user: User
  workspace: Workspace | null
  workspaces: Workspace[]
  permissions: string[]
  menus: MenuItem[]
}

/** 兼容旧命名 */
export type MeResult = SessionView
