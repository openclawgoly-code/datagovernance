<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi, streamAgentEvents } from '@/api/agent'
import { insightApi } from '@/api/insight'
import { useAuthStore } from '@/stores/auth'
import { useAgentSessionStore } from '@/stores/agentSession'
import type { AgentConversation, AgentConversationSummary, AgentStreamEvent, AgentTurn } from '@/types/agent'
import type { InsightAnswer, InsightDashboard, InsightDataset } from '@/types/insight'
import { formatDateTime } from '@/utils/format'
import InsightAnswerChart from '@/components/insight/InsightAnswerChart.vue'

defineOptions({ name: 'AskView' })

/**
 * 智能问数（Pi agent 对话）。
 *
 * 后端 agent-service 直接嵌 @earendil-works/pi-agent-core；前端只和平台
 * `/api/v1/insight/agent/*` 对话，工具调用由 Java 内部端点完成治理。
 * 事件通过 fetch + SSE 读取，因为 EventSource 不能带 Authorization 头。
 */
const auth = useAuthStore()
const router = useRouter()
const agentSession = useAgentSessionStore()

const conversations = ref<AgentConversationSummary[]>([])
const conversation = ref<AgentConversation | null>(null)
const turns = ref<AgentTurn[]>([])
const datasets = ref<InsightDataset[]>([])
const datasetId = ref<number>()
const question = ref('')
const sending = ref(false)
const loading = ref(false)
const error = ref('')
const threadRef = ref<HTMLElement>()
const conversationsLoading = ref(false)
let streamController: AbortController | null = null
let lastSeq = 0
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

const examples = [
  '这个数据域有哪些表',
  '按状态统计订单数',
  '按区域统计总金额',
  '总金额最高的 3 个区域',
  '帮我接入本地 Parquet 数据源',
]

const TOOL_LABELS: Record<string, string> = {
  list_datasets: '列出数据集',
  ask_data: '数据查询',
  create_local_datasource: '创建本地数据源',
  update_local_datasource: '修改本地数据源',
  test_datasource: '测试连接',
  list_catalog_sources: '列出目录来源',
  create_parquet_catalog_source: '创建目录来源',
  refresh_catalog_source: '采集目录',
  navigate_workspace: '切换工作页面',
}

function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

const saveDialogVisible = ref(false)
const saveDashboards = ref<InsightDashboard[]>([])
const saveDashboardId = ref('')
const saveNewName = ref('')
const saveTarget = ref<AgentTurn | null>(null)
const exportingId = ref('')
const exportingXlsxId = ref('')

const isRunning = computed(() => sending.value || turns.value.some((turn) => turn.status === 'running'))
const canAsk = computed(() => question.value.trim().length > 0 && !isRunning.value)
const currentDatasetLabel = computed(() => {
  const item = datasets.value.find((dataset) => dataset.id === datasetId.value)
  return item ? `${item.domain} / ${item.name}` : (conversation.value?.datasetLabel || '默认数据集')
})

function datasetLabelOf(id?: number): string {
  const item = datasets.value.find((dataset) => dataset.id === id)
  return item ? `${item.domain} / ${item.name}` : '默认数据集'
}

/**
 * 把当前轮次镜像到全局会话状态，供跨页面的 AgentDock 展示。
 * 完整消息仍在 AskView 里；这里只保留标题、最新问题、正文和工具状态。
 */
function syncSession(persist = true) {
  if (!conversation.value) {
    return
  }
  const last = turns.value[turns.value.length - 1]
  agentSession.update({
    conversationId: conversation.value.id,
    title: conversation.value.title,
    question: last?.question ?? '',
    text: last?.text ?? '',
    status: last?.status ?? 'idle',
    tools: (last?.tools ?? []).map((tool) => ({
      name: tool.name,
      status: tool.status,
      summary: tool.summary,
    })),
    error: last?.error,
    datasetId: datasetId.value,
    datasetLabel: datasetLabelOf(datasetId.value),
  }, persist)
}

async function loadDatasets() {
  try {
    const [list, config] = await Promise.all([
      insightApi.datasets(),
      insightApi.config().catch(() => null),
    ])
    datasets.value = list
    if (!datasetId.value) {
      const preferred = config?.defaultDatasetId
        ? list.find((item) => item.id === config.defaultDatasetId)
        : undefined
      datasetId.value = preferred?.id ?? list[0]?.id
    }
  } catch {
    datasets.value = []
  }
}

async function loadConversations() {
  conversationsLoading.value = true
  try {
    conversations.value = await agentApi.list()
    if (conversation.value) {
      const current = conversations.value.find((item) => item.id === conversation.value?.id)
      if (current) {
        conversation.value.title = current.title
        conversation.value.updatedAt = current.updatedAt
        conversation.value.running = current.running
      }
    }
  } finally {
    conversationsLoading.value = false
  }
}

async function openConversation(id: string) {
  if (conversation.value?.id === id) {
    return
  }
  stopStream()
  loading.value = true
  error.value = ''
  try {
    const detail = await agentApi.get(id)
    conversation.value = detail
    turns.value = detail.turns ?? []
    lastSeq = Math.max(0, ...(detail.events ?? []).map((event) => event.seq))
    if (detail.datasetId) {
      datasetId.value = detail.datasetId
    }
    agentSession.setActive({
      id: detail.id,
      title: detail.title,
      datasetId: detail.datasetId,
      datasetLabel: detail.datasetLabel,
    })
    syncSession()
    connectStream()
    await scrollToEnd()
  } catch {
    error.value = 'Agent 会话加载失败'
  } finally {
    loading.value = false
  }
}

async function newConversation() {
  stopStream()
  conversation.value = null
  turns.value = []
  lastSeq = 0
  question.value = ''
  error.value = ''
  agentSession.clear()
}

async function ensureConversation(): Promise<AgentConversation> {
  if (conversation.value) {
    return conversation.value
  }
  const created = await agentApi.create({
    datasetId: datasetId.value,
    datasetLabel: datasetLabelOf(datasetId.value),
  })
  conversation.value = created
  turns.value = []
  lastSeq = 0
  agentSession.setActive({
    id: created.id,
    title: created.title,
    datasetId: created.datasetId,
    datasetLabel: created.datasetLabel,
  })
  syncSession()
  connectStream()
  return created
}

function connectStream() {
  if (!conversation.value) {
    return
  }
  streamController?.abort()
  const controller = new AbortController()
  streamController = controller
  const id = conversation.value.id
  void streamAgentEvents(id, lastSeq, applyEvent, controller.signal).catch((streamError) => {
    if (controller.signal.aborted) {
      return
    }
    error.value = streamError instanceof Error ? streamError.message : 'Agent 事件流中断'
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
    }
    reconnectTimer = setTimeout(() => {
      if (conversation.value?.id === id && !controller.signal.aborted) {
        connectStream()
      }
    }, 2000)
  })
}

function stopStream() {
  streamController?.abort()
  streamController = null
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function applyEvent(event: AgentStreamEvent) {
  lastSeq = Math.max(lastSeq, Number(event.seq ?? 0))
  const turnId = typeof event.turnId === 'string' ? event.turnId : ''
  switch (event.type) {
    case 'turn_start': {
      const turn = ensureTurn(turnId, String(event.question ?? ''))
      turn.status = 'running'
      void scrollToEnd()
      break
    }
    case 'text_delta': {
      const turn = ensureTurn(turnId, '')
      turn.text += String(event.delta ?? '')
      void scrollToEnd()
      break
    }
    case 'thinking_delta': {
      const turn = ensureTurn(turnId, '')
      turn.thinking += String(event.delta ?? '')
      break
    }
    case 'tool_start': {
      const turn = ensureTurn(turnId, '')
      const toolId = String(event.toolCallId ?? '')
      if (!turn.tools.some((tool) => tool.id === toolId)) {
        turn.tools.push({
          id: toolId,
          name: String(event.name ?? 'tool'),
          status: 'running',
          args: event.args,
          startedAt: new Date().toISOString(),
        })
      }
      break
    }
    case 'tool_end': {
      const turn = ensureTurn(turnId, '')
      const tool = turn.tools.find((item) => item.id === String(event.toolCallId ?? ''))
      if (tool) {
        tool.status = event.isError ? 'error' : 'ok'
        tool.summary = event.summary ? String(event.summary) : undefined
        tool.finishedAt = new Date().toISOString()
      }
      if (event.answer) {
        turn.answer = event.answer as InsightAnswer
      }
      break
    }
    case 'done': {
      const turn = ensureTurn(turnId, '')
      turn.status = (String(event.status ?? 'done') as AgentTurn['status'])
      if (event.text) {
        turn.text = String(event.text)
      }
      if (event.answer) {
        turn.answer = event.answer as InsightAnswer
      }
      if (event.error) {
        turn.error = String(event.error)
      }
      turn.finishedAt = new Date().toISOString()
      if (conversation.value) {
        conversation.value.running = false
      }
      sending.value = false
      void loadConversations()
      void scrollToEnd()
      break
    }
    case 'error': {
      const turn = ensureTurn(turnId, '')
      turn.status = 'error'
      turn.error = String(event.message ?? 'Agent 运行失败')
      sending.value = false
      break
    }
    case 'navigate': {
      const route = String(event.route ?? '')
      const label = String(event.label ?? route)
      const query = (event.query ?? {}) as Record<string, string>
      const allowed = ['/metadata/datasources', '/metadata/catalog', '/insight/ask']
      if (route && allowed.includes(route)) {
        agentSession.setNavigate({ route, label, query })
        // KeepAlive 下 AskView 即使被切走也还活着，所以这里仍能跟随后续步骤切页。
        // 用户可以在 Dock 里关闭“自动切换页面”，改为手动打开。
        if (agentSession.follow) {
          const target = router.resolve({ path: route, query })
          if (router.currentRoute.value.fullPath !== target.fullPath) {
            void router.push(target)
          }
        }
      }
      break
    }
    default:
      break
  }
  // 流式 token 只更新内存，不每个 delta 都写 sessionStorage；关键事件才落盘。
  syncSession(event.type !== 'text_delta' && event.type !== 'thinking_delta')
}

function ensureTurn(turnId: string, questionText: string): AgentTurn {
  let turn = turns.value.find((item) => item.id === turnId)
  if (!turn) {
    turn = {
      id: turnId || `turn_${Date.now()}`,
      question: questionText,
      datasetId: datasetId.value,
      status: 'running',
      text: '',
      thinking: '',
      tools: [],
      createdAt: new Date().toISOString(),
    }
    turns.value.push(turn)
  } else if (!turn.question && questionText) {
    turn.question = questionText
  }
  return turn
}

async function send() {
  const text = question.value.trim()
  if (!text || sending.value) {
    return
  }
  sending.value = true
  error.value = ''
  try {
    const target = await ensureConversation()
    const result = await agentApi.send(target.id, {
      text,
      datasetId: datasetId.value,
      datasetLabel: datasetLabelOf(datasetId.value),
    })
    if (!turns.value.some((turn) => turn.id === result.turnId)) {
      turns.value.push({
        id: result.turnId,
        question: text,
        datasetId: datasetId.value,
        status: 'running',
        text: '',
        thinking: '',
        tools: [],
        createdAt: new Date().toISOString(),
      })
    }
    syncSession()
    question.value = ''
    await loadConversations()
    await scrollToEnd()
  } catch (sendError: any) {
    sending.value = false
    error.value = sendError?.message || 'Agent 发送失败'
  }
}

function onKey(event: Event | KeyboardEvent) {
  const keyEvent = event as KeyboardEvent
  if (keyEvent.key !== 'Enter' || keyEvent.shiftKey || keyEvent.isComposing
    || (keyEvent as KeyboardEvent & { keyCode?: number }).keyCode === 229) {
    return
  }
  event.preventDefault()
  void send()
}

async function stop() {
  if (!conversation.value) {
    return
  }
  await agentApi.abort(conversation.value.id).catch(() => undefined)
  ElMessage.success('已请求停止')
}

async function scrollToEnd() {
  await nextTick()
  const element = threadRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

function useExample(example: string) {
  question.value = example
}

function stateMeta(turn: AgentTurn): { label: string; type: 'success' | 'primary' | 'danger' | 'info' } {
  if (turn.status === 'running') {
    return { label: '运行中', type: 'primary' }
  }
  if (turn.status === 'error') {
    return { label: '失败', type: 'danger' }
  }
  if (turn.status === 'aborted') {
    return { label: '已停止', type: 'info' }
  }
  return { label: '完成', type: 'success' }
}

function toolState(tool: { status: string }): { label: string; type: 'success' | 'danger' | 'info' } {
  if (tool.status === 'running') {
    return { label: '运行中', type: 'info' }
  }
  return tool.status === 'error'
    ? { label: '失败', type: 'danger' }
    : { label: '完成', type: 'success' }
}

function columnsOf(answer: InsightAnswer) {
  if (answer.columns?.length) {
    return answer.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
}

/**
 * Agent 的正文可能带 Markdown 表格；结构化结果已经有表格组件，
 * 这里去掉表格行，避免同一份数据渲染两遍。
 */
function displayText(text: string): string {
  return text
    .replace(/^\s*\|.*\|\s*$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function sqlOf(answer: InsightAnswer): string {
  return answer.sql || ''
}

async function openSaveDialog(turn: AgentTurn) {
  saveTarget.value = turn
  saveDashboards.value = await insightApi.dashboards()
  saveDashboardId.value = saveDashboards.value[0]?.id ?? ''
  saveNewName.value = ''
  saveDialogVisible.value = true
}

async function saveToDashboard() {
  const target = saveTarget.value
  if (!target) {
    return
  }
  let dashboardId = saveDashboardId.value
  if (!dashboardId) {
    if (!saveNewName.value.trim()) {
      ElMessage.warning('请选择看板或输入新看板名称')
      return
    }
    const created = await insightApi.createDashboard({ name: saveNewName.value.trim() })
    dashboardId = created.id
  }
  await insightApi.addDashboardItem(dashboardId, {
    question: target.question,
    datasetId: target.datasetId ?? conversation.value?.datasetId,
  })
  ElMessage.success('已保存到看板')
  saveDialogVisible.value = false
  saveTarget.value = null
}

async function exportTurn(turn: AgentTurn, format: 'csv' | 'xlsx') {
  const payload = {
    question: turn.question,
    dataSetId: turn.datasetId ?? conversation.value?.datasetId,
  }
  if (format === 'csv') {
    if (exportingId.value) {
      return
    }
    exportingId.value = turn.id
    try {
      download(await insightApi.exportCsv(payload), 'csv')
    } finally {
      exportingId.value = ''
    }
    return
  }
  if (exportingXlsxId.value) {
    return
  }
  exportingXlsxId.value = turn.id
  try {
    download(await insightApi.exportXlsx(payload), 'xlsx')
  } finally {
    exportingXlsxId.value = ''
  }
}

function download(blob: Blob, extension: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `insight-${Date.now()}.${extension}`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  await Promise.all([loadDatasets(), loadConversations()])
  const preferred = agentSession.state.conversationId
    ? conversations.value.find((item) => item.id === agentSession.state.conversationId)
    : undefined
  if (agentSession.state.conversationId && !preferred) {
    // 会话可能已被删除，或当前用户/空间已切换；不要继续指向一个不存在的会话。
    agentSession.clear()
  }
  const target = preferred ?? conversations.value[0]
  if (target) {
    await openConversation(target.id)
  }
})

onBeforeUnmount(() => {
  stopStream()
})
</script>

<template>
  <div class="agent-chat page-container">
    <aside class="chat-side">
      <div class="side-head">
        <div>
          <div class="side-title">Agent 对话</div>
          <div class="side-hint">Pi agent 驱动，带工具过程</div>
        </div>
        <el-button link type="primary" @click="newConversation">新对话</el-button>
      </div>
      <el-scrollbar class="side-scroll" v-loading="conversationsLoading">
        <el-empty
          v-if="!conversationsLoading && !conversations.length"
          description="还没有对话"
          :image-size="72"
        />
        <button
          v-for="item in conversations"
          :key="item.id"
          type="button"
          class="conversation-item"
          :class="{ 'conversation-item--active': conversation?.id === item.id }"
          @click="openConversation(item.id)"
        >
          <div class="conversation-title">{{ item.title }}</div>
          <div class="conversation-meta">
            <el-tag v-if="item.running" size="small" type="primary">运行中</el-tag>
            <span>{{ item.turnCount }} 轮 · {{ formatDateTime(item.updatedAt) }}</span>
          </div>
        </button>
      </el-scrollbar>
      <div class="side-foot">
        <span class="text-muted">模型：{{ auth.user?.displayName || auth.user?.username }} / Pi agent</span>
      </div>
    </aside>

    <section class="chat-main">
      <header class="chat-head">
        <div class="chat-heading">
          <h2>智能问数</h2>
          <p>多轮对话 · 工具过程可见 · 敏感结果经平台脱敏</p>
        </div>
        <el-select
          v-model="datasetId"
          filterable
          placeholder="选择数据集"
          class="dataset-select"
        >
          <el-option
            v-for="item in datasets"
            :key="item.id"
            :label="`${item.domain} / ${item.name}`"
            :value="item.id"
          />
        </el-select>
      </header>

      <div ref="threadRef" v-loading="loading" class="chat-thread">
        <div v-if="!turns.length" class="chat-empty">
          <el-empty description="问一个问题开始 Agent 对话" :image-size="88">
            <div class="example-list">
              <el-button
                v-for="example in examples"
                :key="example"
                link
                type="primary"
                @click="useExample(example)"
              >
                {{ example }}
              </el-button>
            </div>
          </el-empty>
        </div>

        <div v-for="turn in turns" :key="turn.id" class="turn">
          <div class="user-msg">
            <div class="user-bubble">{{ turn.question || '（新问题）' }}</div>
            <div class="turn-meta">
              {{ datasetLabelOf(turn.datasetId) }} · {{ formatDateTime(turn.createdAt) }}
            </div>
          </div>

          <div class="assistant-card">
            <div class="answer-head">
              <div class="answer-badges">
                <el-tag :type="stateMeta(turn).type" size="small">{{ stateMeta(turn).label }}</el-tag>
                <el-tag v-if="turn.tools.length" size="small" type="info">
                  工具调用 {{ turn.tools.length }}
                </el-tag>
                <el-tag v-if="turn.answer?.maskedColumns?.length" size="small" type="warning">
                  已脱敏 {{ turn.answer.maskedColumns.length }} 列
                </el-tag>
              </div>
              <div v-if="turn.status === 'done'" class="answer-actions">
                <el-button
                  v-if="auth.hasPermission('insight:dashboard:manage')"
                  size="small"
                  @click="openSaveDialog(turn)"
                >
                  保存到看板
                </el-button>
                <el-button
                  v-if="auth.hasPermission('insight:export')"
                  size="small"
                  :loading="exportingId === turn.id"
                  @click="exportTurn(turn, 'csv')"
                >
                  导出 CSV
                </el-button>
                <el-button
                  v-if="auth.hasPermission('insight:export')"
                  size="small"
                  :loading="exportingXlsxId === turn.id"
                  @click="exportTurn(turn, 'xlsx')"
                >
                  导出 XLSX
                </el-button>
              </div>
            </div>

            <el-collapse v-if="turn.thinking" class="thinking">
              <el-collapse-item title="思考过程" name="thinking">
                <div class="thinking-text">{{ turn.thinking }}</div>
              </el-collapse-item>
            </el-collapse>

            <div v-if="turn.tools.length" class="tool-list">
              <div v-for="tool in turn.tools" :key="tool.id" class="tool-step">
                <el-tag size="small" :type="toolState(tool).type">{{ toolState(tool).label }}</el-tag>
                <span class="tool-name">{{ toolLabel(tool.name) }}</span>
                <span v-if="tool.summary" class="text-muted tool-summary">{{ tool.summary }}</span>
              </div>
            </div>

            <div v-if="turn.text" class="agent-text">{{ displayText(turn.text) }}</div>
            <el-skeleton
              v-if="turn.status === 'running' && !turn.text"
              :rows="3"
              animated
            />
            <el-alert
              v-if="turn.error"
              type="error"
              show-icon
              :closable="false"
              :title="turn.error"
              class="agent-alert"
            />

            <el-alert
              v-if="turn.answer?.maskedColumns?.length"
              type="warning"
              show-icon
              :closable="false"
              class="agent-alert"
              :title="`已按敏感标签遮蔽：${turn.answer.maskedColumns.join('、')}`"
            />

            <template v-if="turn.answer">
              <InsightAnswerChart :answer="turn.answer" />
              <el-alert
                v-if="turn.answer.summary && !turn.text"
                type="info"
                :closable="false"
                class="agent-alert"
                :title="turn.answer.summary"
              />
              <el-table
                :data="turn.answer.rows"
                size="small"
                border
                max-height="360"
                class="answer-table"
              >
                <el-table-column
                  v-for="column in columnsOf(turn.answer)"
                  :key="column.key"
                  :prop="column.key"
                  :label="column.label"
                  min-width="130"
                  show-overflow-tooltip
                />
              </el-table>
              <el-collapse v-if="sqlOf(turn.answer)" class="answer-sql">
                <el-collapse-item title="查看生成 SQL" name="sql">
                  <pre>{{ sqlOf(turn.answer) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </template>
          </div>
        </div>
      </div>

      <footer class="composer">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          maxlength="4000"
          show-word-limit
          resize="none"
          placeholder="问点什么，例如：这个数据域有哪些表；按状态统计订单数"
          @keydown="onKey"
        />
        <div class="composer-foot">
          <span class="text-muted">
            当前数据集：{{ currentDatasetLabel }} · Enter 发送，Shift+Enter 换行
          </span>
          <div class="composer-actions">
            <el-button v-if="isRunning" type="danger" plain @click="stop">停止</el-button>
            <el-button type="primary" :loading="sending" :disabled="!canAsk" @click="send">
              发送
            </el-button>
          </div>
        </div>
        <el-alert
          v-if="error"
          type="warning"
          show-icon
          :closable="false"
          :title="error"
          class="composer-error"
        />
      </footer>
    </section>

    <el-dialog v-model="saveDialogVisible" title="保存到看板" width="460px">
      <el-form label-position="top">
        <el-form-item label="已有看板">
          <el-select v-model="saveDashboardId" clearable placeholder="选择已有看板" style="width: 100%">
            <el-option
              v-for="item in saveDashboards"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="或新建看板">
          <el-input v-model="saveNewName" maxlength="128" placeholder="例如：销售日看板" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveToDashboard">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-chat {
  display: flex;
  gap: var(--dg-space-md);
  height: calc(100vh - 60px);
  min-height: 560px;
  padding: var(--dg-page-padding);
  box-sizing: border-box;
}

.chat-side {
  display: flex;
  flex-direction: column;
  flex: 0 0 260px;
  min-height: 0;
  padding: var(--dg-space-sm);
  background: #fff;
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.side-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  padding: var(--dg-space-sm) var(--dg-space-sm) var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.side-title {
  font-size: 14px;
  font-weight: 600;
}

.side-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.side-scroll {
  flex: 1;
  min-height: 0;
  padding-top: var(--dg-space-sm);
}

.conversation-item {
  display: block;
  width: 100%;
  padding: var(--dg-space-sm);
  border: 0;
  border-radius: var(--dg-radius);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.conversation-item:hover,
.conversation-item--active {
  background: var(--el-fill-color-light);
}

.conversation-title {
  overflow: hidden;
  font-size: 13px;
  line-height: 1.4;
  color: var(--dg-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-meta {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  margin-top: 4px;
  font-size: 11px;
  color: var(--dg-text-muted);
}

.side-foot {
  padding-top: var(--dg-space-sm);
  border-top: 1px solid var(--dg-border);
}

.chat-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.chat-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dg-space-md);
  padding: var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.chat-heading h2 {
  margin: 0;
  font-size: 18px;
}

.chat-heading p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.dataset-select {
  width: 300px;
}

.chat-thread {
  flex: 1;
  min-height: 0;
  padding: var(--dg-space-lg);
  overflow-y: auto;
  background: var(--dg-canvas);
}

.chat-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.example-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--dg-space-sm);
  max-width: 560px;
}

.turn {
  margin-bottom: var(--dg-space-lg);
}

.user-msg {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  margin-left: auto;
  max-width: 82%;
}

.user-bubble {
  padding: 8px 12px;
  color: var(--dg-text);
  white-space: pre-wrap;
  background: var(--el-color-primary-light-9);
  border-radius: 10px 10px 2px 10px;
}

.turn-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--dg-text-muted);
}

.assistant-card {
  max-width: 100%;
  margin-top: var(--dg-space-sm);
  padding: var(--dg-space-md);
  background: #fff;
  border: 1px solid var(--dg-border);
  border-radius: 2px 10px 10px 10px;
}

.answer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
  flex-wrap: wrap;
}

.answer-badges,
.answer-actions,
.composer-actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  flex-wrap: wrap;
}

.agent-text {
  margin: var(--dg-space-sm) 0;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--dg-space-sm);
  margin: var(--dg-space-sm) 0;
  background: var(--dg-surface-muted);
  border-radius: var(--dg-radius);
}

.tool-step {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  min-width: 0;
}

.tool-name {
  font-family: var(--dg-font-mono);
  font-size: 12px;
}

.tool-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thinking {
  margin: var(--dg-space-sm) 0;
}

.thinking-text {
  font-size: 12px;
  line-height: 1.6;
  color: var(--dg-text-secondary);
  white-space: pre-wrap;
}

.agent-alert {
  margin: var(--dg-space-sm) 0;
}

.answer-table {
  margin-top: var(--dg-space-sm);
}

.answer-sql {
  margin-top: var(--dg-space-sm);
}

.answer-sql pre {
  margin: 0;
  padding: var(--dg-space-sm);
  overflow-x: auto;
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
  background: var(--dg-surface-muted);
  border-radius: var(--dg-radius);
}

.composer {
  padding: var(--dg-space-md);
  border-top: 1px solid var(--dg-border);
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-top: var(--dg-space-sm);
}

.composer-error {
  margin-top: var(--dg-space-sm);
}

@media (max-width: 900px) {
  .agent-chat {
    height: auto;
    flex-direction: column;
  }

  .chat-side {
    flex: none;
    max-height: 240px;
  }

  .chat-main {
    min-height: 70vh;
  }

  .dataset-select {
    width: 100%;
  }

  .chat-head {
    flex-direction: column;
  }
}
</style>
