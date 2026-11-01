<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import { useAgentSessionStore } from '@/stores/agentSession'

/**
 * 全局 Agent Dock。
 *
 * AskView 在 KeepAlive 里存活，SSE 不随切页断开；它把当前轮次摘要同步到
 * agentSession store，本组件只负责在业务页面上显示“Agent 还在做什么”，
 * 不再单独连一条 SSE。
 */
defineOptions({ name: 'AgentDock' })

const route = useRoute()
const router = useRouter()
const session = useAgentSessionStore()
const panelOpen = ref(false)
const dismissed = ref(false)

const visible = computed(() =>
  session.hasConversation && route.name !== 'insight-ask' && !dismissed.value,
)

const follow = computed({
  get: () => session.follow,
  set: (value: boolean) => session.setFollow(value),
})

const latestTools = computed(() => session.state.tools.slice(-8))

const currentTool = computed(() =>
  [...session.state.tools].reverse().find((tool) => tool.status === 'running'),
)

const pillText = computed(() => {
  if (session.running) {
    return currentTool.value
      ? `Agent 正在执行：${toolLabel(currentTool.value.name)}`
      : 'Agent 正在执行'
  }
  if (session.state.status === 'error') {
    return 'Agent 遇到问题'
  }
  if (session.state.status === 'done') {
    return 'Agent 已完成'
  }
  return 'Agent 助手'
})

const statusType = computed(() => {
  if (session.running) return 'primary'
  if (session.state.status === 'error') return 'danger'
  if (session.state.status === 'done') return 'success'
  return 'info'
})

const statusLabel = computed(() => {
  if (session.running) return '运行中'
  if (session.state.status === 'error') return '失败'
  if (session.state.status === 'done') return '完成'
  return '待命'
})

const canOpenTarget = computed(() => {
  const target = session.lastNavigate
  return Boolean(target && route.path !== target.route)
})

// 新一轮开始、或 Agent 主动切页时重新出现；用户回到对话/点关闭后收起。
watch(() => session.running, (running) => {
  if (running) {
    dismissed.value = false
  }
})
watch(() => session.lastNavigate, (target) => {
  if (target) {
    dismissed.value = false
  }
})
watch(() => route.name, (name) => {
  if (name === 'insight-ask') {
    dismissed.value = true
  }
})

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

function toolState(status: string): { label: string; type: 'success' | 'danger' | 'info' } {
  if (status === 'running') return { label: '进行中', type: 'info' }
  if (status === 'error') return { label: '失败', type: 'danger' }
  return { label: '完成', type: 'success' }
}

function openTarget() {
  const target = session.lastNavigate
  if (!target) {
    return
  }
  void router.push({ path: target.route, query: target.query })
}

function goChat() {
  panelOpen.value = false
  void router.push('/insight/ask')
}

function dismiss() {
  panelOpen.value = false
  dismissed.value = true
}

async function stop() {
  if (!session.state.conversationId) {
    return
  }
  await agentApi.abort(session.state.conversationId).catch(() => undefined)
  ElMessage.success('已请求停止 Agent')
}
</script>

<template>
  <div v-if="visible" class="agent-dock">
    <el-card v-if="panelOpen" class="agent-dock__panel" shadow="always">
      <template #header>
        <div class="agent-dock__header">
          <div class="agent-dock__heading">
            <span class="agent-dock__brand">Agent 助手</span>
            <el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag>
          </div>
          <el-button link size="small" @click="panelOpen = false">收起</el-button>
        </div>
      </template>

      <div class="agent-dock__title">{{ session.state.title || '新对话' }}</div>

      <div v-if="session.state.question" class="agent-dock__question">
        {{ session.state.question }}
      </div>

      <div v-if="session.state.text" class="agent-dock__text">
        {{ session.state.text }}
      </div>
      <div v-else-if="session.running" class="agent-dock__loading">
        正在执行，稍候…
      </div>

      <el-alert
        v-if="session.state.error"
        type="error"
        :closable="false"
        show-icon
        :title="session.state.error"
        class="agent-dock__alert"
      />

      <div v-if="latestTools.length" class="agent-dock__tools">
        <div v-for="(tool, index) in latestTools" :key="`${tool.name}-${index}`" class="agent-dock__tool">
          <el-tag size="small" :type="toolState(tool.status).type">
            {{ toolState(tool.status).label }}
          </el-tag>
          <span class="agent-dock__tool-name">{{ toolLabel(tool.name) }}</span>
          <span v-if="tool.summary" class="agent-dock__tool-summary">{{ tool.summary }}</span>
        </div>
      </div>

      <div class="agent-dock__follow">
        <el-switch v-model="follow" size="small" />
        <span class="agent-dock__follow-label">自动切换页面</span>
      </div>

      <div class="agent-dock__actions">
        <el-button
          v-if="canOpenTarget"
          size="small"
          type="primary"
          plain
          @click="openTarget"
        >
          打开{{ session.lastNavigate?.label }}
        </el-button>
        <el-button size="small" @click="goChat">回到对话</el-button>
        <el-button v-if="session.running" size="small" type="danger" plain @click="stop">
          停止
        </el-button>
        <el-button size="small" link @click="dismiss">关闭</el-button>
      </div>
    </el-card>

    <button type="button" class="agent-dock__pill" @click="panelOpen = !panelOpen">
      <el-badge :is-dot="session.running" :hidden="!session.running">
        <el-icon class="agent-dock__icon"><ChatDotRound /></el-icon>
      </el-badge>
      <span class="agent-dock__pill-text">{{ pillText }}</span>
    </button>
  </div>
</template>

<style scoped>
.agent-dock {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2500;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.agent-dock__panel {
  width: 370px;
  max-height: min(560px, calc(100vh - 120px));
  overflow-y: auto;
  border-radius: 12px;
}

.agent-dock__header,
.agent-dock__heading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-dock__header {
  justify-content: space-between;
}

.agent-dock__brand {
  font-weight: 600;
}

.agent-dock__title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}

.agent-dock__question {
  padding: 8px 10px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--dg-text);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
}

.agent-dock__text {
  max-height: 170px;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.agent-dock__loading {
  font-size: 13px;
  color: var(--dg-text-muted);
}

.agent-dock__alert {
  margin-top: 8px;
}

.agent-dock__tools {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 8px;
  margin-top: 10px;
  background: var(--dg-surface-muted);
  border-radius: 8px;
}

.agent-dock__tool {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
}

.agent-dock__tool-name {
  flex-shrink: 0;
}

.agent-dock__tool-summary {
  overflow: hidden;
  color: var(--dg-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-dock__follow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.agent-dock__follow-label {
  font-size: 12px;
  color: var(--dg-text-secondary);
}

.agent-dock__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.agent-dock__pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 360px;
  padding: 9px 14px;
  color: #fff;
  cursor: pointer;
  background: #1f2d3d;
  border: 0;
  border-radius: 999px;
  box-shadow: 0 6px 20px rgb(0 0 0 / 24%);
}

.agent-dock__pill:hover {
  background: #2b3b4e;
}

.agent-dock__icon {
  font-size: 16px;
}

.agent-dock__pill-text {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .agent-dock {
    right: 12px;
    bottom: 12px;
    left: 12px;
    align-items: stretch;
  }

  .agent-dock__panel {
    width: auto;
  }

  .agent-dock__pill {
    justify-content: center;
    max-width: none;
  }
}
</style>
