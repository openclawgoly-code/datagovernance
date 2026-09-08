<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { jobApi, streamingApi } from '@/api/job'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import JobFormDialog from '@/views/integration/JobFormDialog.vue'
import type { JobTypeInfo, StreamingMeta, StreamingState, StreamingStatus } from '@/types/job'

/**
 * 实时开发(序号 18)。
 *
 * 这个页面<b>不是</b>任务管理页加一个 jobType 过滤 —— 那些页面展示的是"定义",
 * 这里展示的是「运行态」。同一个实时任务的定义可以是"已发布"而运行态是
 * "保活失败":两者是两台状态机,混在一列里会让人以为它们是同一件事。
 */

const router = useRouter()

const loading = ref(false)
const states = ref<StreamingState[]>([])
const meta = ref<StreamingMeta>({ statuses: [], maxRestartAttempts: 0 })
const types = ref<JobTypeInfo[]>([])
const formDialog = ref<InstanceType<typeof JobFormDialog>>()

let timer: ReturnType<typeof setInterval> | undefined

/** 状态 → 元数据。按钮可用性读它,不写 if (status === 'RUNNING') */
const statusMeta = computed(
  () => new Map(meta.value.statuses.map((s) => [s.status, s])),
)

function tagType(status: StreamingStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'RUNNING') return 'success'
  if (status === 'FAILED') return 'danger'
  // 启动中/重启中/停止中都是"正在发生什么"——用同一个颜色,让静止态更醒目
  if (status === 'STARTING' || status === 'RESTARTING' || status === 'STOPPING') return 'warning'
  return 'info'
}

function canStart(row: StreamingState): boolean {
  return statusMeta.value.get(row.status)?.canStart ?? false
}

function canStop(row: StreamingState): boolean {
  return statusMeta.value.get(row.status)?.canStop ?? false
}

/** 重启次数逼近上限时提醒:再失败几次就会彻底放弃 */
function restartWarning(row: StreamingState): boolean {
  return row.restartCount > 0 && row.restartCount >= row.maxRestartAttempts - 1
}

async function load() {
  loading.value = true
  try {
    states.value = await streamingApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  ;[meta.value, types.value] = await Promise.all([streamingApi.meta(), jobApi.types()])
  await load()
  // 常驻任务的状态自己会变(重启、保活失败),不刷新的话用户看到的是
  // 一个永远停在"启动中"的界面。5 秒与后端的对账周期同量级
  timer = setInterval(load, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function onStart(row: StreamingState) {
  await streamingApi.start(row.jobDefinitionId)
  ElMessage.success('已提交启动;状态会先变成「启动中」')
  await load()
}

async function onStop(row: StreamingState) {
  const confirmed = await confirmDanger(
    `确定停止「${row.jobName}」吗?平台会先触发 savepoint,停止过程可能需要几十秒。`,
    '停止实时任务',
  )
  if (!confirmed) {
    return
  }
  await streamingApi.stop(row.jobDefinitionId)
  ElMessage.success('已提交停止;等待作业收尾')
  await load()
}

function onCreate() {
  formDialog.value?.open(null, types.value)
}

function viewExecutions(row: StreamingState) {
  router.push({ name: 'executions', query: { jobRefId: row.jobDefinitionId } })
}

async function onSaved() {
  await load()
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-alert type="info" :closable="false" show-icon style="margin: 0">
            <template #title>实时任务是常驻的,它的状态机围绕「保活」</template>
            连续重启超过 {{ meta.maxRestartAttempts }} 次仍起不来才判为保活失败。
            自动重启是常态而非故障 —— 所以它不计入执行失败数。
          </el-alert>
        </div>
        <el-button v-permission="'control:job:create'" type="primary" @click="onCreate">
          新建实时任务
        </el-button>
      </div>

      <el-table :data="states" v-loading="loading" style="margin-top: 12px">
        <el-table-column label="任务名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ (row as StreamingState).jobName }}</template>
        </el-table-column>

        <el-table-column label="运行态" width="120">
          <template #default="{ row }">
            <el-tag :type="tagType((row as StreamingState).status)" size="small">
              {{ (row as StreamingState).statusDisplayName }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="保活" width="150">
          <template #default="{ row }">
            <span v-if="(row as StreamingState).restartCount === 0" class="text-muted">—</span>
            <el-tooltip
              v-else
              content="连续重启次数。用光预算后平台会放弃自动恢复并告警"
            >
              <el-tag
                :type="restartWarning(row as StreamingState) ? 'danger' : 'warning'"
                size="small"
              >
                已重启 {{ (row as StreamingState).restartCount }} /
                {{ (row as StreamingState).maxRestartAttempts }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="启动时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as StreamingState).startedAt) }}
          </template>
        </el-table-column>

        <el-table-column label="最近消息" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="(row as StreamingState).message">
              {{ (row as StreamingState).message }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'control:job:stream'"
              link
              type="success"
              :disabled="!canStart(row as StreamingState)"
              @click="onStart(row as StreamingState)"
            >
              启动
            </el-button>
            <el-button
              v-permission="'control:job:stream'"
              link
              type="warning"
              :disabled="!canStop(row as StreamingState)"
              @click="onStop(row as StreamingState)"
            >
              停止
            </el-button>
            <el-button link type="primary" @click="viewExecutions(row as StreamingState)">
              记录
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有实时任务。新建后需要编译并发布,才能启动。
          </div>
        </template>
      </el-table>
    </el-card>

    <JobFormDialog ref="formDialog" @saved="onSaved" />
  </div>
</template>
