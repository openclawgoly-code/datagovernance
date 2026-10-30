import type {
  ImpactItem,
  LineageEdge,
  LineageGraph,
  LineageLayoutNode,
  LineageNode,
} from '@/types/lineage'

/**
 * 合并 upstream/downstream/多次查询的结果。
 *
 * 后端按路径返回节点和边，同一条边可能在两个方向的结果里各出现一次。
 * 这里按节点 ID 与 from→to→relation 去重，组件只处理一份图。
 */
export function mergeLineageGraphs(graphs: LineageGraph[]): LineageGraph {
  const nodes = new Map<string, LineageNode>()
  const edges = new Map<string, LineageEdge>()

  for (const graph of graphs) {
    for (const node of graph.nodes ?? []) {
      nodes.set(node.id, node)
    }
    for (const edge of graph.edges ?? []) {
      const key = `${edge.fromNodeId}->${edge.toNodeId}:${edge.relation}`
      if (!edges.has(key)) {
        edges.set(key, edge)
      }
    }
  }

  const nodeIds = new Set(nodes.keys())
  return {
    nodes: [...nodes.values()],
    // 只保留两端都在节点集里的边，避免 ECharts 因悬空边报错。
    edges: [...edges.values()].filter(
      (edge) => nodeIds.has(edge.fromNodeId) && nodeIds.has(edge.toNodeId),
    ),
  }
}

function adjacencyOf(
  nodes: LineageNode[],
  edges: LineageEdge[],
  direction: 'forward' | 'reverse',
): Map<string, string[]> {
  const adjacency = new Map<string, string[]>()
  for (const node of nodes) {
    adjacency.set(node.id, [])
  }
  for (const edge of edges) {
    const from = direction === 'forward' ? edge.fromNodeId : edge.toNodeId
    const to = direction === 'forward' ? edge.toNodeId : edge.fromNodeId
    const neighbours = adjacency.get(from)
    if (neighbours && !neighbours.includes(to)) {
      neighbours.push(to)
    }
  }
  return adjacency
}

function distancesFrom(startId: string, adjacency: Map<string, string[]>): Map<string, number> {
  const distances = new Map<string, number>([[startId, 0]])
  const queue = [startId]
  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const current = queue[cursor]
    const nextDistance = (distances.get(current) ?? 0) + 1
    for (const next of adjacency.get(current) ?? []) {
      if (!distances.has(next)) {
        distances.set(next, nextDistance)
        queue.push(next)
      }
    }
  }
  return distances
}

/**
 * 把后端图转成从左到右的层级布局数据。
 *
 * 上游在中心字段左侧、下游在右侧；双向查询时同一节点只出现一次。
 * 调用方负责把 offset 换算成像素坐标。
 */
export function buildLineageLayout(graph: LineageGraph, rootId: string): LineageLayoutNode[] {
  if (!graph.nodes.length) {
    return []
  }

  const startId = graph.nodes.some((node) => node.id === rootId)
    ? rootId
    : graph.nodes[0].id
  const forward = distancesFrom(startId, adjacencyOf(graph.nodes, graph.edges, 'forward'))
  const reverse = distancesFrom(startId, adjacencyOf(graph.nodes, graph.edges, 'reverse'))

  return graph.nodes.map((node) => {
    if (node.id === startId) {
      return { ...node, offset: 0, level: 0, side: 'CENTER' }
    }

    const down = forward.get(node.id)
    const up = reverse.get(node.id)

    let offset = 0
    if (down !== undefined && up !== undefined) {
      offset = down <= up ? down : -up
    } else if (down !== undefined) {
      offset = down
    } else if (up !== undefined) {
      offset = -up
    }

    return {
      ...node,
      offset,
      level: Math.abs(offset),
      side: offset < 0 ? 'UPSTREAM' : offset > 0 ? 'DOWNSTREAM' : 'CENTER',
    }
  })
}

/** 影响清单只认 impact API 的下游节点，不复用图内过滤结果。 */
export function buildImpactItems(graph: LineageGraph, rootId: string): ImpactItem[] {
  return buildLineageLayout(graph, rootId)
    .filter((node) => node.offset > 0)
    .map((node) => ({
      id: node.id,
      level: node.level,
      nodeType: node.nodeType,
      qualifiedName: node.qualifiedName,
      displayName: node.displayName,
      catalogDatasetId: node.catalogDatasetId,
      catalogColumnId: node.catalogColumnId,
      edgeCount: graph.edges.filter(
        (edge) => edge.fromNodeId === node.id || edge.toNodeId === node.id,
      ).length,
    }))
    .sort(
      (a, b) =>
        a.level - b.level ||
        (a.displayName || a.qualifiedName).localeCompare(b.displayName || b.qualifiedName),
    )
}

/**
 * 画布节点的两行短标签：父表名 + 字段名。
 *
 * 只截断父表名，字段名完整保留；详情与复制仍使用完整限定名。
 */
export function shortLineageLabel(node: LineageNode, maxLength = 26): string {
  const raw = (node.displayName || node.qualifiedName || node.id).trim()
  const separator = raw.lastIndexOf('.')
  if (separator > 0) {
    const table = raw.slice(0, separator)
    const column = raw.slice(separator + 1)
    const tableLabel = table.length > maxLength ? `${table.slice(0, maxLength - 1)}…` : table
    return `${tableLabel}\n${column}`
  }
  return raw.length > maxLength ? `${raw.slice(0, maxLength - 1)}…` : raw
}

/** 字段节点的父数据集名称；用于节点详情与影响清单。 */
export function parentDatasetOf(node: {
  qualifiedName?: string | null
  catalogDatasetId?: string | null
}): string {
  const qualifiedName = node.qualifiedName || ''
  const separator = qualifiedName.lastIndexOf('.')
  if (separator > 0) {
    return qualifiedName.slice(0, separator)
  }
  return node.catalogDatasetId || '-'
}
