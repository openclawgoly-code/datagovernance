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
  /** 表级聚合时无法归属数据集的列级边数量。 */
  unattributedEdges?: number
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
  /** 表级影响清单用：该节点关联的表级边数量。 */
  edgeCount?: number
}
