<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { InsightAnswer } from '@/types/insight'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])

/**
 * 单轮回答的图表。
 *
 * 元数据问题（state=METADATA）没有指标，不画图；只有存在数值列时才渲染。
 * 每张图独立 init/dispose，随对话轮次出现和销毁。
 */
const props = defineProps<{ answer: InsightAnswer }>()

const chartEl = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null

function columnsOf(answer: InsightAnswer) {
  if (answer.columns?.length) {
    return answer.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
}

const chartData = computed(() => {
  if (props.answer.state === 'METADATA') {
    return null
  }
  const rows = props.answer.rows ?? []
  const columns = columnsOf(props.answer)
  if (!rows.length || !columns.length) {
    return null
  }
  const numeric = columns.filter((column) =>
    rows.some((row) => isNumeric(row[column.key])),
  )
  const dimensions = columns.filter(
    (column) => !numeric.some((item) => item.key === column.key),
  )
  if (!numeric.length) {
    return null
  }
  const axis = dimensions[0]?.key
  const metric = numeric[0]
  const labels = rows.map((row, index) =>
    axis ? String(row[axis] ?? index + 1) : String(index + 1),
  )
  const values = rows.map((row) => Number(row[metric.key] ?? 0))
  const isTime = labels.every((label) => /^\d{4}-\d{2}(-\d{2})?/.test(label))
  return {
    labels,
    values,
    name: metric.label,
    type: isTime ? ('line' as const) : ('bar' as const),
  }
})

function isNumeric(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return false
  }
  return !Number.isNaN(Number(value))
}

function render() {
  if (!chartData.value || !chartEl.value) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) {
    chart = echarts.init(chartEl.value)
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 32, bottom: 64 },
    xAxis: { type: 'category', data: chartData.value.labels, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series: [{
      name: chartData.value.name,
      type: chartData.value.type,
      data: chartData.value.values,
      smooth: true,
      areaStyle: chartData.value.type === 'line' ? {} : undefined,
    }],
  })
}

function onResize() {
  chart?.resize()
}

watch(chartData, async () => {
  await nextTick()
  render()
})

onMounted(async () => {
  window.addEventListener('resize', onResize)
  await nextTick()
  render()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-if="chartData" ref="chartEl" class="answer-chart" />
</template>

<style scoped>
.answer-chart {
  width: 100%;
  height: 280px;
}
</style>
