<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { channelApi } from '@/api/governance'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type { AlertChannel } from '@/types/governance'

/**
 * 告警渠道(序号 33)。
 *
 * <b>菜单在「基础配置」下,归属却是 Governance</b>(架构风险 R2)——
 * 与执行器、制品同一个道理:渠道有连通性状态。一个发不出去的渠道会让挂在
 * 它上面的所有告警静默失效:界面上告警都好好地记着,而没有一个人收到过。
 *
 * 所以这个页面把「最近一次测试」当成一等公民显示,而不是把它藏在一个
 * 点完就消失的提示框里。
 */

const loading = ref(false)
const rows = ref<AlertChannel[]>([])
const dialogVisible = ref(false)
const submitting = ref(false)
const testingId = ref<string | null>(null)
const editingId = ref<string | null>(null)

const form = reactive({
  name: '',
  type: 'EMAIL',
  recipients: '',
  from: '',
  url: '',
  headers: '',
})

const isEmail = computed(() => form.type === 'EMAIL')

function statusType(channel: AlertChannel): 'success' | 'danger' | 'info' {
  if (channel.status === 'UNREACHABLE') return 'danger'
  if (channel.status === 'DISABLED') return 'info'
  return 'success'
}

async function load() {
  loading.value = true
  try {
    rows.value = await channelApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openDialog(row: AlertChannel | null) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    name: row?.name ?? '',
    type: row?.type ?? 'EMAIL',
    // 编辑时不回填目标:后端返回的是脱敏后的描述,回填会把脱敏结果当成
    // 真实配置写回去。要改配置就重新填一遍 —— 这个摩擦是刻意的
    recipients: '',
    from: '',
    url: '',
    headers: '',
  })
  dialogVisible.value = true
}

function buildConfig(): Record<string, unknown> {
  if (isEmail.value) {
    const recipients = form.recipients
      .split(/[\s,;]+/)
      .map((s) => s.trim())
      .filter(Boolean)
    return form.from ? { recipients, from: form.from } : { recipients }
  }
  const config: Record<string, unknown> = { url: form.url.trim() }
  if (form.headers.trim()) {
    try {
      config.headers = JSON.parse(form.headers)
    } catch {
      throw new Error('请求头不是合法的 JSON')
    }
  }
  return config
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写渠道名称')
    return
  }
  let config: Record<string, unknown>
  try {
    config = buildConfig()
  } catch (e) {
    ElMessage.error((e as Error).message)
    return
  }
  submitting.value = true
  try {
    const payload = { name: form.name.trim(), type: form.type, config }
    if (editingId.value) {
      await channelApi.update(editingId.value, payload)
      ElMessage.success('已保存 —— 改过配置后需要重新做一次连通性测试')
    } else {
      await channelApi.create(payload)
      ElMessage.success('已创建。建议立刻做一次连通性测试')
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onTest(row: AlertChannel) {
  testingId.value = row.id
  try {
    const result = await channelApi.test(row.id)
    if (result.succeeded) {
      ElMessage.success(`测试通过:${result.message}`)
    } else {
      ElMessage.error(`测试失败:${result.message}`)
    }
    await load()
  } finally {
    testingId.value = null
  }
}

async function onToggle(row: AlertChannel) {
  await channelApi.setEnabled(row.id, row.status === 'DISABLED')
  await load()
}

async function onDelete(row: AlertChannel) {
  if (!(await confirmDanger(
    `确定删除渠道「${row.name}」吗?引用它的告警规则会变成「仅记录、不推送」。`,
    '删除渠道',
  ))) {
    return
  }
  await channelApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-alert type="info" :closable="false" show-icon style="margin: 0">
            <template #title>配好之后一定要做一次连通性测试</template>
            测试会<b>真的发一条消息出去</b>,而不是检查配置格式 ——
            后者能过而前者失败的情况太常见:端口被防火墙挡住、地址写对了但服务已下线。
          </el-alert>
        </div>
        <el-button
          v-permission="'governance:channel:manage'"
          type="primary"
          @click="openDialog(null)"
        >
          新建渠道
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" style="margin-top: 12px">
        <el-table-column label="名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ (row as AlertChannel).name }}</template>
        </el-table-column>

        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ (row as AlertChannel).type }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="目标" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-mono">{{ (row as AlertChannel).target }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row as AlertChannel)" size="small">
              {{ (row as AlertChannel).status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="最近测试" min-width="240">
          <template #default="{ row }">
            <!-- 从未测试过要显式说出来:它与"测试通过"完全不同 -->
            <span v-if="!(row as AlertChannel).lastTestedAt" class="text-muted">
              从未测试 —— 无法确认它能发出去
            </span>
            <span v-else>
              <el-tag
                :type="(row as AlertChannel).lastTestSucceeded ? 'success' : 'danger'"
                size="small"
              >
                {{ (row as AlertChannel).lastTestSucceeded ? '通过' : '失败' }}
              </el-tag>
              <span class="text-muted" style="margin-left: 6px">
                {{ formatDateTime((row as AlertChannel).lastTestedAt) }}
              </span>
              <div v-if="(row as AlertChannel).lastTestMessage" class="text-muted">
                {{ (row as AlertChannel).lastTestMessage }}
              </div>
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'governance:channel:manage'"
              link
              type="primary"
              :loading="testingId === (row as AlertChannel).id"
              @click="onTest(row as AlertChannel)"
            >
              测试
            </el-button>
            <el-button
              v-permission="'governance:channel:manage'"
              link
              type="primary"
              @click="openDialog(row as AlertChannel)"
            >
              编辑
            </el-button>
            <el-button
              v-permission="'governance:channel:manage'"
              link
              :type="(row as AlertChannel).status === 'DISABLED' ? 'success' : 'warning'"
              @click="onToggle(row as AlertChannel)"
            >
              {{ (row as AlertChannel).status === 'DISABLED' ? '启用' : '停用' }}
            </el-button>
            <el-button
              v-permission="'governance:channel:manage'"
              link
              type="danger"
              @click="onDelete(row as AlertChannel)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有渠道。没有渠道时告警仍会记录在「告警信息」里,但不会推送给任何人。
          </div>
        </template>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑告警渠道' : '新建告警渠道'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="渠道名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type" :disabled="!!editingId">
            <el-radio value="EMAIL">邮件</el-radio>
            <el-radio value="WEBHOOK">Webhook</el-radio>
          </el-radio-group>
          <div v-if="editingId" class="text-muted">类型不可修改 —— 要换请新建一个</div>
        </el-form-item>

        <template v-if="isEmail">
          <el-form-item label="收件人" required>
            <el-input
              v-model="form.recipients"
              type="textarea"
              :rows="3"
              placeholder="多个地址用逗号或换行分隔"
            />
          </el-form-item>
          <el-form-item label="发件人">
            <el-input v-model="form.from" placeholder="留空则用平台默认发件人" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="地址" required>
            <el-input v-model="form.url" placeholder="https://..." />
          </el-form-item>
          <el-form-item label="请求头">
            <el-input
              v-model="form.headers"
              type="textarea"
              :rows="3"
              class="text-mono"
              placeholder='{"Authorization": "Bearer ..."}'
            />
            <div class="text-muted">JSON 对象。留空表示不加额外的头</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
