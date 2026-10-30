export interface LineageNode {
  id: string
  nodeType: string
  qualifiedName: string
  catalogDatasetId?: string | null
  catalogColumnId?: string | null
  displayName?: string | null
}

export interface LineageEdge {
  id: string
  fromNodeId: string
  toNodeId: string
  relation: string
  transformExpr?: string | null
  confidence?: number | null
  origin?: string | null
  status?: string | null
}

export interface LineageGraph {
  nodes: LineageNode[]
  edges: LineageEdge[]
}

/** 图上的节点相对中心字段的位置：负数上游、正数下游、0 为中心。 */
export interface LineageLayoutNode extends LineageNode {
  offset: number
  level: number
  side: 'UPSTREAM' | 'DOWNSTREAM' | 'CENTER'
}

/** 影响分析表格的一行。始终来自 impact API，而不是图内过滤结果。 */
export interface ImpactItem {
  id: string
  level: number
  nodeType: string
  qualifiedName: string
  displayName?: string | null
  catalogDatasetId?: string | null
  catalogColumnId?: string | null
}
