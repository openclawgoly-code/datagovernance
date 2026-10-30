<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { catalogAssetApi } from '@/api/catalog-assets'
import { lineageApi } from '@/api/lineage'
import { sensitivityApi } from '@/api/sensitivity'
import { useAuthStore } from '@/stores/auth'
import type { CatalogColumn, CatalogDatasetDetail, CatalogSource } from '@/types/catalog-assets'
import type { LineageGraph } from '@/types/lineage'
import type { SensitivityLabel } from '@/types/sensitivity'
import { formatBytes, formatDateTime } from '@/utils/format'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import DetailTabs, { type DetailTab } from '@/components/insight/DetailTabs.vue'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'
import SensitivityBadge from '@/components/insight/SensitivityBadge.vue'
import StatusTag from '@/components/insight/StatusTag.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const validTabs = ['overview', 'schema', 'lineage', 'sensitivity', 'partitions'] as const
type TabKey = (typeof validTabs)[number]

const tabs: DetailTab[] = [
  { key: 'overview', label: '概览' },
  { key: 'schema', label: '字段' },
  { key: 'lineage', label: '血缘' },
  { key: 'sensitivity', label: '敏感' },
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
    const [result, sourceList] = await Promise.all([
      catalogAssetApi.getDataset(assetId.value),
      catalogAssetApi.listSources().catch(() => []),
    ])
    detail.value = result
    sources.value = sourceList
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
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button
                    v-if="canReviewSensitivity"
                    link
                    type="primary"
                    @click="openSensitivityReview(row)"
                  >
                    复核
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-if="!sensitivityLoading && !hasSensitivityData" description="该数据集暂无敏感标签" />
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
</style>
