<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { qualityApi } from '@/api/quality'
import { usePageRefresh } from '@/composables/usePageRefresh'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { formatDuration } from '@/utils/job-status'
import {
  QUALITY_RULE_LABELS,
  QUALITY_SEVERITY_LABELS,
  QUALITY_SEVERITY_TAG,
  metricSummary,
  qualityRunStatusMeta,
} from '@/utils/quality'
import type { QualityRun } from '@/types/quality'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'

/**
 * 质量总览。
 *
 * 以“运行事实”为中心：失败资产/最近运行都从 md_quality_run 读，
 * 不把结果覆盖到规则行上。分数只覆盖已配置规则，这里展示的是运行明细。
 */
defineOptions({ name: 'QualityView' })

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const error = ref('')
const runs = ref<QualityRun[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  status: '',
  result: 'FAILED' as '' | 'PASSED' | 'FAILED',
  ruleType: '',
})

const canManage = computed(() => auth.hasPermission('metadata:quality:manage'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await qualityApi.listRuns({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      passed: query.result === 'PASSED' ? true
        : query.result === 'FAILED' ? false : undefined,
      ruleType: query.ruleType || undefined,
      latest: true,
    })
    runs.value = page.records
    total.value = page.total
  } catch {
    error.value = '质量运行加载失败'
    runs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  query.page = 1
  load()
}

function resetFilters() {
  query.status = ''
  query.result = 'FAILED'
  query.ruleType = ''
  query.page = 1
  load()
}

function openAsset(run: QualityRun | Record<string, any>) {
  router.push({ path: `/metadata/assets/${run.datasetId}`, query: { tab: 'quality' } })
}

function statusOf(run: QualityRun | Record<string, any>) {
  return qualityRunStatusMeta(run.status)
}

function ruleLabel(type: string | null | undefined): string {
  if (!type) {
    return '-'
  }
  return QUALITY_RULE_LABELS[type as keyof typeof QUALITY_RULE_LABELS] || type
}

usePageRefresh(load)
</script>

<template>
  <div class="page-container quality-page">
    <PageHeader
      title="资产质量"
      description="内置规则的手动运行结果。分数只覆盖已配置规则，不代表全量数据质量。"
    >
      <template #actions>
        <el-button @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <FilterBar>
        <el-select
          v-model="query.result"
          clearable
          placeholder="评估结果"
          style="width: 140px"
          @change="onFilterChange"
        >
          <el-option label="未通过" value="FAILED" />
          <el-option label="通过" value="PASSED" />
        </el-select>
        <el-select
          v-model="query.status"
          clearable
          placeholder="运行状态"
          style="width: 140px"
          @change="onFilterChange"
        >
          <el-option label="成功" value="SUCCEEDED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="运行中" value="RUNNING" />
        </el-select>
        <el-select
          v-model="query.ruleType"
          clearable
          placeholder="规则类型"
          style="width: 160px"
          @change="onFilterChange"
        >
          <el-option
            v-for="(label, value) in QUALITY_RULE_LABELS"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <template #actions>
          <el-button v-if="query.status || query.ruleType || query.result !== 'FAILED'" @click="resetFilters">
            清除筛选
          </el-button>
        </template>
      </FilterBar>

      <el-alert
        v-if="error"
        type="error"
        show-icon
        :closable="false"
        :title="error"
        class="quality-page__alert"
      >
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>

      <div class="quality-page__section-title">最近运行</div>

      <el-table v-loading="loading" :data="runs" size="small" border>
        <el-table-column label="资产" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAsset(row)">
              {{ row.datasetQualifiedName || row.datasetName || row.datasetId }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="规则" width="140">
          <template #default="{ row }">
            {{ ruleLabel(row.ruleType) }}
          </template>
        </el-table-column>
        <el-table-column label="字段" width="120">
          <template #default="{ row }">{{ row.columnName || '数据集' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusOf(row).type" size="small">
              {{ statusOf(row).label }}
            </el-tag>
            <el-tag
              v-if="row.status === 'SUCCEEDED'"
              :type="row.passed ? 'success' : 'danger'"
              size="small"
              style="margin-left: 4px"
            >
              {{ row.passed ? '通过' : '未通过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.errorMessage || metricSummary(row.ruleType || '', row.metric) }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="执行人" width="110">
          <template #default="{ row }">{{ row.executedBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="168">
          <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAsset(row)">质量 Tab</el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !error && !runs.length"
        :description="query.status || query.ruleType || query.result !== 'FAILED'
          ? '没有匹配的质量运行'
          : '没有未通过的质量运行'"
      >
        <el-button v-if="canManage" type="primary" plain @click="router.push('/metadata/catalog')">
          去数据目录配置规则
        </el-button>
      </EmptyState>

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
.quality-page__alert {
  margin-bottom: var(--dg-space-md);
}

.quality-page__section-title {
  margin-bottom: var(--dg-space-sm);
  font-size: 14px;
  font-weight: 600;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}
</style>
