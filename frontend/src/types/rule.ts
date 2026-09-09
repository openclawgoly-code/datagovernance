/**
 * 清洗与转换规则(功能 17)—— 对应 md_rule。
 *
 * <b>参数表单由后端下发的 paramSpec 渲染,前端不硬编码每种规则有哪些字段。</b>
 * 这是后端 RuleKindInfo 上写明的设计意图:加一种规则时只改后端枚举,
 * 界面自动跟上。所以这里没有"九种规则各自的表单类型",只有一个通用的
 * 参数字典。
 */

/** 与后端 RuleKind 一致。种类清单仍然从 /rules/kinds 取,这里只用于类型标注。 */
export type RuleKind =
  | 'DATE_FORMAT' | 'NUMBER_FORMAT' | 'NULL_FILL'
  | 'STRING_REPLACE' | 'CHANGE_CASE' | 'AFFIX' | 'DECRYPT' | 'TRIM' | 'MASK'

export type RuleCategory = 'CLEANSE' | 'TRANSFORM'

export interface RuleKindInfo {
  kind: RuleKind
  displayName: string
  category: RuleCategory
  categoryDisplayName: string
  /** 参数名 → 给人看的说明。顺序不可依赖(后端用 Map.of 构造),前端自己排 */
  paramSpec: Record<string, string>
  requiredParams: string[]
}

export interface Rule {
  id: string
  name: string
  kind: RuleKind
  kindDisplayName: string
  category: RuleCategory
  categoryDisplayName: string
  description: string | null
  params: Record<string, string>
  /** 被多少个任务引用。>0 时后端拒绝删除(409) */
  referenceCount: number
  createdAt: string
  createdBy: string | null
  updatedAt: string
  updatedBy: string | null
}

export interface RuleForm {
  name: string
  kind: RuleKind
  description?: string
  params: Record<string, string>
}
