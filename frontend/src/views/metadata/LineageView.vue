<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { catalogAssetApi } from '@/api/catalog-assets'
import { lineageApi } from '@/api/lineage'
import type { CatalogColumn, CatalogDataset, CatalogSource } from '@/types/catalog-assets'
import type { ImpactItem, LineageGraph, LineageLayoutNode, LineageNode } from '@/types/lineage'
import {
  buildImpactItems,
  buildLineageLayout,
  mergeLineageGraphs,
  parentDatasetOf,
} from '@/utils/lineage'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'
import LineageGraphView from '@/components/lineage/LineageGraph.vue'

type LineageDirection = 'upstream' | 'downstream' | 'both'
type LineageMode = 'column' | 'dataset'

const route = useRoute()
const router = useRouter()

const mode = ref<LineageMode>(parseMode(route.query.mode))
const direction = ref<LineageDirection>(parseDirection(route.query.direction))
const depth = ref(parseDepth(route.query.depth))
const datasetId = ref('')
const columnId = ref('')
const dataset = ref<CatalogDataset | null>(null)
const columns = ref<CatalogColumn[]>([])
const sources = ref<CatalogSource[]>([])
const datasetOptions = ref<CatalogDataset[]>([])

const datasetLoading = ref(false)
const datasetSearching = ref(false)
const graphLoading = ref(false)
const graphError = ref('')
const graph = ref<LineageGraph>({ nodes: [], edges: [] })
const rootNodeId = ref('')
const selectedNodeId = ref('')
const nodeTypes = ref<string[]>([])
const expanded = ref(false)

const impactVisible = ref(false)
const impactLoading = ref(false)
const impactError = ref('')
const impactItems = ref<ImpactItem[]>([])

let datasetSearchTimer: ReturnType<typeof setTimeout> | null = null
let searchSequence = 0
let lastSyncedRouteKey = ''
const selfSyncedRouteKeys = new Set<string>()

const directionLabelMap: Record<LineageDirection, string> = {
  both: '双向',
  upstream: '上游',
  downstream: '下游',
}

const isDatasetMode = computed(() => mode.value === 'dataset')
const directionLabel = computed(() => directionLabelMap[direction.value])
const datasetLabel = computed(() => dataset.value?.qualifiedName || '')
const selectedColumn = computed(() =>
  columns.value.find((column) => column.id === columnId.value) || null,
)
const selectedColumnLabel = computed(() => {
  if (!selectedColumn.value) {
    return '-'
  }
  return `${selectedColumn.value.name}（${selectedColumn.value.rawType || selectedColumn.value.canonicalType || '-'}）`
})
const centerLabel = computed(() =>
  isDatasetMode.value ? datasetLabel.value || '未选择数据集' : selectedColumnLabel.value,
)

const fullLayout = computed<LineageLayoutNode[]>(() =>
  buildLineageLayout(graph.value, rootNodeId.value),
)
const availableNodeTypes = computed(() => {
  const types = [...new Set(graph.value.nodes.map((node) => node.nodeType))]
  return types.sort((a, b) => nodeTypeSort(a) - nodeTypeSort(b))
})
const filteredLayout = computed(() => {
  if (!nodeTypes.value.length) {
    return fullLayout.value
  }
  const allowed = new Set(nodeTypes.value)
  return fullLayout.value.filter(
    (node) => node.id === rootNodeId.value || allowed.has(node.nodeType),
  )
})
const visibleLayout = computed(() => {
  let nodes = filteredLayout.value
  if (!expanded.value) {
    if (depth.value > 2) {
      nodes = nodes.filter(
        (node) => node.id === rootNodeId.value || Math.abs(node.offset) <= 2,
      )
    }
    if (nodes.length > 100) {
      nodes = nodes
        .slice()
        .sort(
          (a, b) =>
            Math.abs(a.offset) - Math.abs(b.offset) ||
            (a.displayName || a.qualifiedName).localeCompare(b.displayName || b.qualifiedName),
        )
        .slice(0, 100)
    }
  }
  return nodes
})
const visibleEdges = computed(() => {
  const ids = new Set(visibleLayout.value.map((node) => node.id))
  return graph.value.edges.filter(
    (edge) => ids.has(edge.fromNodeId) && ids.has(edge.toNodeId),
  )
})
const graphHeight = computed(() => {
  const count = visibleLayout.value.length
  return count > 40 ? 620 : count > 10 ? 520 : 440
})
const isTruncated = computed(() => visibleLayout.value.length < filteredLayout.value.length)
const convergenceMessage = computed(() => {
  if (!isTruncated.value && !expanded.value) {
    return ''
  }
  if (expanded.value) {
    return `已展开全部 ${graph.value.nodes.length} 个节点；大图交互可能变慢。`
  }
  const reasons: string[] = []
  if (graph.value.nodes.length > 100) {
    reasons.push(`返回 ${graph.value.nodes.length} 个节点`)
  }
  if (depth.value > 2) {
    reasons.push(`查询深度 ${depth.value} 层`)
  }
  return `${reasons.join('，')}，当前只展示 ${visibleLayout.value.length} 个节点（默认最多 2 层 / 100 节点）。`
})
const canCollapse = computed(
  () => expanded.value && (depth.value > 2 || graph.value.nodes.length > 100),
)

const selectedNode = computed<LineageNode | null>(
  () => graph.value.nodes.find((node) => node.id === selectedNodeId.value) || null,
)
const selectedLayout = computed<LineageLayoutNode | null>(
  () => fullLayout.value.find((node) => node.id === selectedNodeId.value) || null,
)
const selectedEdgeStats = computed(() => {
  const node = selectedNode.value
  if (!node) {
    return { edges: 0, columnEdges: 0 }
  }
  const incident = graph.value.edges.filter(
    (edge) => edge.fromNodeId === node.id || edge.toNodeId === node.id,
  )
  const columnEdges = incident.reduce((sum, edge) => {
    const match = (edge.transformExpr || '').match(/(\d+)/)
    return sum + (match ? Number(match[1]) : 0)
  }, 0)
  return { edges: incident.length, columnEdges }
})
const selectedMetaItems = computed(() => {
  const node = selectedNode.value
  if (!node) {
    return []
  }
  if (isDatasetMode.value) {
    return [
      { label: '限定名', value: node.qualifiedName },
      { label: '数据集 ID', value: node.catalogDatasetId || node.id },
      { label: '节点类型', value: '数据集' },
      { label: '关联表级边', value: selectedEdgeStats.value.edges },
      { label: '累计列级边', value: selectedEdgeStats.value.columnEdges },
    ]
  }
  return [
    { label: '限定名', value: node.qualifiedName },
    { label: '父数据集', value: parentDatasetOf(node) },
    { label: '数据集 ID', value: node.catalogDatasetId || '-' },
    { label: '字段 ID', value: node.catalogColumnId || '-' },
    { label: '节点 ID', value: node.id },
  ]
})
const canCenterSelected = computed(() => {
  const node = selectedNode.value
  if (!node) {
    return false
  }
  if (isDatasetMode.value) {
    return !!node.catalogDatasetId && node.catalogDatasetId !== datasetId.value
  }
  return (
    !!node.catalogColumnId &&
    node.catalogColumnId !== columnId.value &&
    !!node.catalogDatasetId
  )
})

function asString(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }
  return typeof value === 'string' ? value : ''
}

function parseMode(value: unknown): LineageMode {
  return asString(value) === 'dataset' ? 'dataset' : 'column'
}

function parseDirection(value: unknown): LineageDirection {
  const text = asString(value)
  return text === 'upstream' || text === 'downstream' ? text : 'both'
}

function parseDepth(value: unknown): number {
  const parsed = Number(asString(value))
  return parsed >= 1 && parsed <= 3 ? parsed : 2
}

function nodeTypeSort(type: string): number {
  const order = ['COLUMN', 'TABLE', 'DATASET']
  const index = order.indexOf(type)
  return index === -1 ? order.length : index
}

function nodeTypeLabel(type: string): string {
  switch (type) {
    case 'COLUMN':
      return '字段'
    case 'TABLE':
      return '表'
    case 'DATASET':
      return '数据集'
    default:
      return type || '-'
  }
}

function nodeTypeTag(type: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (type) {
    case 'COLUMN':
      return 'primary'
    case 'TABLE':
      return 'success'
    case 'DATASET':
      return 'warning'
    default:
      return 'info'
  }
}

function sideLabel(offset: number): string {
  if (offset < 0) {
    return '上游'
  }
  if (offset > 0) {
    return '下游'
  }
  return '中心'
}

function selectedLevelText(): string {
  if (!selectedLayout.value) {
    return '-'
  }
  if (selectedLayout.value.offset === 0) {
    return isDatasetMode.value ? '中心数据集' : '中心字段'
  }
  return `${sideLabel(selectedLayout.value.offset)} · 第 ${selectedLayout.value.level} 层`
}

function modeTitle(): string {
  return isDatasetMode.value ? '表级血缘' : '字段血缘'
}

function modeDescription(): string {
  return isDatasetMode.value
    ? '按数据集聚合列级血缘，评估表级上下游与影响'
    : '列级上下游、节点详情与影响分析'
}

function registerDataset(item: CatalogDataset) {
  const next = new Map(datasetOptions.value.map((option) => [option.id, option]))
  next.set(item.id, item)
  datasetOptions.value = [...next.values()].sort((a, b) =>
    a.qualifiedName.localeCompare(b.qualifiedName),
  )
}

async function loadSources() {
  try {
    sources.value = await catalogAssetApi.listSources()
  } catch {
    sources.value = []
  }
}

async function searchDatasets(keyword: string) {
  const sequence = ++searchSequence
  datasetSearching.value = true
  try {
    const term = keyword.trim()
    if (!sources.value.length) {
      datasetOptions.value = dataset.value ? [dataset.value] : []
      return
    }
    const results = await Promise.allSettled(
      sources.value.map((source) =>
        catalogAssetApi.listDatasets(source.id, {
          page: 1,
          size: term ? 30 : 15,
          keyword: term || undefined,
        }),
      ),
    )
    if (sequence !== searchSequence) {
      return
    }
    const next = new Map<string, CatalogDataset>()
    if (dataset.value) {
      next.set(dataset.value.id, dataset.value)
    }
    for (const result of results) {
      if (result.status === 'fulfilled') {
        for (const item of result.value.records) {
          next.set(item.id, item)
        }
      }
    }
    datasetOptions.value = [...next.values()]
      .sort((a, b) => a.qualifiedName.localeCompare(b.qualifiedName))
      .slice(0, 80)
  } finally {
    if (sequence === searchSequence) {
      datasetSearching.value = false
    }
  }
}

function onDatasetSearch(keyword: string) {
  if (datasetSearchTimer) {
    clearTimeout(datasetSearchTimer)
  }
  datasetSearchTimer = setTimeout(() => {
    void searchDatasets(keyword)
  }, 260)
}

async function loadDatasetGraph() {
  if (!datasetId.value) {
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    impactVisible.value = false
    impactItems.value = []
    return
  }
  graphLoading.value = true
  graphError.value = ''
  impactVisible.value = false
  impactItems.value = []
  try {
    graph.value = await lineageApi.datasetGraph(datasetId.value, direction.value, depth.value)
    const root =
      graph.value.nodes.find((node) => node.catalogDatasetId === datasetId.value) ||
      graph.value.nodes.find((node) => node.id === datasetId.value) ||
      null
    rootNodeId.value = root?.id || ''
    selectedNodeId.value = root?.id || graph.value.nodes[0]?.id || ''
    nodeTypes.value = []
    expanded.value = false
  } catch {
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    graphError.value = '表级血缘加载失败，请稍后重试'
    ElMessage.error(graphError.value)
  } finally {
    graphLoading.value = false
  }
}

async function loadGraph() {
  if (isDatasetMode.value) {
    await loadDatasetGraph()
    return
  }
  if (!columnId.value) {
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    impactVisible.value = false
    impactItems.value = []
    return
  }

  graphLoading.value = true
  graphError.value = ''
  impactVisible.value = false
  impactItems.value = []
  try {
    const requests: Promise<LineageGraph>[] = []
    if (direction.value === 'both' || direction.value === 'upstream') {
      requests.push(lineageApi.upstream(columnId.value, depth.value))
    }
    if (direction.value === 'both' || direction.value === 'downstream') {
      requests.push(lineageApi.downstream(columnId.value, depth.value))
    }
    const results = await Promise.all(requests)
    graph.value = mergeLineageGraphs(results)
    const root =
      graph.value.nodes.find((node) => node.catalogColumnId === columnId.value) ||
      graph.value.nodes.find((node) => node.id === columnId.value) ||
      null
    rootNodeId.value = root?.id || ''
    selectedNodeId.value = root?.id || graph.value.nodes[0]?.id || ''
    expanded.value = false
    if (nodeTypes.value.length) {
      nodeTypes.value = nodeTypes.value.filter((type) =>
        graph.value.nodes.some((node) => node.nodeType === type),
      )
    }
  } catch {
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    graphError.value = '血缘加载失败，请稍后重试'
    ElMessage.error(graphError.value)
  } finally {
    graphLoading.value = false
  }
}

async function loadDatasetById(id: string, requestedColumnId?: string) {
  datasetLoading.value = true
  datasetId.value = id
  try {
    const detail = await catalogAssetApi.getDataset(id)
    dataset.value = detail.dataset
    registerDataset(detail.dataset)
    columns.value = detail.columns
    const requested = requestedColumnId
      ? detail.columns.find((column) => column.id === requestedColumnId)
      : undefined
    columnId.value = requested?.id || detail.columns[0]?.id || ''
    await loadGraph()
  } catch {
    datasetId.value = ''
    dataset.value = null
    columns.value = []
    columnId.value = ''
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    ElMessage.error('数据集不存在或无权访问')
  } finally {
    datasetLoading.value = false
  }
}

async function resolveColumnDeepLink(column: string) {
  try {
    const node = await lineageApi.node(column)
    if (node.catalogDatasetId) {
      await loadDatasetById(node.catalogDatasetId, column)
      return
    }
  } catch {
    // 没有血缘节点的字段无法反查父数据集；下面按裸字段再查一次图，
    // 有血缘时至少能把图展示出来。
  }
  columnId.value = column
  await loadGraph()
  const root = graph.value.nodes.find((item) => item.catalogColumnId === column)
  if (root?.catalogDatasetId) {
    await loadDatasetById(root.catalogDatasetId, column)
  }
}

async function focusField(targetDatasetId: string, targetColumnId: string) {
  if (
    dataset.value?.id === targetDatasetId &&
    columns.value.some((column) => column.id === targetColumnId)
  ) {
    columnId.value = targetColumnId
    await loadGraph()
    return
  }
  await loadDatasetById(targetDatasetId, targetColumnId)
}

async function onDatasetChange(value: string) {
  if (!value) {
    datasetId.value = ''
    dataset.value = null
    columns.value = []
    columnId.value = ''
    graph.value = { nodes: [], edges: [] }
    rootNodeId.value = ''
    selectedNodeId.value = ''
    syncRoute()
    return
  }
  await loadDatasetById(value)
  syncRoute()
}

async function onModeChange() {
  expanded.value = false
  if (isDatasetMode.value) {
    if (!datasetId.value) {
      graph.value = { nodes: [], edges: [] }
      rootNodeId.value = ''
      selectedNodeId.value = ''
    } else if (!dataset.value) {
      await loadDatasetById(datasetId.value)
    } else {
      await loadDatasetGraph()
    }
    syncRoute()
    return
  }

  if (!datasetId.value) {
    syncRoute()
    return
  }
  if (!columns.value.length) {
    await loadDatasetById(datasetId.value)
  } else {
    if (!columnId.value) {
      columnId.value = columns.value[0]?.id || ''
    }
    await loadGraph()
  }
  syncRoute()
}

async function switchToColumnMode() {
  mode.value = 'column'
  await onModeChange()
}

async function onColumnChange() {
  syncRoute()
  await loadGraph()
}

async function onDirectionChange() {
  expanded.value = false
  syncRoute()
  await loadGraph()
}

async function onDepthChange() {
  expanded.value = false
  syncRoute()
  await loadGraph()
}

function changeDepth(value: number) {
  depth.value = value
  expanded.value = false
  syncRoute()
  void loadGraph()
}

function onNodeTypeChange() {
  expanded.value = false
}

function selectNode(id: string) {
  selectedNodeId.value = id
}

function openSelectedAsset() {
  const id = selectedNode.value?.catalogDatasetId
  if (!id) {
    ElMessage.warning('该节点没有关联的数据集')
    return
  }
  void router.push(`/metadata/assets/${id}`)
}

async function focusDataset(targetDatasetId: string) {
  if (dataset.value?.id === targetDatasetId) {
    await loadDatasetGraph()
    return
  }
  await loadDatasetById(targetDatasetId)
}

async function centerSelected() {
  const node = selectedNode.value
  if (isDatasetMode.value) {
    if (!node?.catalogDatasetId) {
      ElMessage.warning('该节点没有关联的数据集')
      return
    }
    await focusDataset(node.catalogDatasetId)
    syncRoute()
    return
  }
  if (!node?.catalogDatasetId || !node.catalogColumnId) {
    ElMessage.warning('该节点没有关联的字段')
    return
  }
  await focusField(node.catalogDatasetId, node.catalogColumnId)
  syncRoute()
}

async function centerImpactItem(item: any) {
  if (isDatasetMode.value) {
    if (!item.catalogDatasetId) {
      ElMessage.warning('该节点没有关联的数据集')
      return
    }
    await focusDataset(item.catalogDatasetId)
    syncRoute()
    return
  }
  if (!item.catalogDatasetId || !item.catalogColumnId) {
    ElMessage.warning('该节点没有关联的字段')
    return
  }
  await focusField(item.catalogDatasetId, item.catalogColumnId)
  syncRoute()
}

async function loadImpact() {
  if (isDatasetMode.value ? !datasetId.value : !columnId.value) {
    return
  }
  impactVisible.value = true
  impactLoading.value = true
  impactError.value = ''
  try {
    const result = isDatasetMode.value
      ? await lineageApi.datasetGraph(datasetId.value, 'downstream', 5)
      : await lineageApi.impact(columnId.value, 5)
    const root = isDatasetMode.value
      ? result.nodes.find((node) => node.catalogDatasetId === datasetId.value) ||
        result.nodes.find((node) => node.id === rootNodeId.value) ||
        null
      : result.nodes.find((node) => node.catalogColumnId === columnId.value) ||
        result.nodes.find((node) => node.id === rootNodeId.value) ||
        null
    impactItems.value = buildImpactItems(result, root?.id || '')
  } catch {
    impactItems.value = []
    impactError.value = '影响分析加载失败，请稍后重试'
  } finally {
    impactLoading.value = false
  }
}

async function copyText(text: string, successMessage: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(successMessage)
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选择文本')
  }
}

function copyLink() {
  void copyText(window.location.href, '页面链接已复制')
}

function copyQualifiedName() {
  if (!selectedNode.value) {
    return
  }
  void copyText(selectedNode.value.qualifiedName, '限定名已复制')
}

function copyImpact() {
  if (!impactItems.value.length) {
    ElMessage.warning('当前没有可复制的影响清单')
    return
  }
  const header = isDatasetMode.value
    ? [
        '表级血缘影响清单',
        `中心数据集：${datasetLabel.value || '-'}`,
        `生成时间：${new Date().toLocaleString('zh-CN')}`,
        '',
        '层级\t类型\t限定名\t关联表级边\t数据集 ID',
      ]
    : [
        '字段血缘影响清单',
        `中心字段：${selectedColumnLabel.value}`,
        `数据集：${datasetLabel.value || '-'}`,
        `生成时间：${new Date().toLocaleString('zh-CN')}`,
        '',
        '层级\t类型\t限定名\t父数据集\t字段 ID',
      ]
  const lines = impactItems.value.map((item) =>
    isDatasetMode.value
      ? [
          item.level,
          nodeTypeLabel(item.nodeType),
          item.qualifiedName,
          item.edgeCount ?? 0,
          item.catalogDatasetId || '-',
        ].join('\t')
      : [
          item.level,
          nodeTypeLabel(item.nodeType),
          item.qualifiedName,
          parentDatasetOf(item),
          item.catalogColumnId || '-',
        ].join('\t'),
  )
  void copyText([...header, ...lines].join('\n'), '影响清单已复制')
}

async function refresh() {
  await loadGraph()
}

function syncRoute() {
  const query: Record<string, string> = {}
  if (isDatasetMode.value) {
    query.mode = 'dataset'
  }
  if (datasetId.value) {
    query.datasetId = datasetId.value
  }
  if (isDatasetMode.value) {
    query.direction = direction.value
    query.depth = String(depth.value)
  } else if (columnId.value) {
    query.columnId = columnId.value
    query.direction = direction.value
    query.depth = String(depth.value)
  }
  const key = routeKeyOf(query)
  const currentKey = routeKey.value
  lastSyncedRouteKey = key
  if (key !== currentKey) {
    selfSyncedRouteKeys.add(key)
    void router.replace({ path: '/metadata/lineage', query }).catch(() => undefined)
  }
}

function routeState() {
  return {
    mode: parseMode(route.query.mode),
    datasetId: asString(route.query.datasetId),
    columnId: asString(route.query.columnId),
    direction: parseDirection(route.query.direction),
    depth: parseDepth(route.query.depth),
  }
}

function routeKeyOf(query: {
  mode?: string
  datasetId?: string
  columnId?: string
  direction?: string
  depth?: string
}) {
  return [
    query.mode || 'column',
    query.datasetId || '',
    query.columnId || '',
    query.direction || 'both',
    query.depth || '2',
  ].join('|')
}

const routeKey = computed(() =>
  routeKeyOf({
    mode: parseMode(route.query.mode),
    datasetId: asString(route.query.datasetId),
    columnId: asString(route.query.columnId),
    direction: parseDirection(route.query.direction),
    depth: String(parseDepth(route.query.depth)),
  }),
)

async function applyRouteState() {
  const state = routeState()
  mode.value = state.mode
  direction.value = state.direction
  depth.value = state.depth
  expanded.value = false

  if (isDatasetMode.value) {
    if (state.datasetId) {
      if (dataset.value?.id !== state.datasetId) {
        await loadDatasetById(state.datasetId)
      } else {
        await loadDatasetGraph()
      }
    } else {
      graph.value = { nodes: [], edges: [] }
      rootNodeId.value = ''
      selectedNodeId.value = ''
      await searchDatasets('')
    }
    return
  }

  if (state.datasetId) {
    const sameDataset = dataset.value?.id === state.datasetId
    const sameColumn =
      !state.columnId ||
      columns.value.some((column) => column.id === state.columnId)
    if (!sameDataset || !sameColumn) {
      await loadDatasetById(state.datasetId, state.columnId || undefined)
      return
    }
    if (state.columnId && state.columnId !== columnId.value) {
      columnId.value = state.columnId
      await loadGraph()
      return
    }
    if (!columnId.value) {
      columnId.value = columns.value[0]?.id || ''
    }
    await loadGraph()
    return
  }

  if (state.columnId) {
    if (columns.value.some((column) => column.id === state.columnId)) {
      columnId.value = state.columnId
      await loadGraph()
      return
    }
    await resolveColumnDeepLink(state.columnId)
    return
  }

  await searchDatasets('')
}

watch(routeKey, async () => {
  if (routeKey.value === lastSyncedRouteKey) {
    return
  }
  if (selfSyncedRouteKeys.delete(routeKey.value)) {
    return
  }
  await applyRouteState()
  syncRoute()
})

onMounted(async () => {
  await loadSources()
  await applyRouteState()
  syncRoute()
})

onBeforeUnmount(() => {
  if (datasetSearchTimer) {
    clearTimeout(datasetSearchTimer)
  }
})
</script>

<template>
  <div class="page-container lineage-workspace">
    <PageHeader :title="modeTitle()" :description="modeDescription()">
      <template #actions>
        <el-button @click="copyLink">复制链接</el-button>
        <el-button
          :disabled="!datasetId || (!isDatasetMode && !columnId)"
          @click="refresh"
        >
          刷新图谱
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="lineage-controls">
      <FilterBar>
        <div class="control-field">
          <span class="control-field__label">粒度</span>
          <el-radio-group v-model="mode" size="small" @change="onModeChange">
            <el-radio-button value="column">字段级</el-radio-button>
            <el-radio-button value="dataset">表级</el-radio-button>
          </el-radio-group>
        </div>

        <div class="control-field control-field--dataset">
          <span class="control-field__label">数据集</span>
          <el-select
            v-model="datasetId"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="onDatasetSearch"
            :loading="datasetSearching"
            placeholder="搜索数据集"
            @change="onDatasetChange"
          >
            <el-option
              v-for="item in datasetOptions"
              :key="item.id"
              :label="item.qualifiedName"
              :value="item.id"
            >
              <div class="dataset-option">
                <span>{{ item.name }}</span>
                <span class="dataset-option__meta">
                  {{ item.qualifiedName }} · {{ item.columnCount ?? '-' }} 字段
                </span>
              </div>
            </el-option>
          </el-select>
        </div>

        <div v-if="!isDatasetMode" class="control-field control-field--column">
          <span class="control-field__label">字段</span>
          <el-select
            v-model="columnId"
            filterable
            :disabled="!columns.length"
            placeholder="选择字段"
            @change="onColumnChange"
          >
            <el-option
              v-for="column in columns"
              :key="column.id"
              :label="`${column.name} · ${column.rawType || column.canonicalType || '-'}`"
              :value="column.id"
            />
          </el-select>
        </div>

        <div class="control-field">
          <span class="control-field__label">方向</span>
          <el-radio-group v-model="direction" size="small" @change="onDirectionChange">
            <el-radio-button value="upstream">上游</el-radio-button>
            <el-radio-button value="both">双向</el-radio-button>
            <el-radio-button value="downstream">下游</el-radio-button>
          </el-radio-group>
        </div>

        <div class="control-field control-field--depth">
          <span class="control-field__label">深度</span>
          <el-select v-model="depth" @change="onDepthChange">
            <el-option :value="1" label="1 层" />
            <el-option :value="2" label="2 层" />
            <el-option :value="3" label="3 层" />
          </el-select>
        </div>

        <div v-if="!isDatasetMode" class="control-field control-field--types">
          <span class="control-field__label">节点类型</span>
          <el-select
            v-model="nodeTypes"
            multiple
            collapse-tags
            clearable
            placeholder="全部类型"
            @change="onNodeTypeChange"
          >
            <el-option
              v-for="type in availableNodeTypes"
              :key="type"
              :label="nodeTypeLabel(type)"
              :value="type"
            />
          </el-select>
        </div>

        <template #actions>
          <el-button
            type="primary"
            :loading="graphLoading"
            :disabled="!datasetId || (!isDatasetMode && !columnId)"
            @click="refresh"
          >
            查询
          </el-button>
        </template>
      </FilterBar>

      <div class="lineage-meta">
        <template v-if="dataset">
          <span>{{ dataset.qualifiedName }}</span>
          <el-divider direction="vertical" />
          <span>{{ dataset.kind }} / {{ dataset.format || '-' }}</span>
          <el-divider direction="vertical" />
          <span>{{ columns.length }} 个字段</span>
          <template v-if="dataset.rowCount !== null && dataset.rowCount !== undefined">
            <el-divider direction="vertical" />
            <span>{{ dataset.rowCount }} 行</span>
          </template>
        </template>
        <span v-else>
          {{ isDatasetMode ? '选择数据集后查看表级上下游' : '选择数据集后自动加载字段，再以字段为中心查看上下游' }}
        </span>
      </div>
    </el-card>

    <el-alert
      v-if="convergenceMessage"
      class="lineage-convergence"
      type="warning"
      show-icon
      :closable="false"
    >
      <template #title>
        <div class="lineage-convergence__content">
          <span>{{ convergenceMessage }}</span>
          <span class="lineage-convergence__actions">
            <el-button v-if="isTruncated" link type="primary" @click="expanded = true">
              展开全部
            </el-button>
            <el-button v-else-if="canCollapse" link type="primary" @click="expanded = false">
              恢复收敛
            </el-button>
            <el-button v-if="depth > 2" link type="primary" @click="changeDepth(2)">
              改为 2 层
            </el-button>
          </span>
        </div>
      </template>
    </el-alert>

    <div class="lineage-main">
      <el-card shadow="never" class="lineage-chart-card">
        <template #header>
          <div class="panel-head">
            <span class="panel-head__title">血缘图</span>
            <div class="panel-head__meta">
              <el-tag v-if="graph.edges.length" size="small" type="info">
                {{ directionLabel }}
              </el-tag>
              <span v-if="graph.edges.length">
                {{ visibleLayout.length }} / {{ graph.nodes.length }} 节点 ·
                {{ visibleEdges.length }} 边
              </span>
              <span v-if="graph.edges.length" class="panel-head__hint">
                中心：{{ centerLabel }}
              </span>
            </div>
          </div>
        </template>

        <el-alert
          v-if="isDatasetMode && !graphLoading && (graph.unattributedEdges ?? 0) > 0"
          type="warning"
          show-icon
          :closable="false"
          class="lineage-attribution-alert"
          :title="`${graph.unattributedEdges} 条历史列级边缺少数据集归属，未计入表级视图`"
        />

        <div v-if="datasetLoading || graphLoading" v-loading="true" class="lineage-placeholder" />
        <el-alert
          v-else-if="graphError"
          type="error"
          show-icon
          :closable="false"
          :title="graphError"
        >
          <el-button link type="primary" @click="refresh">重试</el-button>
        </el-alert>
        <EmptyState
          v-else-if="isDatasetMode ? !datasetId : !columnId"
          :description="isDatasetMode ? '先选择数据集' : '先选择数据集与字段'"
        />
        <EmptyState
          v-else-if="!graph.edges.length"
          :description="isDatasetMode ? '该数据集暂无表级血缘' : '该字段暂无血缘'"
        >
          <span class="lineage-empty-hint">
            血缘由 ETL / OpenLineage 作业上报后出现在这里
          </span>
        </EmptyState>
        <EmptyState
          v-else-if="!visibleLayout.length"
          description="当前节点类型过滤下没有可展示的节点"
        />
        <LineageGraphView
          v-else
          :layout="visibleLayout"
          :edges="visibleEdges"
          :selected-id="selectedNodeId"
          :height="graphHeight"
          :show-edge-labels="isDatasetMode"
          @select="selectNode"
        />
      </el-card>

      <el-card shadow="never" class="lineage-side-card">
        <template #header>
          <div class="panel-head">
            <span class="panel-head__title">节点详情</span>
            <span v-if="selectedNode" class="panel-head__hint">
              {{ selectedLevelText() }}
            </span>
          </div>
        </template>

        <template v-if="selectedNode">
          <div class="node-summary">
            <div class="node-summary__name">
              {{ selectedNode.displayName || selectedNode.qualifiedName }}
            </div>
            <div class="node-summary__tags">
              <el-tag size="small" :type="nodeTypeTag(selectedNode.nodeType)" effect="plain">
                {{ nodeTypeLabel(selectedNode.nodeType) }}
              </el-tag>
            </div>
          </div>
          <MetadataPanel :items="selectedMetaItems" />
          <div class="node-actions">
            <el-button
              type="primary"
              plain
              :disabled="!selectedNode.catalogDatasetId"
              @click="openSelectedAsset"
            >
              打开资产详情
            </el-button>
            <el-button :disabled="!canCenterSelected" @click="centerSelected">
              {{ isDatasetMode ? '以该数据集为中心' : '以该字段为中心' }}
            </el-button>
            <el-button
              v-if="isDatasetMode"
              :disabled="!dataset"
              @click="switchToColumnMode"
            >
              查看字段级血缘
            </el-button>
            <el-button link @click="copyQualifiedName">复制限定名</el-button>
          </div>
        </template>
        <EmptyState v-else description="点击图中节点查看详情" />
      </el-card>
    </div>

    <el-card shadow="never" class="lineage-impact-card">
      <template #header>
        <div class="panel-head">
          <div class="panel-head__meta">
            <span class="panel-head__title">影响分析</span>
            <span class="panel-head__hint">
              {{ isDatasetMode ? '表级下游 5 层' : '下游 5 层' }}，独立于当前方向与深度
            </span>
          </div>
          <div class="panel-head__actions">
            <el-button
              v-if="impactVisible && impactItems.length"
              link
              type="primary"
              @click="copyImpact"
            >
              复制清单
            </el-button>
            <el-button
              type="primary"
              plain
              :loading="impactLoading"
              :disabled="isDatasetMode ? !datasetId : !columnId"
              @click="loadImpact"
            >
              生成影响清单
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="!impactVisible" class="impact-hint">
        {{ isDatasetMode
          ? '以当前数据集为起点，聚合下游 5 层表级血缘；影响清单与图分开，避免图内过滤影响结论。'
          : '以当前字段为起点，调用 impact API 拉取下游 5 层节点；影响清单与图分开，避免图内过滤影响结论。' }}
      </div>
      <el-alert
        v-else-if="impactError"
        type="error"
        show-icon
        :closable="false"
        :title="impactError"
      />
      <template v-else>
        <el-table v-loading="impactLoading" :data="impactItems" size="small" border>
          <el-table-column prop="level" label="层级" width="72">
            <template #default="{ row }">
              <el-tag size="small" type="warning">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="节点" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.displayName || row.qualifiedName }}
            </template>
          </el-table-column>
          <el-table-column label="类型" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="nodeTypeTag(row.nodeType)">
                {{ nodeTypeLabel(row.nodeType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            v-if="!isDatasetMode"
            label="父数据集"
            min-width="230"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ parentDatasetOf(row) }}</template>
          </el-table-column>
          <el-table-column v-if="isDatasetMode" label="表级边" width="90" align="right">
            <template #default="{ row }">{{ row.edgeCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column v-else label="字段 ID" min-width="210" show-overflow-tooltip>
            <template #default="{ row }">{{ row.catalogColumnId || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="isDatasetMode ? !row.catalogDatasetId : (!row.catalogColumnId || !row.catalogDatasetId)"
                @click="centerImpactItem(row)"
              >
                设为中心
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState
          v-if="!impactLoading && !impactItems.length"
          :description="isDatasetMode ? '该数据集暂无下游表级影响' : '该字段暂无下游影响'"
        />
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.lineage-controls {
  margin-bottom: var(--dg-space-md);
}

.control-field {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
}

.control-field__label {
  font-size: 12px;
  color: var(--dg-text-muted);
  white-space: nowrap;
}

.control-field--dataset :deep(.el-select) {
  width: 280px;
}

.control-field--column :deep(.el-select) {
  width: 220px;
}

.control-field--depth :deep(.el-select) {
  width: 96px;
}

.control-field--types :deep(.el-select) {
  width: 190px;
}

.dataset-option {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.dataset-option__meta {
  font-size: 11px;
  color: var(--dg-text-muted);
}

.lineage-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-muted);
}

.lineage-convergence {
  margin-bottom: var(--dg-space-md);
}

.lineage-attribution-alert {
  margin-bottom: var(--dg-space-sm);
}

.lineage-convergence__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}

.lineage-convergence__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
}

.lineage-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: var(--dg-space-md);
  align-items: start;
}

.lineage-chart-card,
.lineage-side-card,
.lineage-impact-card {
  min-width: 0;
}

.lineage-side-card {
  position: sticky;
  top: 76px;
}

.lineage-placeholder {
  min-height: 480px;
}

.lineage-empty-hint {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}

.panel-head__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--dg-text);
}

.panel-head__meta,
.panel-head__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.panel-head__hint {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.node-summary {
  padding-bottom: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.node-summary__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--dg-text);
  word-break: break-all;
  line-height: 1.5;
}

.node-summary__tags {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-sm);
}

.node-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-lg);
}

.node-actions :deep(.el-button) {
  margin-left: 0;
}

.lineage-impact-card {
  margin-top: var(--dg-space-md);
}

.impact-hint {
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px dashed var(--dg-border);
  border-radius: var(--dg-radius);
  font-size: 12px;
  color: var(--dg-text-muted);
  line-height: 1.7;
}

@media (max-width: 1100px) {
  .lineage-main {
    grid-template-columns: minmax(0, 1fr);
  }

  .lineage-side-card {
    position: static;
  }

  .control-field--dataset :deep(.el-select),
  .control-field--column :deep(.el-select),
  .control-field--types :deep(.el-select) {
    width: 220px;
  }
}
</style>
