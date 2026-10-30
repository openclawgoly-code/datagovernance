<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { LineageEdge, LineageLayoutNode } from '@/types/lineage'
import { shortLineageLabel } from '@/utils/lineage'

const props = withDefaults(defineProps<{
  layout: LineageLayoutNode[]
  edges: LineageEdge[]
  selectedId?: string
  height?: number
}>(), {
  selectedId: '',
  height: 560,
})

const emit = defineEmits<{ select: [id: string] }>()

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

const chartEl = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null
let resizeObserver: ResizeObserver | null = null
let baseZoom = 1

interface PositionedNode extends LineageLayoutNode {
  x: number
  y: number
  shortName: string
}

const positionedNodes = computed<PositionedNode[]>(() => {
  if (!props.layout.length) {
    return []
  }

  const groups = new Map<number, LineageLayoutNode[]>()
  for (const node of props.layout) {
    const bucket = groups.get(node.offset) ?? []
    bucket.push(node)
    groups.set(node.offset, bucket)
  }

  const largeGraph = props.layout.length > 40
  const maxGroupSize = Math.max(...[...groups.values()].map((bucket) => bucket.length), 1)
  const xGap = 270
  const yGap = largeGraph ? 54 : maxGroupSize > 16 ? 58 : maxGroupSize > 8 ? 72 : 88
  // 单列超过 10 个节点时换列。100 个节点如果排成一列，缩放后就是一团黑；
  // 换成网格后至少能看清分布，再配合点击节点看详情。
  const maxPerColumn = largeGraph ? 10 : 20
  const subColumnGap = 94

  const positioned: PositionedNode[] = []
  for (const [offset, bucket] of groups) {
    const direction = offset === 0 ? 0 : Math.sign(offset)
    const sorted = bucket
      .slice()
      .sort((a, b) => (a.displayName || a.qualifiedName).localeCompare(b.displayName || b.qualifiedName))
    sorted.forEach((node, index) => {
      const columnIndex = Math.floor(index / maxPerColumn)
      const rowIndex = index % maxPerColumn
      const rowsInColumn = Math.min(maxPerColumn, sorted.length - columnIndex * maxPerColumn)
      positioned.push({
        ...node,
        x: offset * xGap + direction * columnIndex * subColumnGap,
        y: (rowIndex - (rowsInColumn - 1) / 2) * yGap,
        shortName: shortLineageLabel(node),
      })
    })
  }

  // 以包围盒中心为原点，重置视图时才能稳定回到中心。
  const xs = positioned.map((node) => node.x)
  const ys = positioned.map((node) => node.y)
  const centerX = (Math.min(...xs) + Math.max(...xs)) / 2
  const centerY = (Math.min(...ys) + Math.max(...ys)) / 2
  for (const node of positioned) {
    node.x -= centerX
    node.y -= centerY
  }
  return positioned
})

const visibleEdges = computed(() => {
  const nodeIds = new Set(props.layout.map((node) => node.id))
  return props.edges.filter(
    (edge) => nodeIds.has(edge.fromNodeId) && nodeIds.has(edge.toNodeId),
  )
})

function cssVar(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function nodeColor(nodeType: string, palette: Record<string, string>): string {
  switch (nodeType) {
    case 'COLUMN':
      return palette.primary
    case 'TABLE':
      return palette.success
    case 'DATASET':
      return palette.warning
    default:
      return palette.info
  }
}

function tooltipFormatter(params: any): string {
  const data = params.data ?? {}
  if (params.dataType === 'edge') {
    const transform = data.transformExpr
      ? `<div>表达式：<code>${escapeHtml(data.transformExpr)}</code></div>`
      : ''
    return [
      `<b>${escapeHtml(data.relation || 'DERIVE')}</b>`,
      `<div>${escapeHtml(data.origin || 'AUTO')} · 置信度 ${escapeHtml(data.confidence ?? '-')}</div>`,
      transform,
    ].join('')
  }
  return [
    `<b>${escapeHtml(data.fullName || data.name)}</b>`,
    `<div>类型：${escapeHtml(data.nodeType || '-')}</div>`,
  ].join('')
}

function fitZoom(): number {
  if (!chartEl.value || !positionedNodes.value.length) {
    return 1
  }
  const width = chartEl.value.clientWidth || 800
  const height = chartEl.value.clientHeight || props.height
  const xs = positionedNodes.value.map((node) => node.x)
  const ys = positionedNodes.value.map((node) => node.y)
  const spanX = Math.max(...xs) - Math.min(...xs) + 280
  const spanY = Math.max(...ys) - Math.min(...ys) + 180
  return Math.max(0.12, Math.min(1, width / spanX, height / spanY))
}

function chartPalette() {
  return {
    primary: cssVar('--dg-primary', '#409eff'),
    success: cssVar('--dg-success', '#67c23a'),
    warning: cssVar('--dg-warning', '#e6a23c'),
    info: cssVar('--dg-info', '#909399'),
    danger: cssVar('--dg-danger', '#f56c6c'),
    text: cssVar('--dg-text', '#303133'),
    textMuted: cssVar('--dg-text-muted', '#909399'),
    border: cssVar('--dg-border-strong', '#dcdfe6'),
    surface: cssVar('--dg-surface', '#ffffff'),
  }
}

function seriesData(palette: ReturnType<typeof chartPalette>) {
  const largeGraph = positionedNodes.value.length > 40
  return positionedNodes.value.map((node) => {
    const selected = node.id === props.selectedId
    const root = node.offset === 0
    return {
      id: node.id,
      name: node.shortName,
      x: node.x,
      y: node.y,
      symbol: node.nodeType === 'COLUMN' ? 'circle' : 'roundRect',
      symbolSize: largeGraph
        ? root ? 40 : 22
        : root ? 58 : node.nodeType === 'COLUMN' ? 44 : 52,
      itemStyle: {
        color: nodeColor(node.nodeType, palette),
        borderColor: selected ? palette.text : root ? palette.text : palette.border,
        borderWidth: selected ? 3 : root ? 2 : 1,
        shadowBlur: selected ? 14 : 0,
        shadowColor: selected ? `${palette.primary}66` : 'transparent',
      },
      label: {
        // 大图默认隐藏标签，点击节点后在右侧看完整信息。
        show: !largeGraph || root || selected,
        position: 'bottom',
        distance: 6,
        fontSize: 11,
        lineHeight: 14,
        fontWeight: root ? 600 : 400,
        color: palette.text,
      },
      fullName: node.qualifiedName,
      nodeType: node.nodeType,
    }
  })
}

function seriesLinks() {
  return visibleEdges.value.map((edge) => ({
    source: edge.fromNodeId,
    target: edge.toNodeId,
    relation: edge.relation,
    transformExpr: edge.transformExpr,
    confidence: edge.confidence,
    origin: edge.origin,
    lineStyle: {
      color: 'source',
      width: 1.2,
      opacity: 0.62,
      curveness: 0.08,
    },
  }))
}

function render(resetView = true) {
  if (!chartEl.value) {
    return
  }
  if (!positionedNodes.value.length) {
    chart?.clear()
    return
  }

  const firstRender = !chart
  if (!chart) {
    chart = echarts.init(chartEl.value)
    chart.on('click', (params: any) => {
      if (params.dataType === 'node' && params.data?.id) {
        emit('select', String(params.data.id))
      }
    })
  }

  const palette = chartPalette()
  const data = seriesData(palette)
  const links = seriesLinks()

  // 选中节点只更新样式，保留用户当前的缩放与拖拽位置。
  if (!resetView && !firstRender) {
    chart.setOption({ series: [{ data, links }] })
    return
  }

  baseZoom = fitZoom()
  chart.setOption({
    animationDuration: 300,
    animationDurationUpdate: 240,
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: palette.surface,
      borderColor: palette.border,
      textStyle: { color: palette.text, fontSize: 12 },
      formatter: tooltipFormatter,
    },
    series: [{
      type: 'graph',
      layout: 'none',
      roam: true,
      draggable: true,
      zoom: baseZoom,
      center: [0, 0],
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 8,
      data,
      links,
      lineStyle: {
        color: palette.textMuted,
        width: 1.2,
        opacity: 0.62,
        curveness: 0.08,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 2.4, opacity: 1 },
        itemStyle: { borderWidth: 3 },
      },
      label: {
        color: palette.text,
      },
    }],
  }, { notMerge: true })
}

function currentZoom(): number {
  const option = chart?.getOption() as any
  const value = option?.series?.[0]?.zoom
  return typeof value === 'number' && Number.isFinite(value) ? value : baseZoom
}

function zoomBy(factor: number) {
  if (!chart) {
    return
  }
  chart.setOption({
    series: [{ zoom: Math.max(0.08, Math.min(3, currentZoom() * factor)) }],
  })
}

function resetView() {
  chart?.setOption({ series: [{ zoom: baseZoom, center: [0, 0] }] })
}

function onResize() {
  chart?.resize()
}

defineExpose({ zoomIn: () => zoomBy(1.25), zoomOut: () => zoomBy(0.8), resetView })

watch(
  [() => props.layout, () => props.edges],
  async () => {
    await nextTick()
    render(true)
  },
  { deep: false },
)

watch(
  () => props.selectedId,
  async () => {
    await nextTick()
    render(false)
  },
  { deep: false },
)

onMounted(async () => {
  await nextTick()
  render(true)
  if (chartEl.value) {
    resizeObserver = new ResizeObserver(onResize)
    resizeObserver.observe(chartEl.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="lineage-graph">
    <div class="lineage-graph__toolbar">
      <el-button-group size="small">
        <el-button @click="zoomBy(1.25)">放大</el-button>
        <el-button @click="zoomBy(0.8)">缩小</el-button>
        <el-button @click="resetView">重置</el-button>
      </el-button-group>
      <span class="lineage-graph__hint">滚轮缩放 · 拖拽画布 · 点击节点查看详情</span>
    </div>
    <div ref="chartEl" class="lineage-graph__canvas" :style="{ height: `${height}px` }" />
  </div>
</template>

<style scoped>
.lineage-graph__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
  flex-wrap: wrap;
}

.lineage-graph__hint {
  color: var(--dg-text-muted);
  font-size: 12px;
}

.lineage-graph__canvas {
  width: 100%;
  min-height: 360px;
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  background: var(--dg-surface);
}
</style>
