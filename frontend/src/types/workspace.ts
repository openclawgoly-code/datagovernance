/**
 * 空间(租户)—— 对应 pf_workspace。
 *
 * 注意术语警示:这里的"空间"是平台功能菜单中的租户概念,与架构文档中的
 * Space(边界)同名不同物。secret_key_enc 是空间密钥的密文,硬约束禁止
 * 展示或缓存,因此这里的类型里根本不声明这个字段——不是"可选未使用",
 * 而是接口契约上就不应该存在于任何前端可见的响应里。
 */
export interface Workspace {
  id: string
  code: string
  name: string
  description: string | null
  status: WorkspaceStatus
  createdAt: string
  createdBy: string | null
  updatedAt: string
  updatedBy: string | null
}

export type WorkspaceStatus = 'ACTIVE' | 'DISABLED'

/** 创建 / 编辑空间的表单载荷。 */
export interface WorkspaceForm {
  code: string
  name: string
  description?: string
  status?: WorkspaceStatus
}

/** 空间成员(授权用户)。 */
export interface WorkspaceMember {
  userId: string
  username: string
  displayName: string | null
  createdAt: string
}
