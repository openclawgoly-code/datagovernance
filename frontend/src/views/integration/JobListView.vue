<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { jobApi, taskCatalogApi } from '@/api/job'
import { confirmAction, confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import { jobStatusMeta } from '@/utils/job-status'
import JobFormDialog from './JobFormDialog.vue'
import ScheduleDialog from './ScheduleDialog.vue'
import CompileDiagnosticsDialog from './CompileDiagnosticsDialog.vue'
import BatchCreateDialog from './BatchCreateDialog.vue'
import type {
  CatalogTree,
  JobDefinition,
  JobDefinitionStatus,
  JobType,
  JobTypeInfo,
  TaskCatalogNode,
} from '@/types/job'

/**
 * 任务管理 —— 序号 9、11-14、16、18、20、22 共用这一个页面。
 *
 * 菜单上它们是七个入口,但那是投影:同一个列表加不同的 jobType 过滤。
 */

const router = useRouter()
const route = useRoute()

/**
 * 路由钉死的任务类型。
 *
 * 「离线开发」「工作流编排」与「任务管理」是<b>同一个列表的三个投影</b>
 * (SPACE-MODEL.md I.2「菜单与模块是多对多」)。钉死类型时隐藏类型下拉 ——
 * 在一个叫「工作流编排」的页面上还能切成"离线同步",只会让人困惑。
 */
const pinnedType = computed(() => (route.meta.jobType as JobType | undefined) ?? null)

const loading = ref(false)
const rows = ref<JobDefinition[]>([])
const total = ref(0)
const types = ref<JobTypeInfo[]>([])

const query = reactive({
  page: 1,
  size: 20,
  jobType: '' as '' | JobType,
  status: '' as '' | JobDefinitionStatus,
  keyword: '',
})

const formDialog = ref<InstanceType<typeof JobFormDialog>>()
const scheduleDialog = ref<InstanceType<typeof ScheduleDialog>>()
const diagnosticsDialog = ref<InstanceType<typeof CompileDiagnosticsDialog>>()
const batchDialog = ref<InstanceType<typeof BatchCreateDialog>>()

// ── 任务目录(功能 16)──────────────────────────────────────────────

const catalogTree = ref<CatalogTree>({ nodes: [], uncategorizedCount: 0 })
/** 左树选中的目录;'' 表示全部,'__none__' 表示未分类 */
const selectedCatalog = ref('')

/**
 * 「全部」与「未分类」是两个假节点。
 *
 * 它们不能改名、不能移动、不能删除 —— 所以 id 用 '' 与 '__none__' 这种
 * 明显不是 ULID 的值,让"这是不是真目录"一眼可判,而不是靠额外的标记字段。
 */
const catalogTreeData = computed(() => [
  { id: '', name: `全部(${total.value})`, children: [] as TaskCatalogNode[] },
  {
    id: '__none__',
    name: `未分类(${catalogTree.value.uncategorizedCount})`,
    children: [] as TaskCatalogNode[],
  },
  ...catalogTree.value.nodes,
])

async function loadCatalogs() {
  catalogTree.value = await taskCatalogApi.tree()
}

function onCatalogClick(node: { id: string }) {
  selectedCatalog.value = node.id
  query.page = 1
  load()
}

async function onCreateCatalog() {
  try {
    const { value } = await ElMessageBox.prompt('请输入目录名称', '新建任务目录', {
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空',
    })
    // 选中的是真目录时在它下面建,否则建在根上
    const parentId =
      selectedCatalog.value && !selectedCatalog.value.startsWith('__') ? selectedCatalog.value : null
    await taskCatalogApi.create({ parentId, name: value })
    ElMessage.success('目录已创建')
    await loadCatalogs()
  } catch {
    /* 取消 */
  }
}

async function onDeleteCatalog() {
  const id = selectedCatalog.value
  if (!id || id.startsWith('__')) {
    ElMessage.warning('请先选中一个目录')
    return
  }
  if (!(await confirmAction('确定删除该目录吗?含任务或子目录的目录无法删除。', '删除目录'))) {
    return
  }
  await taskCatalogApi.remove(id)
  ElMessage.success('目录已删除')
  selectedCatalog.value = ''
  await Promise.all([loadCatalogs(), load()])
}

function onBatchCreate() {
  batchDialog.value?.open(catalogTree.value.nodes)
}

const STATUS_OPTIONS: JobDefinitionStatus[] = [
  'DRAFT', 'VALIDATED', 'PUBLISHED', 'SCHEDULING', 'PAUSED', 'OFFLINE', 'ARCHIVED',
]

/** 类型 → 能否调度。读后端下发的元数据,不写 if (jobType === 'DB_MIGRATION') */
const schedulableTypes = computed(
  () => new Set(types.value.filter((t) => t.schedulable).map((t) => t.type)),
)

function canSchedule(row: JobDefinition): boolean {
  return schedulableTypes.value.has(row.jobType)
    && ['PUBLISHED', 'SCHEDULING', 'PAUSED'].includes(row.status)
}

async function load() {
  loading.value = true
  try {
    const page = await jobApi.list({
      page: query.page,
      size: query.size,
      jobType: pinnedType.value ?? (query.jobType || undefined),
      status: query.status || undefined,
      keyword: query.keyword || undefined,
      // 目录过滤走后端:任务数量会持续增长,拉全量再前端过滤迟早撑不住
      catalogId: selectedCatalog.value || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  types.value = await jobApi.types()
  await Promise.all([load(), loadCatalogs()])
})

// 「离线开发」与「工作流编排」是同一个组件的两条路由,切换时组件不会重建,
// 必须显式重查 —— 否则从一个页面点到另一个,列表纹丝不动
watch(pinnedType, () => {
  query.page = 1
  load()
})

function onCreate() {
  formDialog.value?.open(null, types.value, catalogTree.value.nodes, pinnedType.value)
}

function onEdit(row: JobDefinition) {
  formDialog.value?.open(row, types.value, catalogTree.value.nodes)
}

/**
 * 编译。
 *
 * 后端在编译失败时也返回 200,所以不能靠 catch 判断成败 —— 必须看 succeeded。
 * 失败时直接打开诊断对话框:让用户少点一次。
 */
async function onCompile(row: JobDefinition) {
  const result = await jobApi.compile(row.id)
  if (result.succeeded) {
    const warnings = result.diagnostics.filter((d) => d.severity === 'WARNING')
    if (warnings.length > 0) {
      // 有 WARNING 也要让用户看见:有损的类型映射能跑,但半年后会变成
      // 「报表对不上账」,那时已无从追溯
      diagnosticsDialog.value?.open(row.name, result)
    } else {
      ElMessage.success(result.summary)
    }
  } else {
    diagnosticsDialog.value?.open(row.name, result)
  }
  await load()
}

async function onPublish(row: JobDefinition) {
  await jobApi.publish(row.id)
  ElMessage.success('已发布。可手工执行,也可绑定周期调度')
  await load()
}

async function onRun(row: JobDefinition) {
  if (!row.planUpToDate) {
    ElMessage.warning('定义已修改但尚未重新编译发布,不能执行 —— 否则跑的会是一份过期的计划')
    return
  }
  const execution = await jobApi.run(row.id)
  ElMessageBox.confirm(
    `执行已提交,记录 ID:${execution.id}`,
    '已提交',
    { confirmButtonText: '去看执行记录', cancelButtonText: '留在本页', type: 'success' },
  )
    .then(() => router.push({ name: 'executions' }))
    .catch(() => {})
  await load()
}

function onSchedule(row: JobDefinition) {
  scheduleDialog.value?.open(row)
}

async function onPauseResume(row: JobDefinition) {
  if (row.status === 'SCHEDULING') {
    await jobApi.pauseSchedule(row.id)
    ElMessage.success('已暂停自动触发;手工执行仍可用')
  } else {
    await jobApi.resumeSchedule(row.id)
    ElMessage.success('已恢复调度。不补跑暂停期间错过的触发')
  }
  await load()
}

async function onOffline(row: JobDefinition) {
  const confirmed = await confirmAction(
    `下线「${row.name}」后不再触发,要重新编译并发布才能再跑。确定吗?`,
    '下线任务',
  )
  if (!confirmed) {
    return
  }
  await jobApi.offline(row.id)
  ElMessage.success('已下线')
  await load()
}

async function onDelete(row: JobDefinition) {
  if (!(await confirmDanger(`确定删除任务「${row.name}」吗?已有的执行记录会保留。`, '删除任务'))) {
    return
  }
  await jobApi.remove(row.id)
  ElMessage.success('已删除')
  // 删掉任务会改变目录上的计数,树也要跟着刷
  await onSaved()
}

function onPageChange(page: number) {
  query.page = page
  load()
}

function onPageSizeChange(size: number) {
  query.size = size
  query.page = 1
  load()
}

function onFilterChange() {
  query.page = 1
  load()
}

async function onSaved() {
  // 目录上的任务计数会随之变化,两个都要刷
  await Promise.all([load(), loadCatalogs()])
}

function viewExecutions(row: JobDefinition) {
  router.push({ name: 'executions', query: { jobRefId: row.id } })
}
</script>

<template>
  <div class="page-container">
    <el-row :gutter="12">
      <!-- 左:任务目录(功能 16)。与数据源目录同构 -->
      <el-col :span="5">
        <el-card shadow="never" class="catalog-panel">
          <template #header>
            <div class="catalog-panel__header">
              <span>任务目录</span>
              <span>
                <el-button
                  v-permission="'control:catalog:manage'"
                  link
                  type="primary"
                  @click="onCreateCatalog"
                >
                  新建
                </el-button>
                <el-button
                  v-permission="'control:catalog:manage'"
                  link
                  type="danger"
                  @click="onDeleteCatalog"
                >
                  删除
                </el-button>
              </span>
            </div>
          </template>

          <el-tree
            :data="catalogTreeData"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            :current-node-key="selectedCatalog"
            highlight-current
            default-expand-all
            @node-click="onCatalogClick"
          >
            <template #default="{ data }">
              <span>
                {{ data.name }}
                <span v-if="data.taskCount != null" class="text-muted">({{ data.taskCount }})</span>
              </span>
            </template>
          </el-tree>
        </el-card>
      </el-col>

      <!-- 右:任务列表 -->
      <el-col :span="19">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-if="!pinnedType"
            v-model="query.jobType"
            placeholder="全部类型"
            clearable
            style="width: 170px"
            @change="onFilterChange"
          >
            <el-option v-for="t in types" :key="t.type" :label="t.displayName" :value="t.type" />
          </el-select>
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="onFilterChange"
          >
            <el-option
              v-for="s in STATUS_OPTIONS"
              :key="s"
              :label="jobStatusMeta(s).label"
              :value="s"
            />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="搜索任务名称"
            clearable
            style="width: 200px"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <el-button @click="onFilterChange">查询</el-button>
        </div>
        <span>
          <el-button v-permission="'control:job:create'" @click="onBatchCreate">
            批量新增
          </el-button>
          <el-button v-permission="'control:job:create'" type="primary" @click="onCreate">
            新建任务
          </el-button>
        </span>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="任务名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ (row as JobDefinition).name }}</div>
            <div v-if="(row as JobDefinition).description" class="text-muted">
              {{ (row as JobDefinition).description }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ (row as JobDefinition).jobTypeDisplayName }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip :content="jobStatusMeta((row as JobDefinition).status).action">
              <el-tag :type="jobStatusMeta((row as JobDefinition).status).tagType" size="small">
                {{ (row as JobDefinition).statusDisplayName }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="编译" width="120">
          <template #default="{ row }">
            <!--
              「计划过期」比「编译失败」更隐蔽:定义改过但没重新编译,任务看着
              好好的,一执行就被拒。所以它要单独标出来。
            -->
            <el-tag
              v-if="!(row as JobDefinition).planUpToDate"
              type="warning"
              size="small"
              effect="plain"
            >
              待重新编译
            </el-tag>
            <el-tag
              v-else-if="(row as JobDefinition).lastCompileSucceeded"
              type="success"
              size="small"
              effect="plain"
            >
              已通过
            </el-tag>
            <span v-else class="text-muted">未编译</span>
          </template>
        </el-table-column>
        <el-table-column label="调度" min-width="170">
          <template #default="{ row }">
            <template v-if="(row as JobDefinition).cronExpression">
              <div class="text-mono">{{ (row as JobDefinition).cronExpression }}</div>
              <div class="text-muted">
                下次 {{ formatDateTime((row as JobDefinition).nextFireAt) }}
              </div>
            </template>
            <span v-else class="text-muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            {{ formatDateTime((row as JobDefinition).updatedAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="400" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'control:job:compile'" link type="primary" @click="onCompile(row as JobDefinition)">
              编译
            </el-button>
            <el-button
              v-permission="'control:job:publish'"
              link
              type="primary"
              :disabled="!['VALIDATED', 'DRAFT'].includes((row as JobDefinition).status)"
              @click="onPublish(row as JobDefinition)"
            >
              发布
            </el-button>
            <el-button
              v-permission="'control:job:trigger'"
              link
              type="success"
              :disabled="!['PUBLISHED', 'SCHEDULING', 'PAUSED'].includes((row as JobDefinition).status)"
              @click="onRun(row as JobDefinition)"
            >
              执行
            </el-button>
            <el-button
              v-permission="'control:job:schedule'"
              link
              type="primary"
              :disabled="!canSchedule(row as JobDefinition)"
              @click="onSchedule(row as JobDefinition)"
            >
              调度
            </el-button>
            <el-button
              v-if="['SCHEDULING', 'PAUSED'].includes((row as JobDefinition).status)"
              v-permission="'control:job:schedule'"
              link
              :type="(row as JobDefinition).status === 'SCHEDULING' ? 'warning' : 'success'"
              @click="onPauseResume(row as JobDefinition)"
            >
              {{ (row as JobDefinition).status === 'SCHEDULING' ? '暂停' : '恢复' }}
            </el-button>
            <el-button link type="primary" @click="viewExecutions(row as JobDefinition)">
              记录
            </el-button>
            <el-button v-permission="'control:job:update'" link type="primary" @click="onEdit(row as JobDefinition)">
              编辑
            </el-button>
            <el-button
              v-permission="'control:job:publish'"
              link
              type="warning"
              :disabled="!['PUBLISHED', 'SCHEDULING', 'PAUSED'].includes((row as JobDefinition).status)"
              @click="onOffline(row as JobDefinition)"
            >
              下线
            </el-button>
            <el-button v-permission="'control:job:delete'" link type="danger" @click="onDelete(row as JobDefinition)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        :current-page="query.page"
        :page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="onPageChange"
        @size-change="onPageSizeChange"
      />
    </el-card>
      </el-col>
    </el-row>

    <JobFormDialog ref="formDialog" @saved="onSaved" />
    <ScheduleDialog ref="scheduleDialog" @saved="onSaved" />
    <CompileDiagnosticsDialog ref="diagnosticsDialog" />
    <BatchCreateDialog ref="batchDialog" @saved="onSaved" />
  </div>
</template>

<style scoped>
.catalog-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
