import { http } from './request'
import type { Role, RoleForm } from '@/types/role'
import type { PermissionNode } from '@/types/permission'

/** 角色与权限(功能 29)。 */
export const roleApi = {
  /** 当前空间可用角色 = 平台内置角色 + 本空间自定义角色 */
  list(): Promise<Role[]> {
    return http.get<Role[]>('/roles')
  },
  create(form: RoleForm): Promise<Role> {
    return http.post<Role>('/roles', form)
  },
  update(id: string, form: RoleForm): Promise<Role> {
    return http.put<Role>(`/roles/${id}`, form)
  },
  remove(id: string): Promise<void> {
    return http.delete<void>(`/roles/${id}`)
  },
  /** 权限全集(树形),供角色配置页勾选 */
  permissions(): Promise<PermissionNode[]> {
    return http.get<PermissionNode[]>('/permissions')
  },
}
