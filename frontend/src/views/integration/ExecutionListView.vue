<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { executionApi } from '@/api/job'
import { confirmAction } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import {
  executionStatusMeta,
  formatDuration,
  formatRows,
  isTerminal,
} from '@/utils/job-status'
import type {
  Execution,
  ExecutionDetail,
  ExecutionStatus,
  JobRefType,
  JobRefTypeInfo,
} from '@/types/job'

/**
 * 执行记录 —— 序号 10/15/19/21/23 五个页面就是这一个。
 *
 * 需求清单里它们是五个菜单项,但查的是同一张 rt_execution(架构约束 R4)。
 * 这里用一个 jobRefType 过滤器承载全部五个,而不是复制五份几乎相同的页面 ——
 * 复制的那五份迟早会在其中一份上加了列而忘了另外四份。
 */

const loading = ref(false)
const rows = ref<Execution[]>([])
const total = ref(0)
const jobTypes = ref<JobRefTypeInfo[]>([])

const query = reactive({
  page: 1,
  size: 20,
  jobRefType: '' as '' | JobRefType,
  status: '' as '' | ExecutionStatus,
})

const detailVisible = ref(false)
const detail = ref<ExecutionDetail | null>(null)

/** 有未结束的执行时才轮询。全都跑完了还在轮询是纯粹的浪费。 */
const pollTimer = ref<number | null>(null)
const hasRunning = computed(() => rows.value.some((r) => !isTerminal(r.status)))

const STATUS_OPTIONS: ExecutionStatus[] = [
  'PENDING', 'DISPATCHED', 'RUNNING', 'CANCELING',
  'SUCCEEDED', 'FAILED', 'CANCELED', 'TIMEOUT',
]

async function load() {
  loading.value = true
  try {
    const page = await executionApi.list({
      page: query.page,
      size: query.size,
      jobRefType: query.jobRefType || undefined,
      status: query.status || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
  schedulePoll()
}

/**
 * 有执行在跑时每 3 秒静默刷新一次。
 *
 * 不用 setInterval:上一次请求还没回来就发下一次,慢网络下会堆积。
 * 每次刷新完再排下一次,自然形成串行。
 */
function schedulePoll() {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
    pollTimer.value = null
  }
  if (!hasRunning.value || detailVisible.value) {
    return
  }
  pollTimer.value = window.setTimeout(async () => {
    try {
      const page = await executionApi.list({
        page: query.page,
        size: query.size,
        jobRefType: query.jobRefType || undefined,
        status: query.status || undefined,
      })
      rows.value = page.records
      total.value = page.total
    } catch {
      // 轮询失败不打扰用户 —— 下一轮会再试
    }
    schedulePoll()
  }, 3000)
}

onMounted(async () => {
  jobTypes.value = await executionApi.jobTypes()
  await load()
})

onUnmounted(() => {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
  }
})

async function onDetail(row: Execution) {
  detail.value = await executionApi.get(row.id)
  detailVisible.value = true
}

async function onCancel(row: Execution) {
  const confirmed = await confirmAction(
    `确定取消「${row.jobName ?? row.id}」的这次执行吗?已经写入目标端的数据不会回滚。`,
    '取消执行',
  )
  if (!confirmed) {
    return
  }
  const result = await executionApi.cancel(row.id)
  // 取消是两段式的:这里只是受理,状态会先变成「取消中」。
  // 工作流会级联取消子节点(序号 23)—— 那个数字要说出来,否则用户
  // 不会知道自己刚刚还停掉了底下三个正在跑的任务
  ElMessage.success(
    result.canceledChildren > 0
      ? `取消已受理,同时停止了 ${result.canceledChildren} 个正在运行的子节点`
      : '取消已受理,等待执行器停止',
  )
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

/** 单条尝试的展开:只有重试过的执行才值得展开看 */
function attemptSummary(d: ExecutionDetail): string {
  return d.attempts.length <= 1
    ? '一次尝试'
    : `${d.attempts.length} 次尝试(重试不新建执行记录)`
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="所有任务的执行记录都在这里"
          description="整库迁移、离线同步、实时任务、批处理、工作流查的是同一张执行事实表,用「作业种类」筛选即可。任务监控的统计口径也来自它。"
          style="flex: 1"
        />
      </div>

      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-model="query.jobRefType"
            placeholder="全部作业种类"
            clearable
            style="width: 200px"
            @change="onFilterChange"
          >
            <el-option
              v-for="t in jobTypes"
              :key="t.type"
              :label="t.displayName"
              :value="t.type"
            />
          </el-select>
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 160px"
            @change="onFilterChange"
          >
            <el-option
              v-for="s in STATUS_OPTIONS"
              :key="s"
              :label="executionStatusMeta(s).label"
              :value="s"
            />
          </el-select>
          <el-button @click="load">刷新</el-button>
          <span v-if="hasRunning" class="text-muted">有执行进行中,每 3 秒自动刷新</span>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="任务" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ (row as Execution).jobName ?? '-' }}</div>
            <div class="text-muted text-mono">{{ (row as Execution).id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="作业种类" width="120">
          <template #default="{ row }">
            {{ (row as Execution).jobRefTypeDisplayName }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!executionStatusMeta((row as Execution).status).action"
              :content="executionStatusMeta((row as Execution).status).action"
            >
              <el-tag :type="executionStatusMeta((row as Execution).status).tagType" size="small">
                {{ (row as Execution).statusDisplayName }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="触发" width="100">
          <template #default="{ row }">
            <span class="text-muted">{{ (row as Execution).triggerType }}</span>
          </template>
        </el-table-column>
        <el-table-column label="读 / 写" width="140">
          <template #default="{ row }">
            {{ formatRows((row as Execution).rowsRead) }} /
            {{ formatRows((row as Execution).rowsWritten) }}
          </template>
        </el-table-column>
        <el-table-column label="尝试" width="70">
          <template #default="{ row }">{{ (row as Execution).attemptCount }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">
            {{ formatDuration((row as Execution).durationMs) }}
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">
            {{ formatDateTime((row as Execution).submittedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onDetail(row as Execution)">详情</el-button>
            <el-button
              v-if="!isTerminal((row as Execution).status)"
              v-permission="'runtime:execution:cancel'"
              link
              type="danger"
              @click="onCancel(row as Execution)"
            >
              取消
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

    <el-drawer v-model="detailVisible" title="执行详情" size="60%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="执行 ID">
            <span class="text-mono">{{ detail.execution.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="executionStatusMeta(detail.execution.status).tagType" size="small">
              {{ detail.execution.statusDisplayName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="任务">{{ detail.execution.jobName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="定义版本">
            v{{ detail.execution.defVersion ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="触发方式">
            {{ detail.execution.triggerType }} · {{ detail.execution.triggeredBy ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            {{ formatDuration(detail.execution.durationMs) }}
          </el-descriptions-item>
          <el-descriptions-item label="读取行数">
            {{ formatRows(detail.execution.rowsRead) }}
          </el-descriptions-item>
          <el-descriptions-item label="写入行数">
            {{ formatRows(detail.execution.rowsWritten) }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">
            {{ formatDateTime(detail.execution.submittedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ formatDateTime(detail.execution.finishedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="detail.execution.message"
          :type="detail.execution.status === 'SUCCEEDED' ? 'success' : 'error'"
          :closable="false"
          show-icon
          style="margin-top: 12px"
        >
          <div>{{ detail.execution.message }}</div>
          <div v-if="detail.execution.errorCode" class="text-muted">
            错误分类:{{ detail.execution.errorCode }}
          </div>
          <div v-if="executionStatusMeta(detail.execution.status).action" class="text-muted">
            {{ executionStatusMeta(detail.execution.status).action }}
          </div>
        </el-alert>

        <!--
          尝试列表默认折叠:绝大多数执行只有一次尝试,展开会让界面被无意义的
          层级淹没。而重试过的那些,恰恰是用户最想逐次看清楚的。
        -->
        <h4 style="margin-top: 20px">{{ attemptSummary(detail) }}</h4>
        <el-table :data="detail.attempts" size="small" border>
          <el-table-column prop="attemptNo" label="#" width="50" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="executionStatusMeta(row.status).tagType" size="small">
                {{ row.statusDisplayName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="executorId" label="执行器" width="90" />
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="读 / 写" width="130">
            <template #default="{ row }">
              {{ formatRows(row.rowsRead) }} / {{ formatRows(row.rowsWritten) }}
            </template>
          </el-table-column>
          <el-table-column prop="message" label="结果" min-width="200" show-overflow-tooltip />
        </el-table>

        <template v-for="attempt in detail.attempts" :key="attempt.id">
          <div v-if="attempt.errorDetail" style="margin-top: 12px">
            <div class="text-muted">第 {{ attempt.attemptNo }} 次尝试的技术详情:</div>
            <pre class="error-detail">{{ attempt.errorDetail }}</pre>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.error-detail {
  max-height: 240px;
  overflow: auto;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
