<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { dataSourceApi } from '@/api/datasource'
import { executionApi } from '@/api/job'
import { alertApi } from '@/api/governance'
import { sensitivityApi } from '@/api/sensitivity'
import { insightApi } from '@/api/insight'
import { catalogAssetApi } from '@/api/catalog-assets'
import { useAuthStore } from '@/stores/auth'
import { ALL_STATUSES, statusMeta } from '@/utils/datasource-status'
import { formatDateTime } from '@/utils/format'
import { formatDuration } from '@/utils/job-status'
import type { DataSource, DataSourceStatus } from '@/types/datasource'
import type { Execution } from '@/types/job'
import type { Alert } from '@/types/governance'
import type { SensitivityLabel } from '@/types/sensitivity'
import type { InsightQueryLog } from '@/types/insight'
import type { CatalogDataset } from '@/types/catalog-assets'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import EntityLink from '@/components/insight/EntityLink.vue'
import SensitivityBadge from '@/components/insight/SensitivityBadge.vue'
import StatusTag from '@/components/insight/StatusTag.vue'

type CardKey = 'datasources' | 'executions' | 'alerts' | 'sensitivity' | 'queries' | 'assets'

const auth = useAuthStore()
const router = useRouter()

const loading = reactive<Record<CardKey, boolean>>({
  datasources: false,
  executions: false,
  alerts: false,
  sensitivity: false,
  queries: false,
  assets: false,
})
const errors = reactive<Record<CardKey, string>>({
  datasources: '',
  executions: '',
  alerts: '',
  sensitivity: '',
  queries: '',
  assets: '',
})

const dataSources = ref<DataSource[]>([])
const dataSourceTotal = ref(0)
const failedExecutions = ref<Execution[]>([])
const failedExecutionTotal = ref(0)
const openAlerts = ref<Alert[]>([])
const openAlertTotal = ref(0)
const pendingLabels = ref<SensitivityLabel[]>([])
const pendingLabelTotal = ref(0)
const recentQueries = ref<InsightQueryLog[]>([])
const recentAssets = ref<CatalogDataset[]>([])
const refreshedAt = ref('')

const canDatasource = computed(() => auth.hasPermission('metadata:datasource:read'))
const canExecution = computed(() => auth.hasPermission('runtime:execution:read'))
const canAlert = computed(() => auth.hasPermission('governance:alert:read'))
const canSensitivity = computed(() => auth.hasPermission('metadata:sensitivity:read'))
const canInsight = computed(() => auth.hasPermission('insight:read'))
const canAsk = computed(() => auth.hasPermission('insight:ask'))
const canAsset = computed(() => auth.hasPermission('metadata:asset:read'))
const canInbox = computed(() => auth.hasPermission('governance:inbox:read'))

const datasourceSummary = computed(() => {
  const counts = new Map<DataSourceStatus, number>()
  for (const status of ALL_STATUSES) {
    counts.set(status, 0)
  }
  for (const item of dataSources.value) {
    counts.set(item.status, (counts.get(item.status) ?? 0) + 1)
  }
  return ALL_STATUSES.map((status) => ({
    status,
    count: counts.get(status) ?? 0,
    meta: statusMeta(status),
  })).filter((entry) => entry.count > 0)
})
const unhealthySources = computed(() =>
  dataSources.value
    .filter((item) => item.status === 'UNREACHABLE' || item.status === 'DRAFT')
    .slice(0, 3),
)
const attentionCards = computed(() =>
  [
    {
      key: 'executions' as const,
      title: '失败执行',
      count: failedExecutionTotal.value,
      to: '/ops/executions',
      error: errors.executions,
      loading: loading.executions,
      show: canExecution.value,
      hint: 'FAILED / TIMEOUT',
    },
    {
      key: 'alerts' as const,
      title: '未处理告警',
      count: openAlertTotal.value,
      to: '/ops/alerts',
      error: errors.alerts,
      loading: loading.alerts,
      show: canAlert.value,
      hint: '未解决告警',
    },
    {
      key: 'sensitivity' as const,
      title: '待复核敏感标签',
      count: pendingLabelTotal.value,
      to: '/metadata/sensitivity',
      error: errors.sensitivity,
      loading: loading.sensitivity,
      show: canSensitivity.value,
      hint: 'SUGGESTED',
    },
  ].filter((card) => card.show),
)
const attentionCount = computed(() =>
  attentionCards.value.reduce((sum, card) => sum + card.count, 0),
)
const hasAttentionSection = computed(() => attentionCards.value.length > 0)
const quickLinks = computed(() =>
  [
    { label: '智能问数', to: '/insight/ask', show: canAsk.value },
    { label: '数据目录', to: '/metadata/catalog', show: canAsset.value },
    { label: '查询历史', to: '/insight/queries', show: canInsight.value },
    { label: '敏感复核', to: '/metadata/sensitivity', show: canSensitivity.value },
    { label: '执行记录', to: '/ops/executions', show: canExecution.value },
    { label: '告警信息', to: '/ops/alerts', show: canAlert.value },
    { label: '治理收件箱', to: '/workbench/inbox', show: canInbox.value },
    { label: '数据源', to: '/metadata/datasources', show: canDatasource.value },
  ].filter((link) => link.show),
)
const headerDescription = computed(() => {
  const workspace = auth.currentWorkspace?.name || '未选择空间'
  const refreshed = refreshedAt.value ? ` · 更新于 ${formatDateTime(refreshedAt.value)}` : ''
  return `${workspace} · 权限感知的今日概览${refreshed}`
})

function dateValue(value: string | null | undefined): number {
  return value ? new Date(value).getTime() : 0
}

function isOpenAlert(alert: Alert): boolean {
  return alert.status !== 'RESOLVED' && alert.status !== 'ACKNOWLEDGED'
}

function alertTagType(alert: Alert): 'danger' | 'warning' | 'info' {
  if (alert.severity === 'CRITICAL') {
    return 'danger'
  }
  return alert.severity === 'WARNING' ? 'warning' : 'info'
}

function executionTagType(execution: Execution): 'danger' | 'warning' | 'info' {
  if (execution.status === 'FAILED') {
    return 'danger'
  }
  return execution.status === 'TIMEOUT' ? 'warning' : 'info'
}

async function loadDataSources() {
  if (!canDatasource.value) {
    return
  }
  loading.datasources = true
  errors.datasources = ''
  try {
    // 概览页只看一页；真正的监控统计在运维监控模块。
    const page = await dataSourceApi.list({ page: 1, size: 200 })
    dataSources.value = page.records
    dataSourceTotal.value = page.total
  } catch {
    dataSources.value = []
    dataSourceTotal.value = 0
    errors.datasources = '数据源状态不可用'
  } finally {
    loading.datasources = false
  }
}

async function loadFailedExecutions() {
  if (!canExecution.value) {
    return
  }
  loading.executions = true
  errors.executions = ''
  try {
    const [failed, timeout] = await Promise.all([
      executionApi.list({ page: 1, size: 5, status: 'FAILED' }),
      executionApi.list({ page: 1, size: 5, status: 'TIMEOUT' }),
    ])
    failedExecutionTotal.value = failed.total + timeout.total
    failedExecutions.value = [...failed.records, ...timeout.records]
      .sort((a, b) => dateValue(b.submittedAt) - dateValue(a.submittedAt))
      .slice(0, 5)
  } catch {
    failedExecutions.value = []
    failedExecutionTotal.value = 0
    errors.executions = '执行记录不可用'
  } finally {
    loading.executions = false
  }
}

async function loadOpenAlerts() {
  if (!canAlert.value) {
    return
  }
  loading.alerts = true
  errors.alerts = ''
  try {
    const [summary, page] = await Promise.all([
      alertApi.summary(),
      alertApi.list({ page: 1, size: 50 }),
    ])
    openAlertTotal.value = summary.openCount
    openAlerts.value = page.records.filter(isOpenAlert).slice(0, 5)
  } catch {
    openAlerts.value = []
    openAlertTotal.value = 0
    errors.alerts = '告警信息不可用'
  } finally {
    loading.alerts = false
  }
}

async function loadPendingLabels() {
  if (!canSensitivity.value) {
    return
  }
  loading.sensitivity = true
  errors.sensitivity = ''
  try {
    const page = await sensitivityApi.labels({ page: 1, size: 5, status: 'SUGGESTED' })
    pendingLabels.value = page.records
    pendingLabelTotal.value = page.total
  } catch {
    pendingLabels.value = []
    pendingLabelTotal.value = 0
    errors.sensitivity = '敏感标签不可用'
  } finally {
    loading.sensitivity = false
  }
}

async function loadRecentQueries() {
  if (!canInsight.value) {
    return
  }
  loading.queries = true
  errors.queries = ''
  try {
    const page = await insightApi.queries({ page: 1, size: 5 })
    recentQueries.value = page.records
  } catch {
    recentQueries.value = []
    errors.queries = '最近问数不可用'
  } finally {
    loading.queries = false
  }
}

async function loadRecentAssets() {
  if (!canAsset.value) {
    return
  }
  loading.assets = true
  errors.assets = ''
  try {
    const sources = await catalogAssetApi.listSources()
    const pages = await Promise.allSettled(
      // Catalog 列表按限定名排序，没有“最近采集”端点。每个来源取前 100 条
      // 再按 updatedAt 排，1000 表环境下只做近似，精确时间线留给 P1 的资产搜索。
      sources.map((source) => catalogAssetApi.listDatasets(source.id, { page: 1, size: 100 })),
    )
    const merged = new Map<string, CatalogDataset>()
    let fulfilled = 0
    for (const page of pages) {
      if (page.status === 'fulfilled') {
        fulfilled += 1
        for (const item of page.value.records) {
          merged.set(item.id, item)
        }
      }
    }
    if (sources.length > 0 && fulfilled === 0) {
      throw new Error('catalog unavailable')
    }
    recentAssets.value = [...merged.values()]
      .sort((a, b) => dateValue(b.updatedAt) - dateValue(a.updatedAt))
      .slice(0, 5)
  } catch {
    recentAssets.value = []
    errors.assets = '最近资产不可用'
  } finally {
    loading.assets = false
  }
}

async function loadAll() {
  if (!auth.workspaceId) {
    return
  }
  await Promise.allSettled([
    loadDataSources(),
    loadFailedExecutions(),
    loadOpenAlerts(),
    loadPendingLabels(),
    loadRecentQueries(),
    loadRecentAssets(),
  ])
  refreshedAt.value = new Date().toISOString()
}

function go(path: string) {
  void router.push(path)
}

watch(
  () => auth.workspaceId,
  () => {
    if (auth.workspaceId) {
      void loadAll()
    }
  },
)

onMounted(loadAll)
</script>

<template>
  <div class="page-container workbench">
    <el-alert
      v-if="!auth.workspaceId"
      type="warning"
      show-icon
      :closable="false"
      title="尚未选择空间"
      description="平台内每个对象都归属于某个空间,请先在右上角选择一个空间。"
    />

    <template v-else>
      <PageHeader title="工作台" :description="headerDescription">
        <template #actions>
          <el-button type="primary" @click="loadAll">刷新概览</el-button>
        </template>
      </PageHeader>

      <el-card v-if="quickLinks.length" shadow="never" class="quick-card">
        <div class="quick-card__inner">
          <span class="quick-card__label">快捷入口</span>
          <div class="quick-card__actions">
            <el-button
              v-for="link in quickLinks"
              :key="link.to"
              plain
              @click="go(link.to)"
            >
              {{ link.label }}
            </el-button>
          </div>
        </div>
      </el-card>

      <template v-if="hasAttentionSection">
        <div class="section-head">
          <div>
            <h3>需要处理</h3>
            <span class="text-muted">失败执行、未处理告警与待复核标签</span>
          </div>
          <div class="section-head__right">
            <el-button v-if="canInbox" link type="primary" @click="go('/workbench/inbox')">
              打开收件箱
            </el-button>
            <el-tag :type="attentionCount > 0 ? 'danger' : 'success'" size="large">
              {{ attentionCount > 0 ? `${attentionCount} 项` : '已清空' }}
            </el-tag>
          </div>
        </div>

        <el-row :gutter="16" class="attention-row">
          <el-col
            v-for="card in attentionCards"
            :key="card.key"
            :xs="24"
            :md="8"
          >
            <el-card shadow="never" class="attention-card" v-loading="card.loading">
              <template #header>
                <div class="card-head">
                  <div>
                    <span class="card-head__title">{{ card.title }}</span>
                    <span class="card-head__hint">{{ card.hint }}</span>
                  </div>
                  <el-tag :type="card.count > 0 ? 'danger' : 'success'" size="small">
                    {{ card.count }}
                  </el-tag>
                </div>
              </template>

              <el-alert
                v-if="card.error"
                type="error"
                show-icon
                :closable="false"
                :title="card.error"
              />
              <template v-else>
                <div v-if="card.count === 0" class="card-empty">
                  暂无需要处理的{{ card.title }}
                </div>
                <div v-else class="card-list">
                  <template v-if="card.key === 'executions'">
                    <div v-for="execution in failedExecutions" :key="execution.id" class="list-item">
                      <div class="list-item__title">
                        <span>{{ execution.jobName || execution.jobRefTypeDisplayName }}</span>
                        <el-tag :type="executionTagType(execution)" size="small">
                          {{ execution.statusDisplayName || execution.status }}
                        </el-tag>
                      </div>
                      <div class="list-item__meta">
                        {{ execution.message || '执行失败' }} ·
                        {{ formatDateTime(execution.submittedAt) }}
                      </div>
                    </div>
                  </template>

                  <template v-else-if="card.key === 'alerts'">
                    <div v-for="alert in openAlerts" :key="alert.id" class="list-item">
                      <div class="list-item__title">
                        <span>{{ alert.title }}</span>
                        <el-tag :type="alertTagType(alert)" size="small">
                          {{ alert.severity }}
                        </el-tag>
                      </div>
                      <div class="list-item__meta">
                        {{ alert.statusDisplayName }} · {{ formatDateTime(alert.triggeredAt) }}
                      </div>
                    </div>
                  </template>

                  <template v-else>
                    <div v-for="label in pendingLabels" :key="label.id" class="list-item">
                      <div class="list-item__title">
                        <EntityLink
                          kind="column"
                          :id="label.columnId"
                          :dataset-id="label.datasetId"
                          :label="label.columnName"
                        />
                        <SensitivityBadge :level="label.level" :category="label.category" />
                      </div>
                      <div class="list-item__meta">
                        {{ label.datasetName || label.datasetQualifiedName || label.datasetId }}
                      </div>
                    </div>
                  </template>
                </div>
              </template>

              <div class="card-footer">
                <el-button link type="primary" @click="go(card.to)">查看全部</el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </template>

      <el-alert
        v-else-if="quickLinks.length"
        type="success"
        show-icon
        :closable="false"
        title="当前账号没有需要处理模块的权限，可继续使用快捷入口。"
        class="no-attention"
      />

      <el-row :gutter="16" class="overview-row">
        <el-col v-if="canDatasource" :xs="24" :md="8">
          <el-card shadow="never" class="overview-card" v-loading="loading.datasources">
            <template #header>
              <div class="card-head">
                <span class="card-head__title">数据源健康</span>
                <el-button link type="primary" @click="go('/metadata/datasources')">
                  查看
                </el-button>
              </div>
            </template>
            <el-alert
              v-if="errors.datasources"
              type="error"
              show-icon
              :closable="false"
              :title="errors.datasources"
            />
            <template v-else>
              <div class="health-total">
                <span class="health-total__value">{{ dataSourceTotal }}</span>
                <span class="text-muted">个数据源</span>
                <el-tag v-if="unhealthySources.length" type="warning" size="small">
                  {{ unhealthySources.length }} 个需关注
                </el-tag>
              </div>
              <div class="health-statuses">
                <el-tag
                  v-for="entry in datasourceSummary"
                  :key="entry.status"
                  :type="entry.meta.type"
                  size="small"
                >
                  {{ entry.meta.label }} {{ entry.count }}
                </el-tag>
              </div>
              <div v-if="unhealthySources.length" class="health-list">
                <div v-for="source in unhealthySources" :key="source.id" class="health-item">
                  <span>{{ source.name }}</span>
                  <el-tag :type="statusMeta(source.status).type" size="small">
                    {{ statusMeta(source.status).label }}
                  </el-tag>
                </div>
              </div>
              <div v-else class="card-empty">数据源状态正常</div>
              <div v-if="dataSourceTotal > dataSources.length" class="card-hint">
                仅统计前 {{ dataSources.length }} 个数据源
              </div>
            </template>
          </el-card>
        </el-col>

        <el-col v-if="canInsight" :xs="24" :md="8">
          <el-card shadow="never" class="overview-card" v-loading="loading.queries">
            <template #header>
              <div class="card-head">
                <span class="card-head__title">最近问数</span>
                <el-button link type="primary" @click="go('/insight/queries')">
                  查看
                </el-button>
              </div>
            </template>
            <el-alert
              v-if="errors.queries"
              type="error"
              show-icon
              :closable="false"
              :title="errors.queries"
            />
            <template v-else>
              <div v-if="recentQueries.length" class="card-list">
                <div v-for="queryLog in recentQueries" :key="queryLog.id" class="list-item">
                  <div class="list-item__title">
                    <span class="clamp-one">{{ queryLog.question }}</span>
                    <StatusTag
                      :status="queryLog.succeeded ? 'SUCCEEDED' : 'FAILED'"
                      :label="queryLog.succeeded ? '成功' : '失败'"
                    />
                  </div>
                  <div class="list-item__meta">
                    {{ queryLog.username || '-' }} ·
                    {{ formatDuration(queryLog.elapsedMs) }} ·
                    {{ formatDateTime(queryLog.createdAt) }}
                  </div>
                </div>
              </div>
              <div v-else class="card-empty">还没有问数记录</div>
            </template>
          </el-card>
        </el-col>

        <el-col v-if="canAsset" :xs="24" :md="8">
          <el-card shadow="never" class="overview-card" v-loading="loading.assets">
            <template #header>
              <div class="card-head">
                <span class="card-head__title">最近资产</span>
                <el-button link type="primary" @click="go('/metadata/catalog')">
                  查看
                </el-button>
              </div>
            </template>
            <el-alert
              v-if="errors.assets"
              type="error"
              show-icon
              :closable="false"
              :title="errors.assets"
            />
            <template v-else>
              <div v-if="recentAssets.length" class="card-list">
                <div v-for="asset in recentAssets" :key="asset.id" class="list-item">
                  <div class="list-item__title">
                    <EntityLink
                      kind="dataset"
                      :id="asset.id"
                      :label="asset.qualifiedName"
                    />
                    <el-tag size="small" type="info">{{ asset.format || asset.kind }}</el-tag>
                  </div>
                  <div class="list-item__meta">
                    {{ asset.columnCount ?? '-' }} 字段 · {{ formatDateTime(asset.updatedAt) }}
                  </div>
                </div>
              </div>
              <div v-else class="card-empty">还没有采集到资产</div>
            </template>
          </el-card>
        </el-col>
      </el-row>

      <EmptyState
        v-if="!quickLinks.length"
        description="当前账号没有任何工作台模块的查看权限，请联系空间管理员。"
      />
    </template>
  </div>
</template>

<style scoped>
.quick-card {
  margin-bottom: var(--dg-space-md);
}

.quick-card__inner {
  display: flex;
  align-items: center;
  gap: var(--dg-space-md);
  flex-wrap: wrap;
}

.quick-card__label {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.quick-card__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  flex-wrap: wrap;
}

.quick-card__actions :deep(.el-button) {
  margin-left: 0;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin: var(--dg-space-lg) 0 var(--dg-space-sm);
}

.section-head h3 {
  margin: 0 0 2px;
  font-size: 16px;
  color: var(--dg-text);
}

.section-head__right {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
}

.attention-row,
.overview-row {
  margin-bottom: var(--dg-space-sm);
}

.attention-card,
.overview-card {
  height: 100%;
  margin-bottom: var(--dg-space-md);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
}

.card-head__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--dg-text);
}

.card-head__hint {
  margin-left: var(--dg-space-xs);
  font-size: 11px;
  color: var(--dg-text-muted);
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
}

.list-item {
  min-width: 0;
  padding-bottom: var(--dg-space-sm);
  border-bottom: 1px solid var(--dg-border);
}

.list-item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.list-item__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  font-size: 13px;
  color: var(--dg-text);
}

.list-item__title > span:first-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-item__meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--dg-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-empty {
  padding: var(--dg-space-md) 0;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.card-hint {
  margin-top: var(--dg-space-xs);
  font-size: 11px;
  color: var(--dg-text-muted);
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-sm);
}

.health-total {
  display: flex;
  align-items: baseline;
  gap: var(--dg-space-xs);
  margin-bottom: var(--dg-space-sm);
}

.health-total__value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--dg-text);
  font-variant-numeric: tabular-nums;
}

.health-statuses {
  display: flex;
  flex-wrap: wrap;
  gap: var(--dg-space-xs);
}

.health-list {
  margin-top: var(--dg-space-md);
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-xs);
}

.health-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-secondary);
}

.no-attention {
  margin: var(--dg-space-lg) 0;
}

.clamp-one {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
