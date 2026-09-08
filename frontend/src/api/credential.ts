import { http } from './request'
import type { Credential, CredentialForm } from '@/types/credential'

/**
 * 凭据托管(功能 4)。
 *
 * 所有响应都不含凭据内容 —— 明文与密文都不返回。这是后端的类型约束,
 * 前端这里不需要也不应该有"解密"或"查看口令"之类的方法。
 */
export const credentialApi = {
  list(): Promise<Credential[]> {
    return http.get<Credential[]>('/credentials')
  },
  create(form: CredentialForm): Promise<Credential> {
    return http.post<Credential>('/credentials', form)
  },
  update(id: string, form: CredentialForm): Promise<Credential> {
    return http.put<Credential>(`/credentials/${id}`, form)
  },
  remove(id: string): Promise<void> {
    return http.delete<void>(`/credentials/${id}`)
  },
}
