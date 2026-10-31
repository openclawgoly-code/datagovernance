<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { catalogAssetApi } from '@/api/catalog-assets'
import { governanceApi } from '@/api/catalog-governance'
import { glossaryApi } from '@/api/glossary'
import { useAuthStore } from '@/stores/auth'
import { usePageRefresh } from '@/composables/usePageRefresh'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type { CatalogColumn } from '@/types/catalog-assets'
import type { BusinessDomain, GovernanceMember } from '@/types/catalog-governance'
import type {
  GlossaryBinding,
  GlossaryStatus,
  GlossaryTerm,
} from '@/types/glossary'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import DatasetSearchSelect from '@/components/metadata/DatasetSearchSelect.vue'

defineOptions({ name: 'GlossaryView' })

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const terms = ref<GlossaryTerm[]>([])
const total = ref(0)
const domains = ref<BusinessDomain[]>([])
const members = ref<GovernanceMember[]>([])
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  domainId: '',
  status: '',
})

const canManage = computed(() => auth.hasPermission('metadata:glossary:manage'))

const statusMeta: Record<GlossaryStatus, {
  label: string
  type: 'success' | 'warning' | 'info'
}> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'success' },
  DEPRECATED: { label: '已废弃', type: 'warning' },
}

const drawerVisible = ref(false)
const editingId = ref('')
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  term: '',
  definition: '',
  synonyms: [] as string[],
  domainId: '',
  ownerUsername: '',
})

const bindingVisible = ref(false)
const bindingTerm = ref<GlossaryTerm | null>(null)
const bindings = ref<GlossaryBinding[]>([])
const bindingsLoading = ref(false)
const bindingForm = reactive({
  datasetId: '',
  columnId: '',
  note: '',
})
const bindingColumns = ref<CatalogColumn[]>([])
const bindingSubmitting = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await glossaryApi.listTerms({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      domainId: query.domainId || undefined,
      status: query.status || undefined,
    })
    terms.value = page.records
    total.value = page.total
  } catch {
    error.value = '术语加载失败'
    terms.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  const [domainList, memberList] = await Promise.all([
    governanceApi.listDomains().catch(() => []),
    canManage.value ? governanceApi.listMembers().catch(() => []) : Promise.resolve([]),
  ])
  domains.value = domainList
  members.value = memberList
}

function onFilterChange() {
  query.page = 1
  load()
}

function resetFilters() {
  query.keyword = ''
  query.domainId = ''
  query.status = ''
  query.page = 1
  load()
}

function openCreate() {
  editingId.value = ''
  Object.assign(form, {
    term: '',
    definition: '',
    synonyms: [],
    domainId: '',
    ownerUsername: '',
  })
  formError.value = ''
  drawerVisible.value = true
}

function openEdit(term: GlossaryTerm | Record<string, any>) {
  editingId.value = term.id
  Object.assign(form, {
    term: term.term,
    definition: term.definition,
    synonyms: [...term.synonyms],
    domainId: term.domainId || '',
    ownerUsername: term.ownerUsername || '',
  })
  formError.value = ''
  drawerVisible.value = true
}

async function submitTerm() {
  formError.value = ''
  if (!form.term.trim()) {
    formError.value = '术语名称不能为空'
    return
  }
  if (!form.definition.trim()) {
    formError.value = '术语定义不能为空'
    return
  }
  submitting.value = true
  try {
    const payload = {
      term: form.term.trim(),
      definition: form.definition.trim(),
      synonyms: form.synonyms,
      domainId: form.domainId || null,
      ownerUsername: form.ownerUsername || null,
    }
    if (editingId.value) {
      await glossaryApi.updateTerm(editingId.value, payload)
    } else {
      await glossaryApi.createTerm(payload)
    }
    drawerVisible.value = false
    ElMessage.success('术语已保存')
    await load()
  } catch (e: any) {
    formError.value = e?.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

async function publishTerm(term: GlossaryTerm | Record<string, any>) {
  await glossaryApi.publish(term.id)
  ElMessage.success(`术语「${term.term}」已发布`)
  await load()
}

async function deprecateTerm(term: GlossaryTerm | Record<string, any>) {
  const ok = await confirmDanger(
    `废弃术语「${term.term}」？资产详情将不再展示它。`,
    '废弃术语',
    '废弃',
  )
  if (!ok) {
    return
  }
  await glossaryApi.deprecate(term.id)
  ElMessage.success('术语已废弃')
  await load()
}

async function openBindings(term: GlossaryTerm | Record<string, any>) {
  bindingTerm.value = term as GlossaryTerm
  bindingForm.datasetId = ''
  bindingForm.columnId = ''
  bindingForm.note = ''
  bindingColumns.value = []
  bindingVisible.value = true
  await loadBindings()
}

async function loadBindings() {
  if (!bindingTerm.value) {
    return
  }
  bindingsLoading.value = true
  try {
    bindings.value = await glossaryApi.listBindings(bindingTerm.value.id)
  } finally {
    bindingsLoading.value = false
  }
}

async function onBindingDatasetChange(datasetId: string) {
  bindingForm.columnId = ''
  bindingColumns.value = []
  if (!datasetId) {
    return
  }
  try {
    bindingColumns.value = await catalogAssetApi.listColumns(datasetId)
  } catch {
    bindingColumns.value = []
  }
}

async function addBinding() {
  if (!bindingTerm.value || !bindingForm.datasetId) {
    ElMessage.warning('请选择数据集')
    return
  }
  bindingSubmitting.value = true
  try {
    await glossaryApi.createBinding(bindingTerm.value.id, {
      datasetId: bindingForm.datasetId,
      columnId: bindingForm.columnId || null,
      note: bindingForm.note.trim() || null,
    })
    bindingForm.columnId = ''
    bindingForm.note = ''
    ElMessage.success('绑定已添加')
    await Promise.all([loadBindings(), load()])
  } finally {
    bindingSubmitting.value = false
  }
}

async function removeBinding(binding: GlossaryBinding | Record<string, any>) {
  if (!bindingTerm.value) {
    return
  }
  await glossaryApi.deleteBinding(bindingTerm.value.id, binding.id)
  ElMessage.success('绑定已解除')
  await Promise.all([loadBindings(), load()])
}

function openAsset(datasetId: string) {
  router.push(`/metadata/assets/${datasetId}`)
}

function statusOf(term: GlossaryTerm | Record<string, any>) {
  return statusMeta[term.status as GlossaryStatus]
}

async function bootstrap() {
  await Promise.all([load(), loadOptions()])
}

usePageRefresh(bootstrap)
</script>

<template>
  <div class="page-container glossary-page">
    <PageHeader
      title="业务术语"
      description="维护 workspace 级业务词汇，并绑定到资产或字段；资产详情只展示已发布术语。"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openCreate">新建术语</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <FilterBar>
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="搜索术语 / 定义 / 同义词"
          style="width: 260px"
          @keyup.enter="onFilterChange"
          @clear="onFilterChange"
        />
        <el-select
          v-model="query.domainId"
          clearable
          placeholder="业务域"
          style="width: 160px"
          @change="onFilterChange"
        >
          <el-option
            v-for="domain in domains"
            :key="domain.id"
            :label="domain.name"
            :value="domain.id"
          />
        </el-select>
        <el-select
          v-model="query.status"
          clearable
          placeholder="状态"
          style="width: 130px"
          @change="onFilterChange"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已废弃" value="DEPRECATED" />
        </el-select>
        <el-button @click="onFilterChange">搜索</el-button>
        <template #actions>
          <el-button
            v-if="query.keyword || query.domainId || query.status"
            @click="resetFilters"
          >
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
        class="glossary-page__alert"
      >
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="terms" size="small" border>
        <el-table-column label="术语" min-width="150">
          <template #default="{ row }">
            <div class="term-cell">
              <span class="term-cell__name">{{ row.term }}</span>
              <span v-if="row.synonyms.length" class="term-cell__synonyms">
                同义词：{{ row.synonyms.join('、') }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="definition" label="定义" min-width="260" show-overflow-tooltip />
        <el-table-column label="业务域" width="120">
          <template #default="{ row }">{{ row.domainName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusOf(row).type" size="small">{{ statusOf(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="120">
          <template #default="{ row }">{{ row.ownerUsername || '-' }}</template>
        </el-table-column>
        <el-table-column prop="bindingCount" label="绑定" width="70" align="right" />
        <el-table-column label="更新于" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openBindings(row)">绑定</el-button>
            <template v-if="canManage">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status !== 'PUBLISHED'"
                link
                type="success"
                @click="publishTerm(row)"
              >
                发布
              </el-button>
              <el-button
                v-if="row.status === 'PUBLISHED'"
                link
                type="warning"
                @click="deprecateTerm(row)"
              >
                废弃
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && !error && !terms.length"
        :description="query.keyword || query.domainId || query.status
          ? '没有匹配的术语'
          : '还没有业务术语'"
      >
        <el-button v-if="canManage" type="primary" plain @click="openCreate">新建术语</el-button>
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

    <!-- 新建/编辑术语 -->
    <el-drawer
      v-model="drawerVisible"
      :title="editingId ? '编辑术语' : '新建术语'"
      size="560px"
    >
      <el-form label-width="96px">
        <el-form-item label="术语" required>
          <el-input v-model="form.term" maxlength="128" placeholder="如 GMV" />
        </el-form-item>
        <el-form-item label="定义" required>
          <el-input
            v-model="form.definition"
            type="textarea"
            :rows="4"
            maxlength="2048"
            show-word-limit
            placeholder="业务口径，而不是 SQL 表达式"
          />
        </el-form-item>
        <el-form-item label="同义词">
          <el-select
            v-model="form.synonyms"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车，可多个"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="业务域">
          <el-select v-model="form.domainId" clearable placeholder="可选" style="width: 100%">
            <el-option
              v-for="domain in domains"
              :key="domain.id"
              :label="`${domain.name}（${domain.code}）`"
              :value="domain.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select
            v-model="form.ownerUsername"
            clearable
            filterable
            placeholder="当前空间成员"
            style="width: 100%"
          >
            <el-option
              v-for="member in members"
              :key="member.id"
              :label="member.displayName
                ? `${member.displayName}（${member.username}）`
                : member.username"
              :value="member.username"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="formError"
          type="error"
          show-icon
          :closable="false"
          :title="formError"
        />
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitTerm">保存</el-button>
      </template>
    </el-drawer>

    <!-- 绑定 -->
    <el-drawer
      v-model="bindingVisible"
      :title="`绑定资产 · ${bindingTerm?.term || ''}`"
      size="680px"
    >
      <el-table v-loading="bindingsLoading" :data="bindings" size="small" border>
        <el-table-column label="资产" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAsset(row.datasetId)">
              {{ row.datasetQualifiedName || row.datasetName || row.datasetId }}
            </el-button>
            <el-tag v-if="!row.datasetName" size="small" type="danger" style="margin-left: 4px">
              资产不可读
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段" width="140">
          <template #default="{ row }">{{ row.columnName || '整个数据集' }}</template>
        </el-table-column>
        <el-table-column prop="note" label="说明" min-width="150" show-overflow-tooltip />
        <el-table-column v-if="canManage" label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeBinding(row)">解绑</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-if="!bindingsLoading && !bindings.length"
        description="还没有绑定资产"
      />

      <template v-if="canManage">
        <el-divider />
        <div class="binding-form">
          <div class="binding-form__title">新增绑定</div>
          <el-form label-width="86px">
            <el-form-item label="数据集" required>
              <DatasetSearchSelect
                v-model="bindingForm.datasetId"
                placeholder="搜索数据集"
                @change="onBindingDatasetChange"
              />
            </el-form-item>
            <el-form-item label="字段">
              <el-select
                v-model="bindingForm.columnId"
                clearable
                filterable
                placeholder="留空绑定整个数据集"
                style="width: 100%"
              >
                <el-option
                  v-for="column in bindingColumns"
                  :key="column.id"
                  :label="`${column.name}（${column.rawType || column.canonicalType || '-'}）`"
                  :value="column.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="bindingForm.note" maxlength="512" placeholder="可选" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="bindingSubmitting" @click="addBinding">
                添加绑定
              </el-button>
            </el-form-item>
          </el-form>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.glossary-page__alert {
  margin-bottom: var(--dg-space-md);
}

.term-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.term-cell__name {
  font-weight: 600;
}

.term-cell__synonyms {
  font-size: 11px;
  color: var(--dg-text-muted);
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}

.binding-form__title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: var(--dg-space-sm);
}
</style>
