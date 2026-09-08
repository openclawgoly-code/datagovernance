<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { jobApi } from '@/api/job'
import { confirmAction, confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import { jobStatusMeta } from '@/utils/job-status'
import JobFormDialog from './JobFormDialog.vue'
import ScheduleDialog from './ScheduleDialog.vue'
import CompileDiagnosticsDialog from './CompileDiagnosticsDialog.vue'
import type { JobDefinition, JobDefinitionStatus, JobType, JobTypeInfo } from '@/types/job'

/**
 * 任务管理 —— 序号 9、11-14、16、18、20、22 共用这一个页面。
 *
 * 菜单上它们是七个入口,但那是投影:同一个列表加不同的 jobType 过滤。
 */

const router = useRouter()

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
      jobType: query.jobType || undefined,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  types.value = await jobApi.types()
  await load()
})

function onCreate() {
  formDialog.value?.open(null, types.value)
}

function onEdit(row: JobDefinition) {
  formDialog.value?.open(row, types.value)
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
  await load()
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
  await load()
}

function viewExecutions(row: JobDefinition) {
  router.push({ name: 'executions', query: { jobRefId: row.id } })
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
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
        <el-button v-permission="'control:job:create'" type="primary" @click="onCreate">
          新建任务
        </el-button>
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

    <JobFormDialog ref="formDialog" @saved="onSaved" />
    <ScheduleDialog ref="scheduleDialog" @saved="onSaved" />
    <CompileDiagnosticsDialog ref="diagnosticsDialog" />
  </div>
</template>
