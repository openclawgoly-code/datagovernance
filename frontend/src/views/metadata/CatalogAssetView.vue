<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { catalogAssetApi } from '@/api/catalog-assets'
import { credentialApi } from '@/api/credential'
import { governanceApi } from '@/api/catalog-governance'
import { dataSourceApi } from '@/api/datasource'
import { usePageRefresh } from '@/composables/usePageRefresh'
import { confirmDanger } from '@/utils/confirm'
import { formatBytes, formatDateTime } from '@/utils/format'
import type { Credential } from '@/types/credential'
import type { DataSource } from '@/types/datasource'
import type { BusinessDomain } from '@/types/catalog-governance'
import type {
  CatalogDataset,
  CatalogDatasetDetail,
  CatalogSource,
  CatalogSourceType,
} from '@/types/catalog-assets'

/**
 * 数据目录(P10.1/P10.2)。
 *
 * 左边是“来源”(JDBC 数据源或 Parquet 目录),右边是该来源采集到的数据集。
 * 数据集的字段/分区在抽屉里看 —— 列表页只带摘要,正文/分区不随列表一起拉。
 */

defineOptions({ name: 'CatalogAssetView' })

const router = useRouter()
const sources = ref<CatalogSource[]>([])
const selected = ref<CatalogSource | null>(null)
const datasets = ref<CatalogDataset[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  domainId: '',
  owner: '',
  certificationStatus: '',
})
const domains = ref<BusinessDomain[]>([])

const certificationOptions = [
  { value: 'CERTIFIED', label: '已认证' },
  { value: 'DEPRECATED', label: '已废弃' },
  { value: 'NONE', label: '未认证' },
]

const dataSources = ref<DataSource[]>([])
const sourceDialogVisible = ref(false)
const sourceSubmitting = ref(false)
const sourceForm = reactive({
  name: '',
  sourceType: 'JDBC' as CatalogSourceType,
  dataSourceId: '',
  rootPath: '',
  credentialId: '',
  uris: '',
  warehouse: '',
  region: '',
  catalogId: '',
  includeDatabases: '',
  excludeDatabases: '',
})
const credentials = ref<Credential[]>([])

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<CatalogDatasetDetail | null>(null)

const sourceTypes = [
  {
    value: 'JDBC' as CatalogSourceType,
    label: '关系型 / MPP 数据库',
    hint: '把库 → 模式 → 表 → 字段采集进 PG',
  },
  {
    value: 'PARQUET_DIRECTORY' as CatalogSourceType,
    label: 'Parquet 目录',
    hint: '本地/NFS 或 S3/MinIO/OSS 上的 Parquet 湖',
  },
  {
    value: 'HIVE_METASTORE' as CatalogSourceType,
    label: 'Hive Metastore（External）',
    hint: '只读采集 database/table/view/column/partition，不查询表数据',
  },
  {
    value: 'GLUE' as CatalogSourceType,
    label: 'AWS Glue（External）',
    hint: '只读采集 Glue Catalog；凭据可选，默认使用 AWS provider chain',
  },
]

const isExternalType = computed(() =>
  sourceForm.sourceType === 'HIVE_METASTORE' || sourceForm.sourceType === 'GLUE',
)

const compatibleDataSources = computed(() =>
  dataSources.value.filter((ds) =>
    sourceForm.sourceType === 'JDBC'
      ? ds.family === 'RELATIONAL' || ds.family === 'MPP'
      : ds.family === 'OBJECT_STORAGE',
  ),
)

const canCreate = computed(() => {
  if (!sourceForm.name.trim() || !sourceForm.sourceType) {
    return false
  }
  if (sourceForm.sourceType === 'HIVE_METASTORE') {
    return !!sourceForm.uris.trim()
  }
  if (sourceForm.sourceType === 'GLUE') {
    return !!sourceForm.region.trim()
  }
  return !!sourceForm.dataSourceId
})

async function loadSources() {
  sources.value = await catalogAssetApi.listSources()
  if (selected.value) {
    selected.value = sources.value.find((s) => s.id === selected.value?.id) ?? null
  }
  if (!selected.value && sources.value.length > 0) {
    selected.value = sources.value[0]
  }
  if (selected.value) {
    await loadDatasets()
  } else {
    datasets.value = []
    total.value = 0
  }
}

async function loadDatasets() {
  if (!selected.value) {
    return
  }
  loading.value = true
  try {
    const page = await catalogAssetApi.listDatasets(selected.value.id, {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      domainId: query.domainId || undefined,
      owner: query.owner.trim() || undefined,
      certificationStatus: query.certificationStatus || undefined,
    })
    datasets.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function selectSource(source: CatalogSource) {
  selected.value = source
  query.page = 1
  query.keyword = ''
  await loadDatasets()
}

async function loadAllDataSources() {
  if (dataSources.value.length > 0) {
    return
  }
  const page = await dataSourceApi.list({ page: 1, size: 200 })
  dataSources.value = page.records
}

async function loadCredentials() {
  if (credentials.value.length > 0) {
    return
  }
  try {
    credentials.value = await credentialApi.list()
  } catch {
    credentials.value = []
  }
}

async function openCreate() {
  Object.assign(sourceForm, {
    name: '',
    sourceType: 'JDBC',
    dataSourceId: '',
    rootPath: '',
    credentialId: '',
    uris: '',
    warehouse: '',
    region: '',
    catalogId: '',
    includeDatabases: '',
    excludeDatabases: '',
  })
  await loadAllDataSources()
  await loadCredentials()
  sourceDialogVisible.value = true
}

function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function buildConfigJson(): string | undefined {
  switch (sourceForm.sourceType) {
    case 'PARQUET_DIRECTORY':
      return sourceForm.rootPath.trim()
        ? JSON.stringify({ rootPath: sourceForm.rootPath.trim() })
        : undefined
    case 'HIVE_METASTORE': {
      const config: Record<string, unknown> = { uris: splitList(sourceForm.uris) }
      if (sourceForm.warehouse.trim()) {
        config.warehouse = sourceForm.warehouse.trim()
      }
      if (splitList(sourceForm.includeDatabases).length) {
        config.includeDatabases = splitList(sourceForm.includeDatabases)
      }
      if (splitList(sourceForm.excludeDatabases).length) {
        config.excludeDatabases = splitList(sourceForm.excludeDatabases)
      }
      return JSON.stringify(config)
    }
    case 'GLUE': {
      const config: Record<string, unknown> = { region: sourceForm.region.trim() }
      if (sourceForm.catalogId.trim()) {
        config.catalogId = sourceForm.catalogId.trim()
      }
      if (splitList(sourceForm.includeDatabases).length) {
        config.includeDatabases = splitList(sourceForm.includeDatabases)
      }
      if (splitList(sourceForm.excludeDatabases).length) {
        config.excludeDatabases = splitList(sourceForm.excludeDatabases)
      }
      return JSON.stringify(config)
    }
    default:
      return undefined
  }
}

async function submitSource() {
  if (!canCreate.value) {
    ElMessage.warning(isExternalType.value
      ? '请填写名称与外部目录连接配置'
      : '请填写名称并选择数据源')
    return
  }
  sourceSubmitting.value = true
  try {
    await catalogAssetApi.createSource({
      name: sourceForm.name.trim(),
      sourceType: sourceForm.sourceType,
      dataSourceId: isExternalType.value ? null : sourceForm.dataSourceId,
      credentialId: isExternalType.value ? sourceForm.credentialId || null : null,
      configJson: buildConfigJson(),
    })
    ElMessage.success('目录来源已创建。点击“采集”开始读取元数据')
    sourceDialogVisible.value = false
    await loadSources()
  } finally {
    sourceSubmitting.value = false
  }
}

async function refreshSource() {
  if (!selected.value) {
    return
  }
  const result = await catalogAssetApi.refreshSource(selected.value.id)
  ElMessage.success(
    `采集完成:${result.datasets} 个数据集 / ${result.columns} 个字段(耗时 ${result.durationMillis} ms)`,
  )
  if (result.warnings?.length) {
    ElMessage.warning(`采集完成但有 ${result.warnings.length} 条警告：${result.warnings[0]}`)
  }
  await loadSources()
}

async function removeSource() {
  if (!selected.value) {
    return
  }
  const ok = await confirmDanger(
    `删除目录来源「${selected.value.name}」?已采集的资产会保留,但不再刷新。`,
    '删除目录来源',
  )
  if (!ok) {
    return
  }
  await catalogAssetApi.removeSource(selected.value.id)
  selected.value = null
  await loadSources()
}

function openAssetDetail(row: any) {
  router.push(`/metadata/assets/${row.id}`)
}

async function openDetail(row: CatalogDataset) {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await catalogAssetApi.getDataset(row.id)
  } finally {
    detailLoading.value = false
  }
}

function onFilterChange() {
  query.page = 1
  loadDatasets()
}

function formatPartitionValues(valuesJson: string | null): string {
  if (!valuesJson) {
    return '-'
  }
  try {
    const value = JSON.parse(valuesJson) as Record<string, string>
    return Object.entries(value).map(([k, v]) => `${k}=${v}`).join(', ')
  } catch {
    return valuesJson
  }
}

function sourceConfigHint(source: CatalogSource): string {
  if (!source.configJson) {
    return ''
  }
  try {
    const config = JSON.parse(source.configJson) as Record<string, unknown>
    if (source.sourceType === 'HIVE_METASTORE') {
      const uris = config.uris
      return Array.isArray(uris) ? uris.join(', ') : String(uris || '')
    }
    if (source.sourceType === 'GLUE') {
      return config.region ? `region=${config.region}` : ''
    }
    if (source.sourceType === 'PARQUET_DIRECTORY') {
      return config.rootPath ? `rootPath=${config.rootPath}` : ''
    }
  } catch {
    return ''
  }
  return ''
}

function certificationMeta(status?: string | null): {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
} {
  return {
    CERTIFIED: { label: '已认证', type: 'success' as const },
    DEPRECATED: { label: '已废弃', type: 'danger' as const },
    NONE: { label: '未认证', type: 'info' as const },
  }[status || 'NONE'] || { label: '未认证', type: 'info' as const }
}

function resetGovernanceFilters() {
  query.domainId = ''
  query.owner = ''
  query.certificationStatus = ''
  onFilterChange()
}

async function loadDomains() {
  try {
    domains.value = await governanceApi.listDomains()
  } catch {
    domains.value = []
  }
}

async function bootstrap() {
  await loadDomains()
  await loadSources()
}

usePageRefresh(bootstrap)
</script>

<template>
  <div class="catalog-page">
    <div class="page-header">
      <div>
        <h2>数据目录</h2>
        <p class="text-muted">
          把数据库与 Parquet 湖的元数据采集到 PostgreSQL。这里只读结构，不搬运数据。
        </p>
      </div>
      <el-button v-permission="'metadata:asset:manage'" type="primary" @click="openCreate">
        新建目录来源
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="7">
        <el-card shadow="never" class="sources-card">
          <template #header>
            <div class="card-header">
              <span>来源</span>
              <el-tag size="small" type="info">{{ sources.length }}</el-tag>
            </div>
          </template>
          <el-empty v-if="sources.length === 0" description="还没有目录来源" />
          <div
            v-for="source in sources"
            :key="source.id"
            class="source-item"
            :class="{ 'source-item--active': selected?.id === source.id }"
            @click="selectSource(source)"
          >
            <div class="source-item__title">
              <span>{{ source.name }}</span>
              <el-tag size="small" :type="source.sourceType === 'JDBC' ? 'primary' : 'success'">
                {{ source.sourceType }}
              </el-tag>
              <el-tag
                v-if="source.sourceType === 'HIVE_METASTORE' || source.sourceType === 'GLUE'"
                size="small"
                type="warning"
              >
                External
              </el-tag>
            </div>
            <div class="text-muted">
              最近采集:{{ formatDateTime(source.lastRefreshedAt) }}
            </div>
            <div v-if="sourceConfigHint(source)" class="text-muted source-item__config">
              {{ sourceConfigHint(source) }}
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="17">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <div>
                <span>{{ selected?.name ?? '数据集' }}</span>
                <span v-if="selected" class="text-muted" style="margin-left: 8px">
                  {{ selected.sourceType }}
                </span>
              </div>
              <div v-if="selected">
                <el-button
                  v-permission="'metadata:asset:refresh'"
                  size="small"
                  type="primary"
                  @click="refreshSource"
                >
                  采集
                </el-button>
                <el-button
                  v-permission="'metadata:asset:manage'"
                  size="small"
                  type="danger"
                  plain
                  @click="removeSource"
                >
                  删除来源
                </el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!selected" description="先创建或选择一个目录来源" />
          <template v-else>
            <div class="toolbar">
              <el-input
                v-model="query.keyword"
                clearable
                placeholder="搜索表 / 数据集"
                style="width: 240px"
                @keyup.enter="onFilterChange"
                @clear="onFilterChange"
              />
              <el-select
                v-model="query.domainId"
                clearable
                placeholder="业务域"
                style="width: 150px"
                @change="onFilterChange"
              >
                <el-option
                  v-for="domain in domains"
                  :key="domain.id"
                  :label="domain.name"
                  :value="domain.id"
                />
              </el-select>
              <el-input
                v-model="query.owner"
                clearable
                placeholder="负责人"
                style="width: 140px"
                @keyup.enter="onFilterChange"
                @clear="onFilterChange"
              />
              <el-select
                v-model="query.certificationStatus"
                clearable
                placeholder="认证状态"
                style="width: 130px"
                @change="onFilterChange"
              >
                <el-option
                  v-for="item in certificationOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-button @click="onFilterChange">搜索</el-button>
              <el-button
                v-if="query.domainId || query.owner || query.certificationStatus"
                link
                @click="resetGovernanceFilters"
              >
                清除治理筛选
              </el-button>
            </div>

            <el-table v-loading="loading" :data="datasets" @row-click="openDetail">
              <el-table-column prop="qualifiedName" label="数据集" min-width="220">
                <template #default="{ row }">
                  <span>{{ row.qualifiedName }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="kind" label="类型" width="120" />
              <el-table-column label="业务域" width="120">
                <template #default="{ row }">{{ row.domainName || '-' }}</template>
              </el-table-column>
              <el-table-column label="负责人" width="120">
                <template #default="{ row }">{{ row.ownerUsername || '-' }}</template>
              </el-table-column>
              <el-table-column label="认证" width="96">
                <template #default="{ row }">
                  <el-tag :type="certificationMeta(row.certificationStatus).type" size="small">
                    {{ certificationMeta(row.certificationStatus).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="format" label="格式" width="90" />
              <el-table-column prop="columnCount" label="字段数" width="86" align="right" />
              <el-table-column label="更新于" width="170">
                <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click.stop="openAssetDetail(row)">打开详情</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pagination">
              <el-pagination
                v-model:current-page="query.page"
                v-model:page-size="query.size"
                :total="total"
                :page-sizes="[20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                @current-change="loadDatasets"
                @size-change="onFilterChange"
              />
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <!-- 新建来源 -->
    <el-dialog v-model="sourceDialogVisible" title="新建目录来源" width="560px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="sourceForm.name" placeholder="同一空间内唯一" />
        </el-form-item>
        <el-form-item label="来源类型" required>
          <el-select v-model="sourceForm.sourceType" style="width: 100%">
            <el-option
              v-for="item in sourceTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            >
              <span>{{ item.label }}</span>
              <span class="text-muted" style="float: right">{{ item.value }}</span>
            </el-option>
          </el-select>
          <div class="text-muted">
            {{ sourceTypes.find((t) => t.value === sourceForm.sourceType)?.hint }}
          </div>
        </el-form-item>
        <el-form-item v-if="!isExternalType" label="数据源" required>
          <el-select
            v-model="sourceForm.dataSourceId"
            filterable
            placeholder="选择已通过连通性测试的数据源"
            style="width: 100%"
          >
            <el-option
              v-for="ds in compatibleDataSources"
              :key="ds.id"
              :label="`${ds.name} (${ds.typeDisplayName}, ${ds.status})`"
              :value="ds.id"
            />
          </el-select>
          <div v-if="compatibleDataSources.length === 0" class="text-muted">
            没有匹配的数据源。JDBC 目录需要关系型/MPP 数据源；Parquet 目录需要
            LOCAL/S3/MinIO/OSS 数据源。
          </div>
        </el-form-item>
        <el-form-item
          v-if="sourceForm.sourceType === 'PARQUET_DIRECTORY'"
          label="rootPath"
        >
          <el-input
            v-model="sourceForm.rootPath"
            placeholder="如 s3://bucket/warehouse 或 /data/lakehouse"
          />
          <div class="text-muted">
            留空表示使用数据源自身的 Bucket/根目录。LOCAL 数据源的根目录就是扫描起点。
          </div>
        </el-form-item>

        <template v-if="isExternalType">
          <el-alert
            type="info"
            show-icon
            :closable="false"
            class="external-hint"
            title="External Catalog 只采集元数据，不复制数据，也不支持通过 StarRocks 直查。"
          />
          <el-form-item label="平台凭据">
            <el-select
              v-model="sourceForm.credentialId"
              clearable
              filterable
              placeholder="Glue 可留空使用 AWS provider chain"
              style="width: 100%"
            >
              <el-option
                v-for="credential in credentials"
                :key="credential.id"
                :label="`${credential.name}（${credential.authType}）`"
                :value="credential.id"
              />
            </el-select>
            <div class="text-muted">
              accessKey/secretKey 只存平台凭据，不写入 config_json。HMS 无凭据时可留空。
            </div>
          </el-form-item>
          <el-form-item
            v-if="sourceForm.sourceType === 'HIVE_METASTORE'"
            label="Metastore URIs"
            required
          >
            <el-input
              v-model="sourceForm.uris"
              type="textarea"
              :rows="2"
              placeholder="thrift://hms.internal:9083（多个用逗号/换行分隔）"
            />
          </el-form-item>
          <el-form-item
            v-if="sourceForm.sourceType === 'HIVE_METASTORE'"
            label="Warehouse"
          >
            <el-input v-model="sourceForm.warehouse" placeholder="可选，如 s3://lake/warehouse" />
          </el-form-item>
          <template v-if="sourceForm.sourceType === 'GLUE'">
            <el-form-item label="Region" required>
              <el-input v-model="sourceForm.region" placeholder="如 cn-north-1" />
            </el-form-item>
            <el-form-item label="Catalog ID">
              <el-input v-model="sourceForm.catalogId" placeholder="可选，AWS 账号 ID" />
            </el-form-item>
          </template>
          <el-form-item label="包含数据库">
            <el-input
              v-model="sourceForm.includeDatabases"
              placeholder="可选，逗号分隔；留空表示全部"
            />
          </el-form-item>
          <el-form-item label="排除数据库">
            <el-input
              v-model="sourceForm.excludeDatabases"
              placeholder="可选，逗号分隔，如 tmp"
            />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="sourceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sourceSubmitting" @click="submitSource">创建</el-button>
      </template>
    </el-dialog>

    <!-- 数据集详情 -->
    <el-drawer v-model="detailVisible" title="数据集详情" size="720px">
      <el-empty v-if="!detail && !detailLoading" description="暂无数据" />
      <div v-else v-loading="detailLoading">
        <div v-if="detail" class="drawer-actions">
          <el-button type="primary" @click="openAssetDetail(detail.dataset)">打开完整详情</el-button>
        </div>
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="名称">{{ detail.dataset.name }}</el-descriptions-item>
          <el-descriptions-item label="限定名">
            {{ detail.dataset.qualifiedName }}
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.dataset.kind }}</el-descriptions-item>
          <el-descriptions-item label="格式">{{ detail.dataset.format ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="字段数">
            {{ detail.dataset.columnCount ?? detail.columns.length }}
          </el-descriptions-item>
          <el-descriptions-item label="schema hash">
            <span class="mono">{{ detail.dataset.schemaHash ?? '-' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-tabs v-if="detail" style="margin-top: 16px">
          <el-tab-pane label="字段">
            <el-table :data="detail.columns" size="small">
              <el-table-column prop="ordinal" label="#" width="50" />
              <el-table-column prop="name" label="字段" min-width="160" />
              <el-table-column prop="rawType" label="原始类型" width="140" />
              <el-table-column prop="canonicalType" label="规范类型" width="120" />
              <el-table-column label="约束" width="130">
                <template #default="{ row }">
                  <el-tag v-if="row.primaryKey" size="small" type="warning">PK</el-tag>
                  <el-tag
                    v-if="row.partitionKey"
                    size="small"
                    type="success"
                    style="margin-left: 4px"
                  >
                    PARTITION
                  </el-tag>
                  <span v-if="!row.primaryKey && !row.partitionKey" class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column prop="comment" label="注释" min-width="160" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`分区 (${detail.partitions.length})`">
            <el-empty v-if="detail.partitions.length === 0" description="无分区(或 JDBC 表不采集分区)" />
            <el-table v-else :data="detail.partitions" size="small">
              <el-table-column label="分区值" min-width="220">
                <template #default="{ row }">{{ formatPartitionValues(row.valuesJson) }}</template>
              </el-table-column>
              <el-table-column prop="fileCount" label="文件数" width="90" align="right" />
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
              </el-table-column>
              <el-table-column prop="path" label="路径" min-width="240" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.catalog-page {
  padding: 4px 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0 0 4px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sources-card {
  min-height: 420px;
}

.source-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
}

.source-item:hover {
  background: var(--el-fill-color-light);
}

.source-item--active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.source-item__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.source-item__config {
  font-size: 11px;
  word-break: break-all;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.external-hint {
  margin-bottom: var(--dg-space-md);
}
</style>
