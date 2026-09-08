<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { executorApi } from '@/api/job'
import { confirmAction, confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type { Executor, ExecutorStatus, ExecutorStatusInfo } from '@/types/job'

/**
 * 执行器管理(序号 31)。
 *
 * <b>菜单在「基础配置」下,归属却是 Runtime</b>(架构风险 R2)。所以这个页面
 * 没有"编辑"按钮:执行器不是配置项,它是有状态的资源 —— 并发额度、端点这些
 * 由部署决定,平台只负责注册、观察与排空。
 */

const loading = ref(false)
const rows = ref<Executor[]>([])
const statuses = ref<ExecutorStatusInfo[]>([])
const dialogVisible = ref(false)
const submitting = ref(false)

const form = reactive({
  name: '',
  kind: 'LOCAL',
  endpoint: '',
  maxConcurrency: 4,
  shared: false,
})

const statusMeta = computed(() => new Map(statuses.value.map((s) => [s.status, s])))

function tagType(status: ExecutorStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'HEALTHY') return 'success'
  if (status === 'DRAINING') return 'warning'
  if (status === 'UNHEALTHY') return 'danger'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    rows.value = await executorApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  statuses.value = await executorApi.statuses()
  await load()
})

function onRegister() {
  Object.assign(form, {
    name: '', kind: 'LOCAL', endpoint: '', maxConcurrency: 4, shared: false,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写执行器名称')
    return
  }
  submitting.value = true
  try {
    await executorApi.register({
      name: form.name.trim(),
      kind: form.kind,
      endpoint: form.endpoint || undefined,
      maxConcurrency: form.maxConcurrency,
      shared: form.shared,
    })
    ElMessage.success('已注册。收到第一次心跳后才会变成「健康」')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDrain(row: Executor) {
  const confirmed = await confirmAction(
    `排空「${row.name}」后它不再接新任务,但手上的 ${row.runningCount ?? 0} 个会跑完。确定吗?`,
    '排空执行器',
  )
  if (!confirmed) {
    return
  }
  await executorApi.drain(row.id)
  ElMessage.success('已进入排空;等手上的任务跑完即可移除')
  await load()
}

async function onResume(row: Executor) {
  await executorApi.resume(row.id)
  ElMessage.success('已取消排空;等下一次心跳确认它还活着')
  await load()
}

async function onRemove(row: Executor) {
  const confirmed = await confirmDanger(
    `确定移除「${row.name}」吗?历史执行记录仍会保留对它的引用。`,
    '移除执行器',
  )
  if (!confirmed) {
    return
  }
  await executorApi.remove(row.id)
  ElMessage.success('已移除')
  await load()
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-alert type="info" :closable="false" show-icon style="margin: 0">
            <template #title>执行器是资源,不是配置项</template>
            下线一个执行器要先「排空」—— 直接移除会把它手上正在跑的任务连同
            执行记录一起丢掉,而那些记录是任务监控的事实来源。
          </el-alert>
        </div>
        <el-button v-permission="'runtime:executor:manage'" type="primary" @click="onRegister">
          注册执行器
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" style="margin-top: 12px">
        <el-table-column label="名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as Executor).name }}
            <el-tag v-if="(row as Executor).shared" size="small" type="info" style="margin-left: 6px">
              平台共享
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="kind" label="类型" width="100" />

        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tooltip
              :content="statusMeta.get((row as Executor).status)?.acceptsWork
                ? '可以接收新任务'
                : '不接收新任务'"
            >
              <el-tag :type="tagType((row as Executor).status)" size="small">
                {{ (row as Executor).statusDisplayName }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="并发" width="140">
          <template #default="{ row }">
            {{ (row as Executor).runningCount ?? 0 }} / {{ (row as Executor).maxConcurrency ?? 0 }}
            <span class="text-muted">(余 {{ (row as Executor).availableSlots }})</span>
          </template>
        </el-table-column>

        <el-table-column label="最后心跳" width="200">
          <template #default="{ row }">
            <span v-if="!(row as Executor).lastHeartbeatAt" class="text-muted">从未上报</span>
            <span v-else>
              {{ formatDateTime((row as Executor).lastHeartbeatAt) }}
              <!-- 心跳超时但状态还没被巡检更新时,界面要先说出来 -->
              <el-tag v-if="(row as Executor).heartbeatStale" type="danger" size="small">
                已超时
              </el-tag>
            </span>
          </template>
        </el-table-column>

        <el-table-column prop="endpoint" label="端点" min-width="180" show-overflow-tooltip />

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'runtime:executor:manage'"
              link
              type="warning"
              :disabled="(row as Executor).status === 'DRAINING'"
              @click="onDrain(row as Executor)"
            >
              排空
            </el-button>
            <el-button
              v-permission="'runtime:executor:manage'"
              link
              type="success"
              :disabled="(row as Executor).status !== 'DRAINING'"
              @click="onResume(row as Executor)"
            >
              恢复
            </el-button>
            <el-button
              v-permission="'runtime:executor:manage'"
              link
              type="danger"
              :disabled="!['DRAINING', 'UNHEALTHY'].includes((row as Executor).status)"
              @click="onRemove(row as Executor)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有注册任何执行器。平台内置的本地执行器不需要注册。
          </div>
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="注册执行器" width="520px" destroy-on-close>
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="全局唯一" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kind" style="width: 100%">
            <el-option label="本地(LOCAL)" value="LOCAL" />
            <el-option label="Flink" value="FLINK" />
            <el-option label="Kubernetes" value="K8S" />
          </el-select>
        </el-form-item>
        <el-form-item label="端点">
          <el-input v-model="form.endpoint" placeholder="如 http://flink-jm:8081" />
        </el-form-item>
        <el-form-item label="并发上限">
          <el-input-number v-model="form.maxConcurrency" :min="1" :max="512" />
          <span class="text-muted" style="margin-left: 8px">超过则新任务排队</span>
        </el-form-item>
        <el-form-item label="平台共享">
          <el-switch v-model="form.shared" />
          <span class="text-muted" style="margin-left: 8px">
            所有空间可用;只有平台管理员能注册
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>
