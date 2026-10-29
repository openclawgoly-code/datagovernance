<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { insightApi } from '@/api/insight'
import { useAuthStore } from '@/stores/auth'
import type { InsightAnswer, InsightDashboard, InsightDataset, InsightQueryLog } from '@/types/insight'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()
const question = ref('')
const datasetId = ref<number>()
const datasets = ref<InsightDataset[]>([])
const asking = ref(false)
const exporting = ref(false)
const answer = ref<InsightAnswer | null>(null)
const lastAsked = ref<{ question: string; dataSetId?: number } | null>(null)
const history = ref<InsightQueryLog[]>([])
const historyLoading = ref(false)
const saveDialogVisible = ref(false)
const saveDashboards = ref<InsightDashboard[]>([])
const saveDashboardId = ref('')
const saveNewName = ref('')
const chartEl = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const canAsk = computed(() => question.value.trim().length > 0 && !asking.value)

const chartData = computed(() => {
  const rows = answer.value?.rows ?? []
  if (!rows.length || !columns.value.length) {
    return null
  }
  const numeric = columns.value.filter((column) =>
    rows.some((row) => isNumeric(row[column.key])),
  )
  const dimensions = columns.value.filter(
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

const columns = computed(() => {
  if (answer.value?.columns?.length) {
    return answer.value.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.value?.rows?.[0]
  return first
    ? Object.keys(first).map((key) => ({ key, label: key }))
    : []
})

async function loadDatasets() {
  try {
    datasets.value = await insightApi.datasets()
    if (!datasetId.value) {
      const preferred = datasets.value.find((item) => item.id === 4) ?? datasets.value[0]
      datasetId.value = preferred?.id
    }
  } catch {
    // 拦截器已经提示;保留手输/默认数据集能力,不让页面整体不可用
    datasets.value = [{ id: 4, name: '销售数据集', domain: '销售分析' }]
    datasetId.value = 4
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const page = await insightApi.queries({ page: 1, size: 10 })
    history.value = page.records
  } finally {
    historyLoading.value = false
  }
}

async function ask() {
  if (!canAsk.value) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  const payload = {
    question: question.value.trim(),
    dataSetId: datasetId.value,
  }
  try {
    answer.value = await insightApi.ask(payload)
    lastAsked.value = payload
    await loadHistory()
  } finally {
    asking.value = false
  }
}

async function openSaveDialog() {
  saveDashboards.value = await insightApi.dashboards()
  saveDashboardId.value = saveDashboards.value[0]?.id ?? ''
  saveNewName.value = ''
  saveDialogVisible.value = true
}

async function saveToDashboard() {
  if (!lastAsked.value) {
    return
  }
  let dashboardId = saveDashboardId.value
  if (!dashboardId) {
    if (!saveNewName.value.trim()) {
      ElMessage.warning('请选择看板或输入新看板名称')
      return
    }
    const created = await insightApi.createDashboard({ name: saveNewName.value.trim() })
    dashboardId = created.id
  }
  await insightApi.addDashboardItem(dashboardId, {
    question: lastAsked.value.question,
    datasetId: lastAsked.value.dataSetId,
  })
  ElMessage.success('已保存到看板')
  saveDialogVisible.value = false
}

async function exportCsv() {
  if (!answer.value || !lastAsked.value || exporting.value) {
    return
  }
  exporting.value = true
  try {
    const blob = await insightApi.exportCsv(lastAsked.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `insight-${Date.now()}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

function isNumeric(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return false
  }
  return !Number.isNaN(Number(value))
}

function renderChart() {
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
    grid: { left: 48, right: 24, top: 32, bottom: 48 },
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

watch(answer, async () => {
  await nextTick()
  renderChart()
})

function onResize() {
  chart?.resize()
}

function sqlOf(row: Pick<InsightQueryLog, 'generatedSql'>) {
  return row.generatedSql || ''
}

onMounted(async () => {
  window.addEventListener('resize', onResize)
  await Promise.all([loadDatasets(), loadHistory()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
})
</script>

<template>
  <div class="page-container insight">
    <el-card shadow="never">
      <template #header>
        <div class="insight__header">
          <span>智能问数</span>
          <span class="text-muted">受治理的自然语言分析 · 结果默认脱敏</span>
        </div>
      </template>

      <el-form label-position="top" @submit.prevent="ask">
        <el-form-item label="数据集">
          <el-select
            v-model="datasetId"
            filterable
            placeholder="选择数据集"
            style="width: 360px"
          >
            <el-option
              v-for="item in datasets"
              :key="item.id"
              :label="`${item.domain} / ${item.name}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="问题">
          <el-input
            v-model="question"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="例如：按状态统计订单数、按区域统计总金额、总金额最高的 3 个区域"
            @keydown.ctrl.enter="ask"
          />
        </el-form-item>

        <div class="insight__actions">
          <el-button type="primary" :loading="asking" :disabled="!canAsk" @click="ask">
            开始分析
          </el-button>
          <span class="text-muted">Ctrl / Cmd + Enter 提交</span>
        </div>
      </el-form>
    </el-card>

    <el-card v-if="answer" shadow="never" class="insight__answer">
      <template #header>
        <div class="insight__header">
          <span>分析结果</span>
          <div class="insight__badges">
            <el-tag :type="answer.state === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ answer.state }}
            </el-tag>
            <el-tag v-if="answer.truncated" type="warning" size="small">已截断</el-tag>
            <el-tag v-if="answer.maskedColumns.length" type="warning" size="small">
              已脱敏 {{ answer.maskedColumns.length }} 列
            </el-tag>
            <span class="text-muted">{{ answer.elapsedMs }} ms</span>
            <el-button
              v-if="auth.hasPermission('insight:dashboard:manage')"
              size="small"
              @click="openSaveDialog"
            >
              保存到看板
            </el-button>
            <el-button
              v-if="auth.hasPermission('insight:export')"
              size="small"
              :loading="exporting"
              @click="exportCsv"
            >
              导出 CSV
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="answer.maskedColumns.length"
        type="warning"
        show-icon
        :closable="false"
        class="insight__alert"
        :title="`已按敏感标签遮蔽：${answer.maskedColumns.join('、')}`"
      />

      <div v-if="answer.summary" class="insight__summary">{{ answer.summary }}</div>

      <el-card v-if="chartData" shadow="never" class="insight__chart-card">
        <div ref="chartEl" class="insight__chart" />
      </el-card>

      <el-table :data="answer.rows" size="small" border max-height="460">
        <el-table-column
          v-for="column in columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          min-width="130"
          show-overflow-tooltip
        />
      </el-table>

      <el-collapse class="insight__sql">
        <el-collapse-item title="查看生成 SQL" name="sql">
          <pre>{{ answer.sql || '—' }}</pre>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card shadow="never" class="insight__history">
      <template #header>
        <div class="insight__header">
          <span>最近问数</span>
          <el-button link :loading="historyLoading" @click="loadHistory">刷新</el-button>
        </div>
      </template>
      <el-table :data="history" size="small" v-loading="historyLoading">
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="queryState" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.succeeded ? 'success' : 'danger'" size="small">
              {{ row.queryState || (row.succeeded ? 'SUCCESS' : 'FAILED') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rowCount" label="行数" width="80" />
        <el-table-column prop="elapsedMs" label="耗时(ms)" width="100" />
        <el-table-column prop="maskedColumns" label="脱敏列" min-width="140" show-overflow-tooltip />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="SQL" width="80">
          <template #default="{ row }">
            <el-tooltip v-if="sqlOf(row)" :content="sqlOf(row)" placement="top">
              <el-button link type="primary">查看</el-button>
            </el-tooltip>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="saveDialogVisible" title="保存到看板" width="460px">
      <el-form label-position="top">
        <el-form-item label="已有看板">
          <el-select v-model="saveDashboardId" clearable placeholder="选择已有看板" style="width: 100%">
            <el-option
              v-for="item in saveDashboards"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="或新建看板">
          <el-input v-model="saveNewName" maxlength="128" placeholder="例如：销售日看板" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveToDashboard">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.insight {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.insight__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.insight__badges {
  display: flex;
  align-items: center;
  gap: 8px;
}

.insight__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.insight__alert {
  margin-bottom: 12px;
}

.insight__summary {
  line-height: 1.7;
  margin-bottom: 12px;
  white-space: pre-wrap;
}

.insight__chart-card {
  margin-bottom: 12px;
}

.insight__chart {
  height: 300px;
  width: 100%;
}

.insight__sql {
  margin-top: 12px;
}

.insight__sql pre {
  margin: 0;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
}

.insight__answer,
.insight__history {
  min-height: 120px;
}
</style>
