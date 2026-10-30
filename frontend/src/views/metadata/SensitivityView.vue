<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { sensitivityApi } from '@/api/sensitivity'
import { useAuthStore } from '@/stores/auth'
import type {
  SensitivityLabel,
  SensitivityRule,
  SensitivityScanResult,
} from '@/types/sensitivity'
import { formatDateTime } from '@/utils/format'
import { formatDuration } from '@/utils/job-status'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import DetailTabs, { type DetailTab } from '@/components/insight/DetailTabs.vue'
import EntityLink from '@/components/insight/EntityLink.vue'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'
import SensitivityBadge from '@/components/insight/SensitivityBadge.vue'
import StatusTag from '@/components/insight/StatusTag.vue'
import DatasetSearchSelect from '@/components/metadata/DatasetSearchSelect.vue'

type ReviewStatus = 'CONFIRMED' | 'REJECTED'
type ScanMode = 'workspace' | 'dataset'

const route = useRoute()
const auth = useAuthStore()

const tabs: DetailTab[] = [
  { key: 'labels', label: '字段标签' },
  { key: 'rules', label: '敏感规则' },
]
const activeTab = ref<'labels' | 'rules'>('labels')

const query = reactive({
  page: 1,
  size: 20,
  level: '',
  status: '',
  datasetId: '',
})

const labels = ref<SensitivityLabel[]>([])
const labelsTotal = ref(0)
const labelsLoading = ref(false)
const labelsError = ref('')

const rules = ref<SensitivityRule[]>([])
const rulesLoading = ref(false)
const rulesError = ref('')

const scanDialogVisible = ref(false)
const scanMode = ref<ScanMode>('workspace')
const scanDatasetId = ref('')
const scanning = ref(false)
const scanError = ref('')
const scanResult = ref<SensitivityScanResult | null>(null)

const reviewDrawerVisible = ref(false)
const currentLabel = ref<SensitivityLabel | null>(null)
const reviewStatus = ref<ReviewStatus>('CONFIRMED')
const reviewLevel = ref('L2')
const reviewSubmitting = ref(false)
const reviewError = ref('')

let pendingReviewId = ''
let pendingReviewHandled = false

const canScan = computed(() => auth.hasPermission('metadata:sensitivity:scan'))
const canReview = computed(() => auth.hasPermission('metadata:sensitivity:review'))
const scanResultText = computed(() => {
  if (!scanResult.value) {
    return ''
  }
  return `扫描 ${scanResult.value.scannedColumns} 个字段，规则命中 ${scanResult.value.findings} 次，耗时 ${formatDuration(scanResult.value.durationMillis)}`
})
const evidencePretty = computed(() => {
  const raw = currentLabel.value?.evidenceJson
  if (!raw) {
    return ''
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})
const evidenceRuleName = computed(() => {
  const raw = currentLabel.value?.evidenceJson
  if (!raw) {
    return ''
  }
  try {
    const value = JSON.parse(raw) as { ruleName?: string; ruleId?: string }
    return value.ruleName || value.ruleId || ''
  } catch {
    return ''
  }
})
const reviewMetaItems = computed(() => {
  const label = currentLabel.value
  if (!label) {
    return []
  }
  return [
    { label: '数据集', value: label.datasetName || label.datasetQualifiedName || label.datasetId },
    { label: '数据集限定名', value: label.datasetQualifiedName || '-' },
    { label: '类别', value: categoryLabel(label.category) },
    { label: '当前级别', value: label.level },
    { label: '状态', value: statusLabel(label.status) },
    { label: '置信度', value: formatConfidence(label.confidence) },
    { label: '检测方式', value: detectedByLabel(label.detectedBy) },
    { label: '检测时间', value: formatDateTime(label.detectedAt) },
    { label: '复核人', value: label.reviewedBy || '-' },
    { label: '复核时间', value: formatDateTime(label.reviewedAt) },
    { label: '字段 ID', value: label.columnId },
  ]
})

function asString(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }
  return typeof value === 'string' ? value : ''
}

function statusLabel(status: string): string {
  switch (status) {
    case 'SUGGESTED':
      return '待复核'
    case 'CONFIRMED':
      return '已确认'
    case 'REJECTED':
      return '已拒绝'
    default:
      return status || '-'
  }
}

function categoryLabel(category: string): string {
  switch (category) {
    case 'PII':
      return '个人信息'
    case 'FINANCIAL':
      return '财务信息'
    case 'MEDICAL':
      return '医疗信息'
    case 'CREDENTIAL':
      return '凭证密钥'
    default:
      return category || '-'
  }
}

function detectorLabel(type: string): string {
  switch (type) {
    case 'COLUMN_NAME':
      return '字段名/注释'
    case 'REGEX':
      return '正则'
    case 'LUHN':
      return 'Luhn 校验'
    case 'DICTIONARY':
      return '字典'
    default:
      return type || '-'
  }
}

function detectedByLabel(value: string): string {
  switch (value) {
    case 'RULE':
      return '规则扫描'
    case 'MANUAL':
      return '人工标注'
    default:
      return value || '-'
  }
}

function formatConfidence(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${Math.round(value * 100)}%`
}

function levelLabel(level: string): string {
  switch (level) {
    case 'L4':
      return 'L4 · 最高'
    case 'L3':
      return 'L3 · 高'
    case 'L2':
      return 'L2 · 中'
    case 'L1':
      return 'L1 · 低'
    default:
      return level || '-'
  }
}

async function loadLabels() {
  labelsLoading.value = true
  labelsError.value = ''
  try {
    const page = await sensitivityApi.labels({
      page: query.page,
      size: query.size,
      level: query.level || undefined,
      status: query.status || undefined,
      datasetId: query.datasetId || undefined,
    })
    labels.value = page.records
    labelsTotal.value = page.total
    if (
      pendingReviewId &&
      !pendingReviewHandled &&
      page.records.some((label) => label.id === pendingReviewId)
    ) {
      const target = page.records.find((label) => label.id === pendingReviewId)
      if (target) {
        pendingReviewHandled = true
        openReview(target)
      }
    }
  } catch {
    labels.value = []
    labelsTotal.value = 0
    labelsError.value = '敏感标签加载失败，请稍后重试'
  } finally {
    labelsLoading.value = false
  }
}

async function loadRules() {
  rulesLoading.value = true
  rulesError.value = ''
  try {
    rules.value = await sensitivityApi.rules()
  } catch {
    rules.value = []
    rulesError.value = '敏感规则加载失败，请稍后重试'
  } finally {
    rulesLoading.value = false
  }
}

function onFilterChange() {
  query.page = 1
  void loadLabels()
}

function resetFilters() {
  query.level = ''
  query.status = ''
  query.datasetId = ''
  query.page = 1
  void loadLabels()
}

function showSuggested() {
  query.status = 'SUGGESTED'
  query.page = 1
  void loadLabels()
}

function refreshActive() {
  if (activeTab.value === 'labels') {
    void loadLabels()
  } else {
    void loadRules()
  }
}

function openScanDialog() {
  scanError.value = ''
  scanMode.value = query.datasetId ? 'dataset' : 'workspace'
  scanDatasetId.value = query.datasetId
  scanDialogVisible.value = true
}

async function runScan() {
  if (scanMode.value === 'dataset' && !scanDatasetId.value) {
    ElMessage.warning('请选择要扫描的数据集')
    return
  }
  scanning.value = true
  scanError.value = ''
  try {
    const result = await sensitivityApi.scan(
      scanMode.value === 'dataset' ? scanDatasetId.value : undefined,
    )
    scanResult.value = result
    scanDialogVisible.value = false
    ElMessage.success(`扫描完成，规则命中 ${result.findings} 次`)
    await loadLabels()
  } catch {
    scanError.value = '敏感扫描失败，请稍后重试'
  } finally {
    scanning.value = false
  }
}

function openReview(label: any) {
  currentLabel.value = label
  reviewStatus.value =
    label.status === 'REJECTED' ? 'REJECTED' : 'CONFIRMED'
  reviewLevel.value = label.level || 'L2'
  reviewError.value = ''
  reviewDrawerVisible.value = true
}

async function submitReview() {
  const label = currentLabel.value
  if (!label) {
    return
  }
  reviewSubmitting.value = true
  reviewError.value = ''
  try {
    const payload = {
      status: reviewStatus.value,
      ...(reviewStatus.value === 'CONFIRMED' ? { level: reviewLevel.value } : {}),
    }
    await sensitivityApi.review(label.id, payload)
    ElMessage.success('复核已提交，审计日志已记录')
    reviewDrawerVisible.value = false
    await loadLabels()
  } catch {
    reviewError.value = '复核提交失败，请稍后重试'
  } finally {
    reviewSubmitting.value = false
  }
}

watch(activeTab, (value) => {
  if (value === 'rules' && !rules.value.length && !rulesLoading.value) {
    void loadRules()
  }
})

onMounted(async () => {
  const datasetId = asString(route.query.datasetId)
  const reviewId = asString(route.query.reviewId)
  if (datasetId) {
    query.datasetId = datasetId
  }
  if (reviewId) {
    pendingReviewId = reviewId
    query.size = 50
  }
  await Promise.all([loadLabels(), loadRules()])
})
</script>

<template>
  <div class="page-container sensitivity-workbench">
    <PageHeader title="敏感数据" description="规则、字段标签、扫描与人工复核">
      <template #actions>
        <el-button @click="refreshActive">刷新</el-button>
        <el-button v-if="canScan" type="primary" @click="openScanDialog">
          发起扫描
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="scanResult"
      class="scan-result"
      type="success"
      show-icon
      @close="scanResult = null"
    >
      <template #title>
        <div class="scan-result__content">
          <span>{{ scanResultText }}</span>
          <el-button
            v-if="scanResult.findings > 0"
            link
            type="primary"
            @click="showSuggested"
          >
            查看待复核
          </el-button>
        </div>
      </template>
    </el-alert>

    <el-card shadow="never">
      <DetailTabs v-model="activeTab" :tabs="tabs">
        <template #labels>
          <FilterBar>
            <el-select
              v-model="query.level"
              placeholder="敏感级别"
              clearable
              style="width: 140px"
              @change="onFilterChange"
            >
              <el-option label="L4 · 最高" value="L4" />
              <el-option label="L3 · 高" value="L3" />
              <el-option label="L2 · 中" value="L2" />
              <el-option label="L1 · 低" value="L1" />
            </el-select>
            <el-select
              v-model="query.status"
              placeholder="复核状态"
              clearable
              style="width: 140px"
              @change="onFilterChange"
            >
              <el-option label="待复核" value="SUGGESTED" />
              <el-option label="已确认" value="CONFIRMED" />
              <el-option label="已拒绝" value="REJECTED" />
            </el-select>
            <DatasetSearchSelect
              v-model="query.datasetId"
              placeholder="筛选数据集"
              style="width: 280px"
              @change="onFilterChange"
            />
            <template #actions>
              <el-button @click="resetFilters">重置</el-button>
              <el-button :loading="labelsLoading" @click="loadLabels">查询</el-button>
            </template>
          </FilterBar>

          <el-alert
            v-if="labelsError"
            type="error"
            show-icon
            :closable="false"
            :title="labelsError"
            class="tab-alert"
          >
            <el-button link type="primary" @click="loadLabels">重试</el-button>
          </el-alert>

          <el-table v-loading="labelsLoading" :data="labels" size="small" border>
            <el-table-column label="字段" min-width="160">
              <template #default="{ row }">
                <EntityLink
                  kind="column"
                  :id="row.columnId"
                  :dataset-id="row.datasetId"
                  :label="row.columnName"
                />
              </template>
            </el-table-column>
            <el-table-column label="数据集" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <EntityLink
                  kind="dataset"
                  :id="row.datasetId"
                  :label="row.datasetName || row.datasetQualifiedName || row.datasetId"
                />
              </template>
            </el-table-column>
            <el-table-column label="级别 / 类别" width="150">
              <template #default="{ row }">
                <SensitivityBadge :level="row.level" :category="row.category" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusTag :status="row.status" :label="statusLabel(row.status)" />
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="86" align="right">
              <template #default="{ row }">{{ formatConfidence(row.confidence) }}</template>
            </el-table-column>
            <el-table-column label="检测方式" width="106">
              <template #default="{ row }">{{ detectedByLabel(row.detectedBy) }}</template>
            </el-table-column>
            <el-table-column label="检测时间" width="168">
              <template #default="{ row }">{{ formatDateTime(row.detectedAt) }}</template>
            </el-table-column>
            <el-table-column label="复核" width="190">
              <template #default="{ row }">
                <template v-if="row.reviewedAt">
                  <div>{{ row.reviewedBy || '-' }}</div>
                  <div class="text-muted">{{ formatDateTime(row.reviewedAt) }}</div>
                </template>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="86" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openReview(row)">
                  {{ canReview ? '复核' : '查看' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <EmptyState
            v-if="!labelsLoading && !labels.length"
            description="没有符合条件的敏感标签"
          >
            <el-button v-if="canScan" type="primary" plain @click="openScanDialog">
              发起扫描
            </el-button>
          </EmptyState>

          <div v-if="labelsTotal > 0" class="pagination">
            <el-pagination
              v-model:current-page="query.page"
              v-model:page-size="query.size"
              :total="labelsTotal"
              :page-sizes="[20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadLabels"
              @size-change="onFilterChange"
            />
          </div>
        </template>

        <template #rules>
          <el-alert
            type="info"
            show-icon
            :closable="false"
            class="tab-alert"
            title="规则由平台或空间管理员维护；当前只读。扫描只匹配字段名和注释，不读取数据内容。"
          />

          <el-alert
            v-if="rulesError"
            type="error"
            show-icon
            :closable="false"
            :title="rulesError"
            class="tab-alert"
          >
            <el-button link type="primary" @click="loadRules">重试</el-button>
          </el-alert>

          <el-table v-loading="rulesLoading" :data="rules" size="small" border>
            <el-table-column prop="name" label="规则" min-width="150" />
            <el-table-column label="类别" width="110">
              <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
            </el-table-column>
            <el-table-column label="级别" width="100">
              <template #default="{ row }">
                <SensitivityBadge :level="row.level" />
              </template>
            </el-table-column>
            <el-table-column label="检测方式" width="120">
              <template #default="{ row }">{{ detectorLabel(row.detectorType) }}</template>
            </el-table-column>
            <el-table-column label="匹配式" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.pattern || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="86" align="right" />
            <el-table-column label="来源" width="96">
              <template #default="{ row }">
                <el-tag size="small" :type="row.workspaceId ? 'warning' : 'info'">
                  {{ row.workspaceId ? '空间自定义' : '内置' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">
                  {{ row.enabled === false ? '停用' : '启用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <EmptyState
            v-if="!rulesLoading && !rules.length"
            description="当前空间没有可用的敏感规则"
          />
        </template>
      </DetailTabs>
    </el-card>

    <el-dialog
      v-model="scanDialogVisible"
      title="发起敏感扫描"
      width="560px"
      :close-on-click-modal="!scanning"
      :close-on-press-escape="!scanning"
    >
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="dialog-alert"
        title="扫描按字段名/注释匹配规则，不会读取数据内容。"
      />
      <el-form label-position="top">
        <el-form-item label="扫描范围">
          <el-radio-group v-model="scanMode">
            <el-radio value="workspace">全工作区</el-radio>
            <el-radio value="dataset">指定数据集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="scanMode === 'dataset'" label="数据集">
          <DatasetSearchSelect v-model="scanDatasetId" placeholder="选择要扫描的数据集" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="scanError"
        type="error"
        show-icon
        :closable="false"
        :title="scanError"
      />
      <template #footer>
        <el-button :disabled="scanning" @click="scanDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="scanning"
          :disabled="scanMode === 'dataset' && !scanDatasetId"
          @click="runScan"
        >
          开始扫描
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="reviewDrawerVisible"
      :title="canReview ? '复核敏感标签' : '敏感标签详情'"
      size="540px"
    >
      <template v-if="currentLabel">
        <div class="review-head">
          <div class="review-head__name">{{ currentLabel.columnName }}</div>
          <div class="review-head__tags">
            <SensitivityBadge :level="currentLabel.level" :category="currentLabel.category" />
            <StatusTag :status="currentLabel.status" :label="statusLabel(currentLabel.status)" />
          </div>
        </div>

        <MetadataPanel :items="reviewMetaItems" />

        <section class="evidence">
          <div class="evidence__head">
            <h4>命中证据</h4>
            <el-tag v-if="evidenceRuleName" size="small" type="info">
              {{ evidenceRuleName }}
            </el-tag>
          </div>
          <pre v-if="evidencePretty">{{ evidencePretty }}</pre>
          <div v-else class="text-muted">暂无证据 JSON</div>
        </section>

        <el-form v-if="canReview" label-position="top" class="review-form">
          <el-form-item label="复核结论">
            <el-radio-group v-model="reviewStatus">
              <el-radio-button value="CONFIRMED">确认为敏感</el-radio-button>
              <el-radio-button value="REJECTED">拒绝标签</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="敏感级别">
            <el-select
              v-model="reviewLevel"
              :disabled="reviewStatus === 'REJECTED'"
              style="width: 180px"
            >
              <el-option v-for="level in ['L4', 'L3', 'L2', 'L1']" :key="level" :value="level" :label="levelLabel(level)" />
            </el-select>
            <div class="form-hint">
              确认后可调整级别；拒绝后该标签不再参与问数结果的脱敏兜底。
            </div>
          </el-form-item>
          <el-alert
            v-if="reviewError"
            type="error"
            show-icon
            :closable="false"
            :title="reviewError"
          />
        </el-form>
        <el-alert
          v-else
          type="info"
          show-icon
          :closable="false"
          title="当前账号只有查看权限，不能提交复核。"
        />
      </template>

      <template #footer>
        <el-button @click="reviewDrawerVisible = false">关闭</el-button>
        <el-button
          v-if="canReview"
          type="primary"
          :loading="reviewSubmitting"
          @click="submitReview"
        >
          提交复核
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.scan-result {
  margin-bottom: var(--dg-space-md);
}

.scan-result__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}

.tab-alert {
  margin-bottom: var(--dg-space-md);
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}

.dialog-alert {
  margin-bottom: var(--dg-space-md);
}

.mono {
  font-family: var(--dg-font-mono);
  font-size: 12px;
}

.review-head {
  padding-bottom: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.review-head__name {
  font-size: 16px;
  font-weight: 600;
  color: var(--dg-text);
  word-break: break-all;
}

.review-head__tags {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-sm);
  flex-wrap: wrap;
}

.evidence {
  margin-top: var(--dg-space-lg);
}

.evidence__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}

.evidence__head h4 {
  margin: 0;
  font-size: 14px;
  color: var(--dg-text);
}

.evidence pre {
  margin: 0;
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.review-form {
  margin-top: var(--dg-space-lg);
  padding-top: var(--dg-space-md);
  border-top: 1px solid var(--dg-border);
}

.form-hint {
  margin-top: var(--dg-space-xs);
  font-size: 12px;
  color: var(--dg-text-muted);
  line-height: 1.6;
}
</style>
