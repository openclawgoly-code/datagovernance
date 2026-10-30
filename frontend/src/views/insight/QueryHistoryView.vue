<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { insightApi } from '@/api/insight'
import { useAuthStore } from '@/stores/auth'
import type { InsightAnswer, InsightQueryLog } from '@/types/insight'
import { formatDateTime } from '@/utils/format'
import { formatDuration, formatRows } from '@/utils/job-status'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'
import QuerySqlBlock from '@/components/insight/QuerySqlBlock.vue'
import StatusTag from '@/components/insight/StatusTag.vue'

type HistoryStatus = '' | 'success' | 'failed'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const error = ref('')
const logs = ref<InsightQueryLog[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20 })
const keyword = ref('')
const statusFilter = ref<HistoryStatus>('')

const detailVisible = ref(false)
const selectedLog = ref<InsightQueryLog | null>(null)
const rerunningId = ref('')
const resultVisible = ref(false)
const resultAnswer = ref<InsightAnswer | null>(null)

const canAsk = computed(() => auth.hasPermission('insight:ask'))
const canExport = computed(() => auth.hasPermission('insight:export'))
const hasFilter = computed(() => !!keyword.value.trim() || !!statusFilter.value)
const filteredLogs = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return logs.value.filter((log) => {
    if (statusFilter.value === 'success' && !log.succeeded) {
      return false
    }
    if (statusFilter.value === 'failed' && log.succeeded) {
      return false
    }
    if (!term) {
      return true
    }
    const haystack = [
      log.question,
      log.username,
      log.generatedSql,
      log.maskedColumns,
      log.errorMessage,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(term)
  })
})
const selectedMaskedColumns = computed(() => maskedColumns(selectedLog.value))
const resultMaskedColumns = computed(() => resultAnswer.value?.maskedColumns ?? [])
const resultColumns = computed(() => {
  const answer = resultAnswer.value
  if (!answer) {
    return []
  }
  if (answer.columns?.length) {
    return answer.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
})
const detailItems = computed(() => {
  const log = selectedLog.value
  if (!log) {
    return []
  }
  return [
    { label: '用户', value: log.username || '-' },
    { label: '数据集 ID', value: log.datasetId ?? '-' },
    { label: '查询状态', value: log.queryState || '-' },
    { label: '行数', value: log.rowCount ?? '-' },
    { label: '耗时', value: formatDuration(log.elapsedMs) },
    { label: '时间', value: formatDateTime(log.createdAt) },
    { label: '脱敏列数', value: maskedColumns(log).length },
    { label: '记录 ID', value: log.id },
  ]
})

function maskedColumns(log: InsightQueryLog | null): string[] {
  if (!log?.maskedColumns) {
    return []
  }
  return log.maskedColumns
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function maskedPreview(log: any): string {
  const columns = maskedColumns(log)
  if (!columns.length) {
    return '-'
  }
  if (columns.length <= 3) {
    return columns.join('、')
  }
  return `${columns.slice(0, 3).join('、')} 等 ${columns.length} 列`
}

function statusOf(log: any): { status: string; label: string } {
  return log.succeeded
    ? { status: 'SUCCEEDED', label: '成功' }
    : { status: 'FAILED', label: '失败' }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await insightApi.queries({ page: query.page, size: query.size })
    logs.value = page.records
    total.value = page.total
  } catch {
    logs.value = []
    total.value = 0
    error.value = '查询历史加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openDetail(log: any) {
  selectedLog.value = log
  detailVisible.value = true
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
}

async function rerun(log: any) {
  if (!canAsk.value) {
    return
  }
  rerunningId.value = log.id
  try {
    const answer = await insightApi.ask({
      question: log.question,
      dataSetId: log.datasetId ?? undefined,
    })
    resultAnswer.value = answer
    detailVisible.value = false
    resultVisible.value = true
    query.page = 1
    await load()
    ElMessage.success('重新执行完成')
  } catch {
    ElMessage.error('重新执行失败，失败记录已写入历史')
    query.page = 1
    await load()
  } finally {
    rerunningId.value = ''
  }
}

function goAsk() {
  void router.push('/insight/ask')
}

function closeResult() {
  resultVisible.value = false
  resultAnswer.value = null
}

onMounted(load)
</script>

<template>
  <div class="page-container query-history">
    <PageHeader title="查询历史" description="问数记录、SQL、脱敏列与重新执行">
      <template #actions>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canAsk" type="primary" @click="goAsk">去问数</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <FilterBar>
        <el-input
          v-model="keyword"
          placeholder="搜索问题、用户、SQL、脱敏列"
          clearable
          style="width: 300px"
        />
        <el-select v-model="statusFilter" placeholder="执行状态" clearable style="width: 140px">
          <el-option label="成功" value="success" />
          <el-option label="失败" value="failed" />
        </el-select>
        <template #actions>
          <el-button :disabled="!hasFilter" @click="resetFilters">清除筛选</el-button>
        </template>
      </FilterBar>

      <div class="query-history__hint">
        当前页客户端过滤，服务端共 {{ total }} 条；分页仍由服务端控制。
      </div>

      <el-alert
        v-if="error"
        type="error"
        show-icon
        :closable="false"
        :title="error"
        class="query-history__alert"
      >
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>

      <el-table
        v-loading="loading"
        :data="filteredLogs"
        size="small"
        border
        @row-click="openDetail"
      >
        <el-table-column label="问题" min-width="280">
          <template #default="{ row }">
            <el-button link type="primary" class="question-link" @click.stop="openDetail(row)">
              {{ row.question }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110">
          <template #default="{ row }">{{ row.username || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag :status="statusOf(row).status" :label="statusOf(row).label" />
          </template>
        </el-table-column>
        <el-table-column label="行数" width="86" align="right">
          <template #default="{ row }">{{ formatRows(row.rowCount) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="96" align="right">
          <template #default="{ row }">{{ formatDuration(row.elapsedMs) }}</template>
        </el-table-column>
        <el-table-column label="脱敏列" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ maskedPreview(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="168">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button
              v-if="canAsk"
              link
              type="primary"
              :loading="rerunningId === row.id"
              @click.stop="rerun(row)"
            >
              重新执行
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !error && !logs.length"
        description="暂无查询历史"
      >
        <el-button v-if="canAsk" type="primary" plain @click="goAsk">去问数</el-button>
      </EmptyState>
      <EmptyState
        v-else-if="!loading && !error && logs.length && !filteredLogs.length"
        description="当前页没有匹配的记录，可翻页或清除筛选"
      >
        <el-button plain @click="resetFilters">清除筛选</el-button>
      </EmptyState>

      <div v-if="total > 0" class="pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="查询详情" size="600px">
      <template v-if="selectedLog">
        <div class="detail-head">
          <div class="detail-head__question">{{ selectedLog.question }}</div>
          <div class="detail-head__tags">
            <StatusTag
              :status="statusOf(selectedLog).status"
              :label="statusOf(selectedLog).label"
            />
            <el-tag v-if="selectedLog.queryState" size="small" type="info">
              {{ selectedLog.queryState }}
            </el-tag>
          </div>
        </div>

        <MetadataPanel :items="detailItems" />

        <el-alert
          v-if="selectedLog.errorMessage || !selectedLog.succeeded"
          class="detail-alert"
          type="error"
          show-icon
          :closable="false"
          :title="selectedLog.errorMessage || '本次问数执行失败'"
        />

        <section class="detail-section">
          <div class="detail-section__title">脱敏列</div>
          <div v-if="selectedMaskedColumns.length" class="detail-tags">
            <el-tag
              v-for="column in selectedMaskedColumns"
              :key="column"
              size="small"
              type="warning"
            >
              {{ column }}
            </el-tag>
          </div>
          <span v-else class="text-muted">无脱敏列</span>
        </section>

        <QuerySqlBlock :sql="selectedLog.generatedSql" title="生成的 SQL" />
      </template>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="canAsk && selectedLog"
          type="primary"
          :loading="rerunningId === selectedLog.id"
          @click="rerun(selectedLog)"
        >
          重新执行
        </el-button>
      </template>
    </el-drawer>

    <el-dialog
      v-model="resultVisible"
      title="重新执行结果"
      width="860px"
      @closed="closeResult"
    >
      <template v-if="resultAnswer">
        <div class="result-head">
          <div>
            <el-tag type="success" size="small">{{ resultAnswer.state }}</el-tag>
            <span class="result-head__summary">{{ resultAnswer.summary || '问数完成' }}</span>
          </div>
          <div class="text-muted">
            {{ formatRows(resultAnswer.rowCount) }} 行 · {{ formatDuration(resultAnswer.elapsedMs) }}
          </div>
        </div>

        <el-alert
          v-if="resultAnswer.maskedColumns.length"
          type="warning"
          show-icon
          :closable="false"
          :title="`已脱敏：${resultAnswer.maskedColumns.join('、')}`"
          class="result-alert"
        />

        <QuerySqlBlock :sql="resultAnswer.sql" title="生成的 SQL" />

        <el-table :data="resultAnswer.rows" size="small" max-height="320" border>
          <el-table-column
            v-for="column in resultColumns"
            :key="column.key"
            :prop="column.key"
            :label="column.label"
            min-width="120"
          />
        </el-table>
        <EmptyState
          v-if="!resultAnswer.rows.length"
          description="本次查询没有返回数据行"
        />
        <div v-if="canExport" class="result-hint text-muted">
          需要导出时请到智能问数页使用导出功能。
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.query-history__hint {
  margin-bottom: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-muted);
}

.query-history__alert {
  margin-bottom: var(--dg-space-md);
}

.question-link {
  padding: 0;
  height: auto;
  text-align: left;
  white-space: normal;
  line-height: 1.5;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}

.detail-head {
  padding-bottom: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.detail-head__question {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
  color: var(--dg-text);
  word-break: break-word;
}

.detail-head__tags {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-sm);
}

.detail-alert {
  margin: var(--dg-space-md) 0;
}

.detail-section {
  margin: var(--dg-space-lg) 0;
}

.detail-section__title {
  margin-bottom: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-muted);
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--dg-space-xs);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
  flex-wrap: wrap;
}

.result-head__summary {
  margin-left: var(--dg-space-sm);
  color: var(--dg-text);
}

.result-alert {
  margin-bottom: var(--dg-space-md);
}

.result-hint {
  margin-top: var(--dg-space-sm);
  font-size: 12px;
}
</style>
