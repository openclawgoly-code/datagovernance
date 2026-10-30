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
