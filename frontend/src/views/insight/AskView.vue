<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { insightApi } from '@/api/insight'
import type { InsightAnswer, InsightDataset, InsightQueryLog } from '@/types/insight'
import { formatDateTime } from '@/utils/format'

const question = ref('')
const datasetId = ref<number>()
const datasets = ref<InsightDataset[]>([])
const asking = ref(false)
const answer = ref<InsightAnswer | null>(null)
const history = ref<InsightQueryLog[]>([])
const historyLoading = ref(false)

const canAsk = computed(() => question.value.trim().length > 0 && !asking.value)

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
  try {
    answer.value = await insightApi.ask({
      question: question.value.trim(),
      dataSetId: datasetId.value,
    })
    await loadHistory()
  } finally {
    asking.value = false
  }
}

function sqlOf(row: Pick<InsightQueryLog, 'generatedSql'>) {
  return row.generatedSql || ''
}

onMounted(async () => {
  await Promise.all([loadDatasets(), loadHistory()])
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
