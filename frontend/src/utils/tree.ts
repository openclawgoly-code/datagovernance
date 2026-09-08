/**
 * 通用"扁平列表 + parentCode → 树"还原算法。
 *
 * 菜单树、权限树都用它——这里刻意不认识任何具体的 code/name,只认结构
 * (code / parentCode / sortOrder),这样无论后端将来在 pf_permission 里
 * 新增多少菜单或权限项,这个文件都不需要跟着改一行。这是"不得硬编码菜单树"
 * 这条硬约束在工具函数层面的落地。
 */
export interface TreeSource {
  code: string
  parentCode: string | null | undefined
  sortOrder?: number
  children?: TreeSource[]
}

export function buildTree<T extends TreeSource>(nodes: T[]): T[] {
  if (nodes.length === 0) {
    return []
  }
  // 后端如果已经嵌套好 children(任意一个节点非空),直接信任其结构,
  // 只做同级排序;避免"半信任"两种形状导致的诡异结果。
  if (nodes.some((n) => n.children && n.children.length > 0)) {
    return sortSiblings(nodes)
  }

  const byCode = new Map<string, T & { children: T[] }>()
  for (const node of nodes) {
    byCode.set(node.code, { ...node, children: [] })
  }
  const roots: (T & { children: T[] })[] = []
  for (const node of byCode.values()) {
    const parentCode = node.parentCode
    const parent = parentCode ? byCode.get(parentCode) : undefined
    if (parent) {
      parent.children.push(node)
    } else {
      // parentCode 指向一个不在当前列表里的节点(或本就没有父节点)时,
      // 一律当根节点处理,而不是丢弃——宁可多显示一层,也不要静默漏掉数据。
      roots.push(node)
    }
  }
  return sortSiblings(roots)
}

function sortSiblings<T extends TreeSource>(nodes: T[]): T[] {
  const sorted = [...nodes].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  for (const node of sorted) {
    if (node.children && node.children.length > 0) {
      node.children = sortSiblings(node.children) as T['children']
    }
  }
  return sorted
}

/** 把树重新压平成数组,用于面包屑等需要"按 code 查节点"的场景。 */
export function flattenTree<T extends TreeSource>(nodes: T[]): T[] {
  const result: T[] = []
  const walk = (list: T[]) => {
    for (const node of list) {
      result.push(node)
      if (node.children && node.children.length > 0) {
        walk(node.children as T[])
      }
    }
  }
  walk(nodes)
  return result
}
