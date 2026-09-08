<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { alertRuleApi, channelApi } from '@/api/governance'
import { jobApi } from '@/api/job'
import { confirmDanger } from '@/utils/confirm'
import type { AlertChannel, AlertRule, TriggerInfo } from '@/types/governance'
import type { JobDefinition } from '@/types/job'

/**
 * 告警规则(序号 25)。
 *
 * 需求给的四个维度全在这个表单里:范围、触发方式、通知渠道、告警频率。
 * 第四个在界面上叫「告警频率」,在实现上是抑制窗口 —— 提示文案要把这层
 * 关系说清楚,否则用户会以为设了 10 分钟就等于"10 分钟内不会有告警",
 * 而实际上告警仍然被记录,只是不推送。
 */

const loading = ref(false)
const rows = ref<AlertRule[]>([])
const triggers = ref<TriggerInfo[]>([])
const channels = ref<AlertChannel[]>([])
const jobs = ref<JobDefinition[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  name: '',
  description: '',
  triggerType: 'EXECUTION_FAILED',
  scope: 'ALL',
  targetJobIds: [] as string[],
  thresholdMs: 600000,
  channelIds: [] as string[],
  suppressWindowSeconds: 600,
})

/** 当前触发方式要不要填阈值 —— 读后端下发的元数据,不写 if (type === 'SLOW') */
const needsThreshold = computed(
  () => triggers.value.find((t) => t.type === form.triggerType)?.needsThreshold ?? false,
)

/** 抑制窗口的人话描述。0 是一个需要特别说明的值 */
const windowHint = computed(() => {
  const s = form.suppressWindowSeconds
  if (!s || s <= 0) {
    return '不抑制:每次触发都推送。一个每分钟失败的任务会在两小时里发出 120 条'
  }
  const minutes = Math.round(s / 60)
  return `同一个任务连续触发时,${minutes} 分钟内只推送第一条;其余仍然记录,但不打扰人`
})

async function load() {
  loading.value = true
  try {
    rows.value = await alertRuleApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  ;[triggers.value, channels.value] = await Promise.all([
    alertRuleApi.triggerTypes(),
    channelApi.list(),
  ])
  await load()
})

async function openDialog(row: AlertRule | null) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    name: row?.name ?? '',
    description: row?.description ?? '',
    triggerType: row?.triggerType ?? 'EXECUTION_FAILED',
    scope: row?.scope ?? 'ALL',
    targetJobIds: row?.targetJobIds ?? [],
    thresholdMs: row?.thresholdMs ?? 600000,
    channelIds: row?.channelIds ?? [],
    suppressWindowSeconds: row?.suppressWindowSeconds ?? 600,
  })
  dialogVisible.value = true
  if (jobs.value.length === 0) {
    // 任务列表只在真的要选"指定任务"时才需要,但用户可能进来就切过去,
    // 所以打开对话框时拉一次
    const page = await jobApi.list({ page: 1, size: 200 })
    jobs.value = page.records
  }
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  submitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description || undefined,
      triggerType: form.triggerType,
      scope: form.scope,
      targetJobIds: form.scope === 'SPECIFIC' ? form.targetJobIds : [],
      thresholdMs: needsThreshold.value ? form.thresholdMs : null,
      channelIds: form.channelIds,
      suppressWindowSeconds: form.suppressWindowSeconds,
    }
    if (editingId.value) {
      await alertRuleApi.update(editingId.value, payload)
    } else {
      await alertRuleApi.create(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onToggle(row: AlertRule) {
  await alertRuleApi.setEnabled(row.id, !row.enabled)
  ElMessage.success(row.enabled ? '已停用 —— 它不再触发新告警' : '已启用')
  await load()
}

async function onDelete(row: AlertRule) {
  if (!(await confirmDanger(`确定删除规则「${row.name}」吗?已有的告警不受影响。`, '删除规则'))) {
    return
  }
  await alertRuleApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function describeWindow(seconds: number): string {
  if (!seconds || seconds <= 0) return '不抑制'
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.round(seconds / 60)} 分钟`
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-alert type="info" :closable="false" show-icon style="margin: 0">
            <template #title>「告警频率」= 抑制窗口</template>
            窗口内的同源告警<b>仍然被记录</b>,只是不推送 ——
            所以事后能查出"那两小时里其实失败了 120 次",而当时只被打扰了一次。
          </el-alert>
        </div>
        <el-button v-permission="'governance:rule:manage'" type="primary" @click="openDialog(null)">
          新建规则
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" style="margin-top: 12px">
        <el-table-column label="规则名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ (row as AlertRule).name }}</div>
            <div v-if="(row as AlertRule).description" class="text-muted">
              {{ (row as AlertRule).description }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="触发方式" width="180">
          <template #default="{ row }">
            {{ (row as AlertRule).triggerDisplayName }}
            <div v-if="(row as AlertRule).thresholdMs" class="text-muted">
              阈值 {{ Math.round((row as AlertRule).thresholdMs! / 1000) }} 秒
            </div>
          </template>
        </el-table-column>

        <el-table-column label="范围" width="130">
          <template #default="{ row }">
            <span v-if="(row as AlertRule).scope === 'ALL'">全部任务</span>
            <span v-else>
              指定 {{ (row as AlertRule).targetJobIds.length }} 个任务
            </span>
          </template>
        </el-table-column>

        <el-table-column label="告警频率" width="120">
          <template #default="{ row }">
            <el-tooltip content="通知之后多久内不再推送同源告警;被抑制的仍然记录">
              <span>{{ describeWindow((row as AlertRule).suppressWindowSeconds) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="渠道" width="90" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="(row as AlertRule).channelIds.length === 0"
              content="没有配置渠道:告警仍会记录在「告警信息」里,但不会推送给任何人"
            >
              <el-tag type="info" size="small">仅记录</el-tag>
            </el-tooltip>
            <span v-else>{{ (row as AlertRule).channelIds.length }} 个</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="(row as AlertRule).enabled ? 'success' : 'info'" size="small">
              {{ (row as AlertRule).enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'governance:rule:manage'"
              link
              type="primary"
              @click="openDialog(row as AlertRule)"
            >
              编辑
            </el-button>
            <el-button
              v-permission="'governance:rule:manage'"
              link
              :type="(row as AlertRule).enabled ? 'warning' : 'success'"
              @click="onToggle(row as AlertRule)"
            >
              {{ (row as AlertRule).enabled ? '停用' : '启用' }}
            </el-button>
            <el-button
              v-permission="'governance:rule:manage'"
              link
              type="danger"
              @click="onDelete(row as AlertRule)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有告警规则。没有规则时任务失败不会有任何通知。
          </div>
        </template>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑告警规则' : '新建告警规则'"
      width="620px"
      destroy-on-close
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="选填" />
        </el-form-item>

        <el-divider content-position="left">触发方式</el-divider>
        <el-form-item label="什么时候告警">
          <el-select v-model="form.triggerType" style="width: 100%">
            <el-option
              v-for="t in triggers"
              :key="t.type"
              :label="t.displayName"
              :value="t.type"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needsThreshold" label="阈值(毫秒)" required>
          <el-input-number v-model="form.thresholdMs" :min="1000" :step="60000" />
          <span class="text-muted" style="margin-left: 8px">
            约 {{ Math.round(form.thresholdMs / 1000) }} 秒
          </span>
        </el-form-item>

        <el-divider content-position="left">范围</el-divider>
        <el-form-item label="盯哪些任务">
          <el-radio-group v-model="form.scope">
            <el-radio value="ALL">全部任务</el-radio>
            <el-radio value="SPECIFIC">指定任务</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'SPECIFIC'" label="选择任务" required>
          <el-select v-model="form.targetJobIds" multiple filterable style="width: 100%">
            <el-option v-for="j in jobs" :key="j.id" :label="j.name" :value="j.id" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">通知</el-divider>
        <el-form-item label="通知渠道">
          <el-select v-model="form.channelIds" multiple style="width: 100%"
                     placeholder="不选则只记录、不推送">
            <el-option
              v-for="c in channels"
              :key="c.id"
              :label="`${c.name}(${c.type})`"
              :value="c.id"
            />
          </el-select>
          <div v-if="channels.length === 0" class="text-muted">
            还没有渠道。去「基础配置 → 告警渠道」建一个,并做一次连通性测试。
          </div>
        </el-form-item>
        <el-form-item label="告警频率">
          <el-input-number v-model="form.suppressWindowSeconds" :min="0" :step="60" />
          <span class="text-muted" style="margin-left: 8px">秒</span>
          <div class="text-muted">{{ windowHint }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
