<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { catalogAssetApi } from '@/api/catalog-assets'
import { glossaryApi } from '@/api/glossary'
import { lineageApi } from '@/api/lineage'
import { qualityApi } from '@/api/quality'
import { sensitivityApi } from '@/api/sensitivity'
import { useAuthStore } from '@/stores/auth'
import { confirmDanger } from '@/utils/confirm'
import { formatDuration } from '@/utils/job-status'
import {
  QUALITY_RULE_LABELS,
  QUALITY_SEVERITY_LABELS,
  QUALITY_SEVERITY_TAG,
  metricSummary,
  qualityRunStatusMeta,
} from '@/utils/quality'
import type { CatalogColumn, CatalogDatasetDetail, CatalogSource } from '@/types/catalog-assets'
import type { LineageGraph } from '@/types/lineage'
import type { SensitivityLabel } from '@/types/sensitivity'
import type { AssetGlossaryTerm } from '@/types/glossary'
import type {
  QualityRule,
  QualityRuleType,
  QualityRun,
  QualitySeverity,
  QualitySummary,
} from '@/types/quality'
import { formatBytes, formatDateTime } from '@/utils/format'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import AssetGovernancePanel from '@/components/metadata/AssetGovernancePanel.vue'
import DetailTabs, { type DetailTab } from '@/components/insight/DetailTabs.vue'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'
import SensitivityBadge from '@/components/insight/SensitivityBadge.vue'
import StatusTag from '@/components/insight/StatusTag.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const validTabs = ['overview', 'schema', 'lineage', 'sensitivity', 'quality', 'partitions'] as const
type TabKey = (typeof validTabs)[number]

const tabs: DetailTab[] = [
  { key: 'overview', label: '概览' },
  { key: 'schema', label: '字段' },
  { key: 'lineage', label: '血缘' },
  { key: 'sensitivity', label: '敏感' },
  { key: 'quality', label: '质量' },
  { key: 'partitions', label: '分区' },
]

const assetId = computed(() => String(route.params.id || ''))
const activeTab = ref<TabKey>(normalizeTab(route.query.tab))
const loading = ref(false)
const error = ref('')
const detail = ref<CatalogDatasetDetail | null>(null)
const sources = ref<CatalogSource[]>([])

const sensitivityLoading = ref(false)
const sensitivityError = ref('')
const sensitivityLabels = ref<SensitivityLabel[]>([])

const glossaryLoading = ref(false)
const glossaryError = ref('')
const assetTerms = ref<AssetGlossaryTerm[]>([])

const qualityLoading = ref(false)
const qualityError = ref('')
const qualitySummary = ref<QualitySummary | null>(null)
const qualityRules = ref<QualityRule[]>([])
const qualityRuns = ref<QualityRun[]>([])
const qualityRunning = ref('')
const ruleDrawerVisible = ref(false)
const editingRuleId = ref('')
const ruleSubmitting = ref(false)
const ruleError = ref('')
const ruleForm = reactive({
  ruleType: 'ROW_COUNT_RANGE' as QualityRuleType,
  columnId: '',
  severity: 'MEDIUM' as QualitySeverity,
  enabled: true,
  min: undefined as number | undefined,
  max: undefined as number | undefined,
  maxNullRatio: 0.01,
  maxDuplicateRatio: 0,
  maxAgeHours: 24,
  baselineSchemaHash: '',
})

const ruleTypes = Object.entries(QUALITY_RULE_LABELS).map(([value, label]) => ({ value, label }))
const columnRuleTypes: QualityRuleType[] = ['NOT_NULL', 'UNIQUE', 'FRESHNESS']
const ruleNeedsColumn = computed(() => columnRuleTypes.includes(ruleForm.ruleType))

const lineageLoading = ref(false)
const lineageError = ref('')
const lineageDirection = ref<'both' | 'upstream' | 'downstream'>('both')
const selectedColumnId = ref('')
const lineageGraph = ref<LineageGraph>({ nodes: [], edges: [] })

const columnDrawerVisible = ref(false)
const selectedColumn = ref<CatalogColumn | null>(null)

const dataset = computed(() => detail.value?.dataset ?? null)
const columns = computed(() => detail.value?.columns ?? [])
const partitions = computed(() => detail.value?.partitions ?? [])
const sourceName = computed(() => {
  const source = sources.value.find((item) => item.id === dataset.value?.sourceId)
  return source?.name || dataset.value?.sourceId || '-'
})
const sourceType = computed(() => {
  const source = sources.value.find((item) => item.id === dataset.value?.sourceId)
  return source?.sourceType || '-'
})
const isExternalSource = computed(() =>
  sourceType.value === 'HIVE_METASTORE' || sourceType.value === 'GLUE',
)
const selectedColumnLabel = computed(() => {
  const column = columns.value.find((item) => item.id === selectedColumnId.value)
  return column ? `${column.name}（${column.rawType || column.canonicalType || '-'}）` : '选择字段'
})
const sensitivityByColumn = computed(() => {
  const map = new Map<string, SensitivityLabel>()
  for (const label of sensitivityLabels.value) {
    map.set(label.columnId, label)
  }
  return map
})
const lineageRows = computed(() => lineageGraph.value.nodes
  .filter((node) => node.catalogColumnId !== selectedColumnId.value)
  .map((node) => ({
    id: node.id,
    name: node.displayName || node.qualifiedName,
    qualifiedName: node.qualifiedName,
    nodeType: node.nodeType,
    columnId: node.catalogColumnId || '',
    datasetId: node.catalogDatasetId || '',
  })))
const canReviewSensitivity = computed(() => auth.hasPermission('metadata:sensitivity:review'))
const canGovern = computed(() => auth.hasPermission('metadata:asset:govern'))
const canManageQuality = computed(() => auth.hasPermission('metadata:quality:manage'))
const canRequestAccess = computed(() => auth.hasPermission('metadata:access:request'))
const canReadGlossary = computed(() => auth.hasPermission('metadata:glossary:read'))
const selectedColumnTerms = computed(() => {
  const column = selectedColumn.value
  if (!column) {
    return []
  }
  return assetTerms.value.filter((term) => term.columnId === column.id)
})
const hasSensitivityData = computed(() => sensitivityLabels.value.length > 0)
const prettyProperties = computed(() => {
  const raw = dataset.value?.propertiesJson
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

function normalizeTab(value: unknown): TabKey {
  const text = typeof value === 'string' ? value : ''
  return (validTabs as readonly string[]).includes(text) ? (text as TabKey) : 'overview'
}

function syncTab(tab: string) {
  const next = normalizeTab(tab)
  activeTab.value = next
  router.replace({ query: { ...route.query, tab: next } })
}

function formatPartitionValues(valuesJson: string | null): string {
  if (!valuesJson) {
    return '-'
  }
  try {
    const value = JSON.parse(valuesJson) as Record<string, string>
    return Object.entries(value).map(([key, item]) => `${key}=${item}`).join(', ')
  } catch {
    return valuesJson
  }
}

async function load() {
  loading.value = true
  error.value = ''
  sensitivityLabels.value = []
  lineageGraph.value = { nodes: [], edges: [] }
  try {
    const [result, sourceList, terms] = await Promise.all([
      catalogAssetApi.getDataset(assetId.value),
      catalogAssetApi.listSources().catch(() => []),
      glossaryApi.assetGlossary(assetId.value).catch(() => {
        glossaryError.value = '业务术语加载失败'
        return [] as AssetGlossaryTerm[]
      }),
    ])
    detail.value = result
    sources.value = sourceList
    assetTerms.value = terms
  } catch (e) {
    detail.value = null
    error.value = '资产不存在或无权访问'
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

async function loadSensitivity() {
  if (!detail.value || sensitivityLoading.value) {
    return
  }
  sensitivityLoading.value = true
  sensitivityError.value = ''
  try {
    const page = await sensitivityApi.labels({
      page: 1,
      size: 200,
      datasetId: assetId.value,
    })
    sensitivityLabels.value = page.records
  } catch {
    sensitivityError.value = '敏感标签加载失败'
  } finally {
    sensitivityLoading.value = false
  }
}

async function loadQuality() {
  if (!detail.value || qualityLoading.value) {
    return
  }
  qualityLoading.value = true
  qualityError.value = ''
  try {
    const [summary, rules, runs] = await Promise.all([
      qualityApi.summary(assetId.value),
      qualityApi.listRules({ page: 1, size: 200, datasetId: assetId.value }),
      qualityApi.listRuns({ page: 1, size: 20, datasetId: assetId.value }),
    ])
    qualitySummary.value = summary
    qualityRules.value = rules.records
    qualityRuns.value = runs.records
  } catch {
    qualityError.value = '质量信息加载失败'
    qualitySummary.value = null
    qualityRules.value = []
    qualityRuns.value = []
  } finally {
    qualityLoading.value = false
  }
}

function openCreateRule() {
  editingRuleId.value = ''
  Object.assign(ruleForm, {
    ruleType: 'ROW_COUNT_RANGE',
    columnId: '',
    severity: 'MEDIUM',
    enabled: true,
    min: undefined,
    max: undefined,
    maxNullRatio: 0.01,
    maxDuplicateRatio: 0,
    maxAgeHours: 24,
    baselineSchemaHash: dataset.value?.schemaHash || '',
  })
  ruleError.value = ''
  ruleDrawerVisible.value = true
}

function openEditRule(rule: QualityRule) {
  editingRuleId.value = rule.id
  const config = rule.config || {}
  Object.assign(ruleForm, {
    ruleType: rule.ruleType,
    columnId: rule.columnId || '',
    severity: rule.severity,
    enabled: rule.enabled,
    min: config.min == null ? undefined : Number(config.min),
    max: config.max == null ? undefined : Number(config.max),
    maxNullRatio: config.maxNullRatio == null ? 0.01 : Number(config.maxNullRatio),
    maxDuplicateRatio: config.maxDuplicateRatio == null ? 0 : Number(config.maxDuplicateRatio),
    maxAgeHours: config.maxAgeHours == null ? 24 : Number(config.maxAgeHours),
    baselineSchemaHash: config.baselineSchemaHash == null ? '' : String(config.baselineSchemaHash),
  })
  ruleError.value = ''
  ruleDrawerVisible.value = true
}

function ruleConfig(): Record<string, unknown> {
  switch (ruleForm.ruleType) {
    case 'ROW_COUNT_RANGE': {
      const config: Record<string, unknown> = {}
      if (ruleForm.min !== undefined) {
        config.min = ruleForm.min
      }
      if (ruleForm.max !== undefined) {
        config.max = ruleForm.max
      }
      return config
    }
    case 'NOT_NULL':
      return { maxNullRatio: ruleForm.maxNullRatio }
    case 'UNIQUE':
      return { maxDuplicateRatio: ruleForm.maxDuplicateRatio }
    case 'FRESHNESS':
      return { maxAgeHours: ruleForm.maxAgeHours }
    case 'SCHEMA_DRIFT':
      return ruleForm.baselineSchemaHash
        ? { baselineSchemaHash: ruleForm.baselineSchemaHash.trim() }
        : {}
    default:
      return {}
  }
}

async function submitRule() {
  ruleError.value = ''
  if (ruleNeedsColumn.value && !ruleForm.columnId) {
    ruleError.value = '该规则必须选择字段'
    return
  }
  ruleSubmitting.value = true
  try {
    const payload = {
      datasetId: assetId.value,
      columnId: ruleNeedsColumn.value ? ruleForm.columnId : null,
      ruleType: ruleForm.ruleType,
      config: ruleConfig(),
      severity: ruleForm.severity,
      enabled: ruleForm.enabled,
    }
    if (editingRuleId.value) {
      await qualityApi.updateRule(editingRuleId.value, payload)
    } else {
      await qualityApi.createRule(payload)
    }
    ruleDrawerVisible.value = false
    ElMessage.success('质量规则已保存')
    await loadQuality()
  } catch (e: any) {
    ruleError.value = e?.message || '保存失败'
  } finally {
    ruleSubmitting.value = false
  }
}

async function removeRule(rule: QualityRule) {
  const ok = await confirmDanger(
    `删除「${QUALITY_RULE_LABELS[rule.ruleType] || rule.ruleType}」规则？运行历史会保留。`,
    '删除质量规则',
  )
  if (!ok) {
    return
  }
  await qualityApi.removeRule(rule.id)
  ElMessage.success('质量规则已删除')
  await loadQuality()
}

async function runQualityRule(ruleId: string) {
  qualityRunning.value = ruleId
  try {
    const result = await qualityApi.runRule(ruleId)
    ElMessage.success(
      result.status === 'FAILED'
        ? `运行失败：${result.errorMessage || '未知错误'}`
        : result.passed ? '规则通过' : '规则未通过',
    )
    await loadQuality()
  } finally {
    qualityRunning.value = ''
  }
}

async function runQualityDataset() {
  qualityRunning.value = 'all'
  try {
    const results = await qualityApi.runDataset(assetId.value)
    const failed = results.filter((item) => item.status === 'FAILED' || !item.passed).length
    ElMessage.success(
      `已运行 ${results.length} 条规则，${failed} 条失败/未通过`,
    )
    await loadQuality()
  } finally {
    qualityRunning.value = ''
  }
}

function ruleById(ruleId: string): QualityRule | undefined {
  return qualityRules.value.find((rule) => rule.id === ruleId)
}

function ruleLabel(type: string | null | undefined): string {
  if (!type) {
    return '-'
  }
  return QUALITY_RULE_LABELS[type as QualityRuleType] || type
}

function severityLabel(severity: string): string {
  return QUALITY_SEVERITY_LABELS[severity as QualitySeverity] || severity
}

function severityTag(severity: string): 'danger' | 'warning' | 'primary' | 'info' {
  return QUALITY_SEVERITY_TAG[severity as QualitySeverity] || 'info'
}

function openEditRuleById(ruleId: string) {
  const rule = ruleById(ruleId)
  if (rule) {
    openEditRule(rule)
  }
}

function removeRuleById(ruleId: string) {
  const rule = ruleById(ruleId)
  if (rule) {
    removeRule(rule)
  }
}

async function loadLineage() {
  if (!detail.value || lineageLoading.value) {
    return
  }
  if (!selectedColumnId.value) {
    selectedColumnId.value = columns.value[0]?.id || ''
  }
  if (!selectedColumnId.value) {
    return
  }
  lineageLoading.value = true
  lineageError.value = ''
  try {
    const tasks: Promise<LineageGraph>[] = []
    if (lineageDirection.value === 'both' || lineageDirection.value === 'upstream') {
      tasks.push(lineageApi.upstream(selectedColumnId.value, 2))
    }
    if (lineageDirection.value === 'both' || lineageDirection.value === 'downstream') {
      tasks.push(lineageApi.downstream(selectedColumnId.value, 2))
    }
    const results = await Promise.all(tasks)
    const nodes = new Map<string, LineageGraph['nodes'][number]>()
    const edges = new Map<string, LineageGraph['edges'][number]>()
    for (const graph of results) {
      graph.nodes.forEach((node) => nodes.set(node.id, node))
      graph.edges.forEach((edge) => edges.set(edge.id, edge))
    }
    lineageGraph.value = { nodes: [...nodes.values()], edges: [...edges.values()] }
  } catch {
    lineageError.value = '血缘加载失败'
    lineageGraph.value = { nodes: [], edges: [] }
  } finally {
    lineageLoading.value = false
  }
}

function openColumn(column: CatalogColumn) {
  selectedColumn.value = column
  columnDrawerVisible.value = true
}

function openColumnFromQuery() {
  const columnId = typeof route.query.columnId === 'string' ? route.query.columnId : ''
  if (!columnId) {
    return
  }
  const column = columns.value.find((item) => item.id === columnId)
  if (column) {
    openColumn(column)
  }
}

function openLineage(columnId?: string) {
  const id = columnId || selectedColumnId.value || columns.value[0]?.id
  if (!id) {
    ElMessage.warning('该数据集没有字段，无法查看血缘')
    return
  }
  router.push({
    path: '/metadata/lineage',
    query: {
      columnId: id,
      // 带上数据集 ID，血缘工作台才能直接加载字段选择器，不必反查。
      datasetId: assetId.value,
    },
  })
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(window.location.href)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请从地址栏复制')
  }
}

function openSensitivityReview(label?: any) {
  router.push({
    path: '/metadata/sensitivity',
    query: {
      datasetId: assetId.value,
      ...(label ? { reviewId: label.id } : {}),
    },
  })
}

function requestAccess(label?: any) {
  const target = label || sensitivityLabels.value[0]
  if (!target?.category || !target?.level) {
    ElMessage.warning('标签缺少类别或级别，无法发起申请')
    return
  }
  router.push({
    path: '/governance/access-requests',
    query: {
      apply: '1',
      category: target.category,
      level: target.level,
    },
  })
}

watch(() => route.query.tab, (value) => {
  activeTab.value = normalizeTab(value)
})

watch(() => route.query.columnId, () => {
  openColumnFromQuery()
})

watch(activeTab, (value) => {
  if (typeof route.query.tab !== 'string' || route.query.tab !== value) {
    router.replace({ query: { ...route.query, tab: value } })
  }
  if (value === 'schema' || value === 'sensitivity') {
    loadSensitivity()
  }
  if (value === 'lineage') {
    loadLineage()
  }
  if (value === 'quality') {
    loadQuality()
  }
})

watch(selectedColumnId, () => {
  if (activeTab.value === 'lineage') {
    loadLineage()
  }
})

watch(lineageDirection, () => {
  if (activeTab.value === 'lineage') {
    loadLineage()
  }
})

onMounted(async () => {
  await load()
  if (!detail.value) {
    return
  }
  if (activeTab.value === 'schema' || activeTab.value === 'sensitivity') {
    await loadSensitivity()
  }
  if (activeTab.value === 'lineage') {
    await loadLineage()
  }
  if (activeTab.value === 'quality') {
    await loadQuality()
  }
  openColumnFromQuery()
})
</script>

<template>
  <div class="page-container asset-detail" v-loading="loading">
    <PageHeader
      :title="dataset?.name || '资产详情'"
      :description="dataset?.qualifiedName || assetId"
    >
      <template #actions>
        <el-button :disabled="!detail" @click="copyLink">复制链接</el-button>
        <el-button type="primary" :disabled="!detail" @click="openLineage()">打开血缘</el-button>
      </template>
    </PageHeader>

    <el-result
      v-if="error"
      icon="warning"
      title="无法打开资产"
      :sub-title="error"
    >
      <template #extra>
        <el-button @click="router.push('/metadata/catalog')">返回数据目录</el-button>
      </template>
    </el-result>

    <template v-else-if="detail">
      <el-row :gutter="12" class="asset-detail__stats">
        <el-col :xs="12" :sm="6">
          <el-card shadow="never">
            <div class="stat-label">字段数</div>
            <div class="stat-value">{{ dataset?.columnCount ?? columns.length }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never">
            <div class="stat-label">分区数</div>
            <div class="stat-value">{{ partitions.length }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never">
            <div class="stat-label">行数</div>
            <div class="stat-value">{{ dataset?.rowCount ?? '-' }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never">
            <div class="stat-label">大小</div>
            <div class="stat-value">{{ formatBytes(dataset?.sizeBytes) }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never">
        <DetailTabs
          :model-value="activeTab"
          :tabs="tabs"
          @update:model-value="syncTab"
        >
          <template #overview>
            <el-alert
              v-if="isExternalSource"
              class="asset-detail__alert"
              type="info"
              show-icon
              :closable="false"
              title="该资产来自 External Catalog：这里只有元数据，不支持通过平台直接查询表数据。"
            />
            <MetadataPanel
              title="资产属性"
              :items="[
                { label: '限定名', value: dataset?.qualifiedName },
                { label: '来源', value: sourceName },
                { label: '来源类型', value: sourceType },
                { label: '类型 / 格式', value: `${dataset?.kind || '-'} / ${dataset?.format || '-'}` },
                { label: '位置', value: dataset?.location || '-' },
                { label: 'schema hash', value: dataset?.schemaHash || '-' },
                { label: '最近修改', value: formatDateTime(dataset?.lastModifiedAt) },
                { label: '采集更新', value: formatDateTime(dataset?.updatedAt) },
              ]"
            />
            <div class="asset-detail__comment">
              <div class="stat-label">描述</div>
              <p>{{ dataset?.comment || '暂无描述' }}</p>
            </div>
            <el-collapse v-if="prettyProperties" class="asset-detail__properties">
              <el-collapse-item title="属性 JSON" name="properties">
                <pre>{{ prettyProperties }}</pre>
              </el-collapse-item>
            </el-collapse>
            <AssetGovernancePanel
              :asset-id="assetId"
              :can-edit="canGovern"
            />

            <section class="asset-detail__glossary">
              <div class="asset-detail__section-head">
                <h4>业务术语</h4>
                <div>
                  <el-button
                    v-if="canReadGlossary"
                    link
                    type="primary"
                    @click="router.push('/metadata/glossary')"
                  >
                    术语管理
                  </el-button>
                  <el-button link @click="load">刷新</el-button>
                </div>
              </div>
              <el-alert
                v-if="glossaryError"
                type="warning"
                show-icon
                :closable="false"
                :title="glossaryError"
              />
              <EmptyState
                v-if="!assetTerms.length && !glossaryError"
                description="暂无业务术语"
              >
                <el-button
                  v-if="canReadGlossary"
                  plain
                  @click="router.push('/metadata/glossary')"
                >
                  去术语管理
                </el-button>
              </EmptyState>
              <el-table v-else :data="assetTerms" size="small" border>
                <el-table-column prop="term" label="术语" width="150" />
                <el-table-column prop="definition" label="定义" min-width="260" show-overflow-tooltip />
                <el-table-column label="业务域" width="120">
                  <template #default="{ row }">{{ row.domainName || '-' }}</template>
                </el-table-column>
                <el-table-column label="绑定字段" width="140">
                  <template #default="{ row }">{{ row.columnName || '整个数据集' }}</template>
                </el-table-column>
              </el-table>
            </section>
          </template>

          <template #schema>
            <el-table :data="columns" size="small" border @row-click="openColumn">
              <el-table-column prop="ordinal" label="#" width="56" />
              <el-table-column prop="name" label="字段" min-width="170" />
              <el-table-column prop="rawType" label="原始类型" width="150" />
              <el-table-column prop="canonicalType" label="规范类型" width="130" />
              <el-table-column label="约束" width="150">
                <template #default="{ row }">
                  <el-tag v-if="row.primaryKey" size="small" type="warning">PK</el-tag>
                  <el-tag v-if="row.partitionKey" size="small" type="success" style="margin-left: 4px">
                    PARTITION
                  </el-tag>
                  <span v-if="!row.primaryKey && !row.partitionKey" class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="敏感" width="150">
                <template #default="{ row }">
                  <SensitivityBadge
                    v-if="sensitivityByColumn.get(row.id)"
                    :level="sensitivityByColumn.get(row.id)?.level"
                    :category="sensitivityByColumn.get(row.id)?.category"
                    :status="sensitivityByColumn.get(row.id)?.status"
                  />
                  <span v-else class="text-muted">未标注</span>
                </template>
              </el-table-column>
              <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip />
            </el-table>
            <el-alert
              v-if="sensitivityError"
              class="asset-detail__alert"
              type="warning"
              show-icon
              :closable="false"
              :title="sensitivityError"
            />
          </template>

          <template #sensitivity>
            <div class="asset-detail__tab-actions">
              <el-button
                v-if="canRequestAccess"
                type="warning"
                plain
                :disabled="!sensitivityLabels.length"
                @click="requestAccess()"
              >
                申请临时访问
              </el-button>
              <el-button
                v-if="canReviewSensitivity"
                type="primary"
                plain
                @click="openSensitivityReview()"
              >
                去敏感工作台复核
              </el-button>
              <el-button link @click="loadSensitivity">刷新标签</el-button>
            </div>
            <el-alert
              v-if="sensitivityError"
              class="asset-detail__alert"
              type="warning"
              show-icon
              :closable="false"
              :title="sensitivityError"
            />
            <el-table v-loading="sensitivityLoading" :data="sensitivityLabels" size="small" border>
              <el-table-column prop="columnName" label="字段" min-width="150" />
              <el-table-column label="级别" width="130">
                <template #default="{ row }">
                  <SensitivityBadge :level="row.level" :category="row.category" />
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <StatusTag :status="row.status" />
                </template>
              </el-table-column>
              <el-table-column prop="confidence" label="置信度" width="100" />
              <el-table-column prop="detectedBy" label="检测方式" width="110" />
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ formatDateTime(row.detectedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="170">
                <template #default="{ row }">
                  <el-button
                    v-if="canReviewSensitivity"
                    link
                    type="primary"
                    @click="openSensitivityReview(row)"
                  >
                    复核
                  </el-button>
                  <el-button
                    v-if="canRequestAccess"
                    link
                    type="warning"
                    :disabled="!row.category || !row.level"
                    @click="requestAccess(row)"
                  >
                    申请访问
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-if="!sensitivityLoading && !hasSensitivityData" description="该数据集暂无敏感标签" />
          </template>

          <template #quality>
            <div class="asset-detail__tab-actions">
              <el-button
                v-if="canManageQuality"
                type="primary"
                :loading="qualityRunning === 'all'"
                @click="runQualityDataset"
              >
                运行全部启用规则
              </el-button>
              <el-button v-if="canManageQuality" @click="openCreateRule">新建规则</el-button>
              <el-button link @click="loadQuality">刷新</el-button>
            </div>

            <el-alert
              v-if="qualityError"
              class="asset-detail__alert"
              type="warning"
              show-icon
              :closable="false"
              :title="qualityError"
            />

            <div v-loading="qualityLoading" class="quality-summary">
              <div class="quality-summary__score">
                <div class="stat-label">质量分数</div>
                <div class="quality-summary__value">
                  {{ qualitySummary?.score ?? '未评估' }}
                  <span v-if="qualitySummary?.score != null" class="quality-summary__unit">/ 100</span>
                </div>
              </div>
              <div class="quality-summary__meta">
                <div v-if="qualitySummary">
                  已评估 {{ qualitySummary.evaluatedRules }} / {{ qualitySummary.totalRules }} 条规则，
                  通过 {{ qualitySummary.passedRules }} 条，失败 {{ qualitySummary.failedRules }} 条
                </div>
                <div class="text-muted">
                  分数只覆盖已配置且已运行的规则，不代表全量数据质量。
                </div>
              </div>
            </div>

            <el-table :data="qualitySummary?.rules ?? []" size="small" border>
              <el-table-column label="规则" width="130">
                <template #default="{ row }">
                  {{ ruleLabel(row.ruleType) }}
                </template>
              </el-table-column>
              <el-table-column label="严重度" width="86">
                <template #default="{ row }">
                  <el-tag :type="severityTag(row.severity)" size="small">
                    {{ severityLabel(row.severity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="字段" width="120">
                <template #default="{ row }">{{ row.columnName || '数据集' }}</template>
              </el-table-column>
              <el-table-column label="最新状态" width="130">
                <template #default="{ row }">
                  <el-tag :type="qualityRunStatusMeta(row.latestStatus).type" size="small">
                    {{ qualityRunStatusMeta(row.latestStatus).label }}
                  </el-tag>
                  <el-tag
                    v-if="row.latestStatus === 'SUCCEEDED'"
                    :type="row.latestPassed ? 'success' : 'danger'"
                    size="small"
                    style="margin-left: 4px"
                  >
                    {{ row.latestPassed ? '通过' : '未通过' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="指标 / 错误" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.errorMessage || metricSummary(row.ruleType, row.metric) }}
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="86" align="right">
                <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
              </el-table-column>
              <el-table-column label="运行时间" width="168">
                <template #default="{ row }">{{ formatDateTime(row.executedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canManageQuality"
                    link
                    type="primary"
                    :loading="qualityRunning === row.ruleId"
                    @click="runQualityRule(row.ruleId)"
                  >
                    运行
                  </el-button>
                  <template v-if="canManageQuality && ruleById(row.ruleId)">
                    <el-button link type="primary" @click="openEditRuleById(row.ruleId)">
                      编辑
                    </el-button>
                    <el-button
                      link
                      type="danger"
                      @click="removeRuleById(row.ruleId)"
                    >
                      删除
                    </el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-if="!qualityLoading && !(qualitySummary?.rules.length)"
              description="该资产还没有质量规则"
            >
              <el-button
                v-if="canManageQuality"
                type="primary"
                plain
                @click="openCreateRule"
              >
                新建第一条规则
              </el-button>
            </EmptyState>

            <div class="asset-detail__section-head" style="margin-top: 16px">
              <h4>最近运行</h4>
              <span class="text-muted">最近 20 次</span>
            </div>
            <el-table :data="qualityRuns" size="small" border>
              <el-table-column label="规则" width="130">
                <template #default="{ row }">
                  {{ ruleLabel(row.ruleType) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <el-tag :type="qualityRunStatusMeta(row.status).type" size="small">
                    {{ qualityRunStatusMeta(row.status).label }}
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
              <el-table-column label="指标 / 错误" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.errorMessage || metricSummary(row.ruleType || '', row.metric) }}
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="86" align="right">
                <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
              </el-table-column>
              <el-table-column label="执行人" width="110">
                <template #default="{ row }">{{ row.executedBy || '-' }}</template>
              </el-table-column>
              <el-table-column label="时间" width="168">
                <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-if="!qualityLoading && !qualityRuns.length"
              description="还没有运行记录，点击“运行全部启用规则”开始评估"
            />
          </template>

          <template #lineage>
            <div class="asset-detail__lineage-bar">
              <el-select
                v-model="selectedColumnId"
                filterable
                placeholder="选择字段"
                style="width: 260px"
              >
                <el-option
                  v-for="column in columns"
                  :key="column.id"
                  :label="`${column.name}（${column.rawType || column.canonicalType || '-'}）`"
                  :value="column.id"
                />
              </el-select>
              <el-radio-group v-model="lineageDirection" size="small">
                <el-radio-button value="both">双向</el-radio-button>
                <el-radio-button value="upstream">上游</el-radio-button>
                <el-radio-button value="downstream">下游</el-radio-button>
              </el-radio-group>
              <el-button link @click="loadLineage">刷新</el-button>
              <el-button type="primary" plain @click="openLineage(selectedColumnId)">
                打开完整血缘
              </el-button>
            </div>
            <el-alert
              v-if="lineageError"
              class="asset-detail__alert"
              type="warning"
              show-icon
              :closable="false"
              :title="lineageError"
            />
            <el-table v-loading="lineageLoading" :data="lineageRows" size="small" border>
              <el-table-column prop="name" label="节点" min-width="220" show-overflow-tooltip />
              <el-table-column prop="nodeType" label="类型" width="110" />
              <el-table-column prop="qualifiedName" label="限定名" min-width="260" show-overflow-tooltip />
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openLineage(row.columnId)">血缘</el-button>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-if="!lineageLoading && !lineageRows.length"
              description="该字段暂无上下游血缘"
            />
            <div class="text-muted asset-detail__hint">
              当前为列级血缘预览：{{ selectedColumnLabel }}
            </div>
          </template>

          <template #partitions>
            <EmptyState
              v-if="!partitions.length"
              :description="dataset?.format === 'PARQUET' ? '该 Parquet 数据集暂无分区' : '该数据集没有分区信息'"
            />
            <el-table v-else :data="partitions" size="small" border>
              <el-table-column label="分区值" min-width="220">
                <template #default="{ row }">{{ formatPartitionValues(row.valuesJson) }}</template>
              </el-table-column>
              <el-table-column prop="fileCount" label="文件数" width="90" align="right" />
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
              </el-table-column>
              <el-table-column prop="rowCount" label="行数" width="100" align="right" />
              <el-table-column prop="path" label="路径" min-width="260" show-overflow-tooltip />
              <el-table-column label="最近修改" width="170">
                <template #default="{ row }">{{ formatDateTime(row.lastModifiedAt) }}</template>
              </el-table-column>
            </el-table>
          </template>
        </DetailTabs>
      </el-card>
    </template>

    <el-drawer v-model="ruleDrawerVisible" :title="editingRuleId ? '编辑质量规则' : '新建质量规则'" size="540px">
      <el-form label-width="110px">
        <el-form-item label="规则类型" required>
          <el-select v-model="ruleForm.ruleType" :disabled="!!editingRuleId" style="width: 100%">
            <el-option
              v-for="item in ruleTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <div class="text-muted">
            P2 只支持内置规则；阈值由平台生成只读聚合 SQL，不接受自定义 SQL。
          </div>
        </el-form-item>
        <el-form-item v-if="ruleNeedsColumn" label="字段" required>
          <el-select v-model="ruleForm.columnId" filterable placeholder="选择字段" style="width: 100%">
            <el-option
              v-for="column in columns"
              :key="column.id"
              :label="`${column.name}（${column.rawType || column.canonicalType || '-'}）`"
              :value="column.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option label="严重" value="CRITICAL" />
            <el-option label="高" value="HIGH" />
            <el-option label="中" value="MEDIUM" />
            <el-option label="低" value="LOW" />
          </el-select>
          <div class="text-muted">权重：严重 5 / 高 3 / 中 2 / 低 1，用于加权分数。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>

        <template v-if="ruleForm.ruleType === 'ROW_COUNT_RANGE'">
          <el-form-item label="最小行数">
            <el-input-number v-model="ruleForm.min" :min="0" controls-position="right" />
          </el-form-item>
          <el-form-item label="最大行数">
            <el-input-number v-model="ruleForm.max" :min="0" controls-position="right" />
          </el-form-item>
        </template>
        <el-form-item v-if="ruleForm.ruleType === 'NOT_NULL'" label="最大空值率">
          <el-input-number
            v-model="ruleForm.maxNullRatio"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="4"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType === 'UNIQUE'" label="最大重复率">
          <el-input-number
            v-model="ruleForm.maxDuplicateRatio"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="4"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType === 'FRESHNESS'" label="最大年龄(小时)">
          <el-input-number
            v-model="ruleForm.maxAgeHours"
            :min="1"
            :max="8760"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType === 'SCHEMA_DRIFT'" label="基线 schema">
          <el-input
            v-model="ruleForm.baselineSchemaHash"
            placeholder="留空使用当前 schema hash"
          />
          <div class="text-muted">当前：{{ dataset?.schemaHash || '-' }}</div>
        </el-form-item>

        <el-alert
          v-if="ruleError"
          type="error"
          show-icon
          :closable="false"
          :title="ruleError"
        />
      </el-form>
      <template #footer>
        <el-button @click="ruleDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="ruleSubmitting" @click="submitRule">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="columnDrawerVisible" title="字段详情" size="480px">
      <template v-if="selectedColumn">
        <MetadataPanel
          :items="[
            { label: '字段', value: selectedColumn.name },
            { label: '原始类型', value: selectedColumn.rawType || '-' },
            { label: '规范类型', value: selectedColumn.canonicalType || '-' },
            { label: '精度 / 小数位', value: `${selectedColumn.precision ?? '-'} / ${selectedColumn.scale ?? '-'}` },
            { label: '可空', value: selectedColumn.nullable ? '是' : '否' },
            { label: '主键', value: selectedColumn.primaryKey ? '是' : '否' },
            { label: '分区键', value: selectedColumn.partitionKey ? '是' : '否' },
            { label: '注释', value: selectedColumn.comment || '-' },
          ]"
        />
        <div class="asset-detail__drawer-section">
          <div class="stat-label">业务术语</div>
          <div v-if="selectedColumnTerms.length" class="asset-detail__term-list">
            <div v-for="term in selectedColumnTerms" :key="term.bindingId" class="asset-detail__term">
              <strong>{{ term.term }}</strong>
              <span>{{ term.definition }}</span>
            </div>
          </div>
          <span v-else class="text-muted">该字段暂无已发布术语</span>
        </div>
        <div class="asset-detail__drawer-actions">
          <el-button type="primary" plain @click="openLineage(selectedColumn.id)">查看血缘</el-button>
          <el-button @click="openSensitivityReview()">查看敏感标签</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.asset-detail__stats {
  margin-bottom: var(--dg-space-lg);
}

.asset-detail__stats .el-col {
  margin-bottom: var(--dg-space-sm);
}

.stat-label {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: var(--dg-space-xs);
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--dg-text);
  font-variant-numeric: tabular-nums;
}

.asset-detail__comment {
  margin-top: var(--dg-space-lg);
  padding-top: var(--dg-space-md);
  border-top: 1px solid var(--dg-border);
}

.asset-detail__properties {
  margin-top: var(--dg-space-lg);
}

.asset-detail__properties pre {
  margin: 0;
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border-radius: var(--dg-radius);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  overflow-x: auto;
}

.asset-detail__comment p {
  margin: var(--dg-space-xs) 0 0;
  font-size: 13px;
  color: var(--dg-text-secondary);
  line-height: 1.7;
}

.asset-detail__alert {
  margin: var(--dg-space-md) 0;
}

.asset-detail__tab-actions,
.asset-detail__lineage-bar {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
  flex-wrap: wrap;
}

.asset-detail__hint {
  margin-top: var(--dg-space-sm);
}

.asset-detail__drawer-actions {
  display: flex;
  gap: var(--dg-space-sm);
  margin-top: var(--dg-space-lg);
}

.asset-detail__glossary {
  margin-top: var(--dg-space-lg);
  padding-top: var(--dg-space-md);
  border-top: 1px solid var(--dg-border);
}

.asset-detail__glossary h4 {
  margin: 0;
  font-size: 14px;
}

.asset-detail__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-sm);
}

.asset-detail__drawer-section {
  margin-top: var(--dg-space-lg);
}

.asset-detail__term-list {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
  margin-top: var(--dg-space-xs);
}

.asset-detail__term {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--dg-space-sm);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.asset-detail__term strong {
  font-size: 13px;
}

.asset-detail__term span {
  font-size: 12px;
  color: var(--dg-text-secondary);
}

.quality-summary {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xl);
  padding: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border-radius: var(--dg-radius);
}

.quality-summary__value {
  font-size: 30px;
  font-weight: 700;
  color: var(--dg-text);
  font-variant-numeric: tabular-nums;
}

.quality-summary__unit {
  font-size: 13px;
  font-weight: 400;
  color: var(--dg-text-muted);
  margin-left: 4px;
}

.quality-summary__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--dg-text-secondary);
}
</style>
