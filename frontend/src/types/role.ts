/**
 * 角色 —— 对应 pf_role(+ pf_role_permission)。
 *
 * 契约缺口:V2 种子数据里角色与权限是两张表(pf_role / pf_role_permission),
 * 但 API 清单没有单独列出"角色详情"或"角色-权限"端点。这里假设 GET /roles
 * 直接把已授权的 permissionCodes 内嵌返回,创建/编辑也把它放在同一个请求体里
 * 一并提交——与"角色页可勾选权限树"的产品描述最贴合的最小假设。
 * 一旦后端定下真实形状,只需要改这一个文件和 src/api/role.ts。
 */
export interface Role {
  id: string
  /** null = 平台级内置角色,对所有空间可见但不可编辑(built_in 恒为 true)。 */
  workspaceId: string | null
  code: string
  name: string
  description: string | null
  builtIn: boolean
  permissionCodes: string[]
  createdAt: string
  updatedAt: string
}

export interface RoleForm {
  code: string
  name: string
  description?: string
  permissionCodes: string[]
}
