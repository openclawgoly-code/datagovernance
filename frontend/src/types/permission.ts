/**
 * 权限项(菜单 / 操作)—— 对应 pf_permission。
 *
 * 这是整个前端"不硬编码菜单/权限"约束的数据源头:菜单树、按钮的
 * v-permission 判定,全部消费这里定义的结构,没有任何一处允许把
 * 具体的 code/name 写死在组件里。
 */
export type PermissionType = 'MENU' | 'ACTION'

export interface PermissionNode {
  code: string
  name: string
  type: PermissionType
  parentCode: string | null
  routePath: string | null
  icon: string | null
  sortOrder: number
  ownerSpace?: string
  builtIn?: boolean
  /** 后端若已给出树形结构则直接带 children;若是扁平列表,由 buildTree() 在前端还原。 */
  children?: PermissionNode[]
}

/** /auth/me 里的菜单项——结构上与 PermissionNode 一致,单独命名是为了在
 *  auth store / 布局组件里语义更直白("这是菜单",不是"这是权限判定用的原始项")。 */
export type MenuItem = PermissionNode
