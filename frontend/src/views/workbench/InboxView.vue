<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { inboxApi } from '@/api/governance'
import { useAuthStore } from '@/stores/auth'
import type { InboxItem, InboxSourceType } from '@/types/governance'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()

const rows = ref<InboxItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const truncated = ref(false)
const query = reactive({
  page: 1,
  size: 20,
  sourceType: '',
  state: 'OPEN',
  severity: '',
  keyword: '',
})

let filterTimer: ReturnType<typeof setTimeout> | null = null

const canExecution = computed(() => auth.hasPermission('runtime:execution:read'))
const canAlert = computed(() => auth.hasPermission('governance:alert:read'))
const canSensitivity = computed(() => auth.hasPermission('metadata:sensitivity:read'))
const canManage = computed(() => auth.hasPermission('governance:inbox:manage'))

const sourceOptions = computed(() => [
  { value: 'EXECUTION', label: '失败执行', show: canExecution.value },
  { value: 'ALERT', label: '未处理告警', show: canAlert.value },
  { value: 'SENSITIVITY', label: '待复核敏感', show: canSensitivity.value },
].filter((item) => item.show))

const sourceMeta: Record<InboxSourceType, { label: string; type: 'danger' | 'warning' | 'info' }> = {
  EXECUTION: { label: '失败执行', type: 'danger' },
  ALERT: { label: '告警', type: 'warning' },
  SENSITIVITY: { label: '敏感', type: 'info' },
}

const severityMeta: Record<string, { label: string; type: 'danger' | 'warning' | 'info' }> = {
  CRITICAL: { label: '紧急', type: 'danger' },
  HIGH: { label: '高', type: 'warning' },
  MEDIUM: { label: '中', type: 'info' },
  LOW: { label: '低', type: 'info' },
}

function sourceLabel(type: InboxSourceType) {
  return sourceMeta[type]?.label || type
}

function sourceType(type: InboxSourceType) {
  return sourceMeta[type]?.type || 'info'
}

function severityOf(item: any) {
  return severityMeta[item.severity] || { label: item.severity, type: 'info' as const }
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await inboxApi.list({
      page: query.page,
      size: query.size,
      sourceType: query.sourceType || undefined,
      state: query.state || undefined,
      severity: query.severity || undefined,
      keyword: query.keyword.trim() || undefined,
    })
    rows.value = page.records
    total.value = page.total
    truncated.value = page.truncated
  } catch {
    rows.value = []
    total.value = 0
    truncated.value = false
    error.value = '治理收件箱加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  if (filterTimer) {
    clearTimeout(filterTimer)
    filterTimer = null
  }
  query.page = 1
  void load()
}

function scheduleFilter() {
  if (filterTimer) {
    clearTimeout(filterTimer)
  }
  filterTimer = setTimeout(onFilterChange, 300)
}

function resetFilters() {
  query.sourceType = ''
  query.state = 'OPEN'
  query.severity = ''
  query.keyword = ''
  query.page = 1
  void load()
}

function openItem(item: any) {
  void router.push(item.route)
}

async function toggleItem(item: any) {
  const dismiss = item.state === 'OPEN'
  try {
    await ElMessageBox.confirm(
      dismiss
        ? '仅从收件箱移除，不会改变来源对象的状态。继续？'
        : '将条目恢复到待处理列表？',
      dismiss ? '移出收件箱' : '恢复条目',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    if (dismiss) {
      await inboxApi.dismiss(item.sourceType, item.sourceId)
      ElMessage.success('已移出收件箱')
    } else {
      await inboxApi.reopen(item.sourceType, item.sourceId)
      ElMessage.success('已恢复到收件箱')
    }
    await load()
  } catch {
    ElMessage.error(dismiss ? '移出失败' : '恢复失败')
  }
}

function goBack() {
  void router.push('/workbench')
}

onMounted(load)

onBeforeUnmount(() => {
  if (filterTimer) {
    clearTimeout(filterTimer)
  }
})
</script>

<template>
  <div class="page-container inbox-page">
    <PageHeader title="治理收件箱" description="失败执行、未处理告警与待复核敏感标签的统一队列">
      <template #actions>
        <el-button @click="goBack">返回工作台</el-button>
        <el-button type="primary" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="truncated"
      type="warning"
      show-icon
      :closable="false"
      class="inbox-alert"
      title="每个来源只展示最近 200 条；更早记录请到对应来源页面查看。"
    />

    <el-card shadow="never">
      <FilterBar>
        <el-select
          v-model="query.sourceType"
          placeholder="全部来源"
          clearable
          style="width: 150px"
          @change="onFilterChange"
        >
          <el-option
            v-for="option in sourceOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-select
          v-model="query.state"
          placeholder="收件箱状态"
          clearable
          style="width: 140px"
          @change="onFilterChange"
        >
          <el-option label="待处理" value="OPEN" />
          <el-option label="已移出" value="DISMISSED" />
          <el-option label="全部" value="ALL" />
        </el-select>
        <el-select
          v-model="query.severity"
          placeholder="严重度"
          clearable
          style="width: 120px"
          @change="onFilterChange"
        >
          <el-option label="紧急" value="CRITICAL" />
          <el-option label="高" value="HIGH" />
          <el-option label="中" value="MEDIUM" />
          <el-option label="低" value="LOW" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="搜索标题、摘要、来源 ID"
          clearable
          style="width: 260px"
          @input="scheduleFilter"
          @keyup.enter="onFilterChange"
        />
        <template #actions>
          <el-button :disabled="!query.sourceType && !query.severity && !query.keyword && query.state === 'OPEN'" @click="resetFilters">
            清除筛选
          </el-button>
        </template>
      </FilterBar>

      <div class="inbox-hint">
        共 {{ total }} 条；移出收件箱不会解决告警、复核敏感标签或改变执行状态。
      </div>

      <el-alert
        v-if="error"
        type="error"
        show-icon
        :closable="false"
        :title="error"
        class="inbox-alert"
      >
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>

      <el-table v-if="loading || rows.length" v-loading="loading" :data="rows" size="small" border>
        <el-table-column label="来源" width="106">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceType(row.sourceType)">
              {{ sourceLabel(row.sourceType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="严重度" width="82">
          <template #default="{ row }">
            <el-tag size="small" :type="severityOf(row).type">
              {{ severityOf(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="条目" min-width="320">
          <template #default="{ row }">
            <el-button link type="primary" class="inbox-title" @click="openItem(row)">
              {{ row.title }}
            </el-button>
            <div class="inbox-summary">{{ row.summary || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源状态" width="110">
          <template #default="{ row }">{{ row.sourceState || '-' }}</template>
        </el-table-column>
        <el-table-column label="发生时间" width="168">
          <template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="收件箱状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.state === 'OPEN' ? 'warning' : 'info'">
              {{ row.state === 'OPEN' ? '待处理' : '已移出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.state === 'OPEN' ? 'warning' : 'primary'" @click="toggleItem(row)">
              {{ row.state === 'OPEN' ? '移出' : '恢复' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !error && !rows.length"
        :description="query.state === 'DISMISSED' ? '没有已移出的条目' : '收件箱没有待处理项'"
      />

      <div v-if="total > 0" class="pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="onFilterChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.inbox-alert {
  margin-bottom: var(--dg-space-md);
}

.inbox-hint {
  margin-bottom: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-muted);
}

.inbox-title {
  height: auto;
  padding: 0;
  text-align: left;
  white-space: normal;
  line-height: 1.45;
}

.inbox-summary {
  margin-top: 2px;
  font-size: 12px;
  color: var(--dg-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}
</style>
