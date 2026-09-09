import { http } from './request'
import type { Rule, RuleForm, RuleKind, RuleKindInfo } from '@/types/rule'

/**
 * 规则管理(功能 17)。
 *
 * kinds() 不需要权限 —— 它返回的是平台支持哪些规则种类,是产品能力而非数据。
 */
export const ruleApi = {
  kinds(): Promise<RuleKindInfo[]> {
    return http.get<RuleKindInfo[]>('/rules/kinds')
  },
  list(kind?: RuleKind): Promise<Rule[]> {
    return http.get<Rule[]>('/rules', kind ? { kind } : undefined)
  },
  create(form: RuleForm): Promise<Rule> {
    return http.post<Rule>('/rules', form)
  },
  update(id: string, form: RuleForm): Promise<Rule> {
    return http.put<Rule>(`/rules/${id}`, form)
  },
  remove(id: string): Promise<void> {
    return http.delete<void>(`/rules/${id}`)
  },
}
