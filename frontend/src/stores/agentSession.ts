import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/** Agent 在别的页面继续工作时，Dock 只需要这一份轻量状态。 */
export interface AgentSessionTool {
  name: string
  status: 'running' | 'ok' | 'error' | string
  summary?: string
}

export interface AgentSessionState {
  conversationId: string
  title: string
  question: string
  text: string
  status: string
  tools: AgentSessionTool[]
  error?: string
  datasetId?: number
  datasetLabel?: string
}

export interface AgentNavigateTarget {
  route: string
  label: string
  query?: Record<string, string>
}

const SESSION_KEY = 'dg.agent.session'
const FOLLOW_KEY = 'dg.agent.follow'

const EMPTY: AgentSessionState = {
  conversationId: '',
  title: '新对话',
  question: '',
  text: '',
  status: 'idle',
  tools: [],
}

function readSession(): AgentSessionState {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (!raw) {
      return { ...EMPTY }
    }
    const parsed = JSON.parse(raw) as Partial<AgentSessionState>
    return {
      ...EMPTY,
      ...parsed,
      tools: Array.isArray(parsed.tools) ? parsed.tools : [],
    }
  } catch {
    return { ...EMPTY }
  }
}

function readFollow(): boolean {
  try {
    return sessionStorage.getItem(FOLLOW_KEY) !== 'false'
  } catch {
    return true
  }
}

/**
 * 跨页面保存 Agent 会话的“当前状态”。
 *
 * 完整会话仍由 AskView 持有；AskView 在 KeepAlive 里存活，SSE 不会因为切页断开。
 * 这里只镜像一份摘要给全局 Dock 渲染，避免两个组件各连一条 SSE。
 */
export const useAgentSessionStore = defineStore('agentSession', () => {
  const state = ref<AgentSessionState>(readSession())
  const follow = ref(readFollow())
  const lastNavigate = ref<AgentNavigateTarget | null>(null)

  const running = computed(() => state.value.status === 'running')
  const hasConversation = computed(() => Boolean(state.value.conversationId))

  function persist() {
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(state.value))
    } catch {
      // 隐私模式下 sessionStorage 可能不可用；只影响刷新恢复，不影响当前页面。
    }
  }

  function update(patch: Partial<AgentSessionState>, persistState = true) {
    state.value = { ...state.value, ...patch }
    if (persistState) {
      persist()
    }
  }

  function setActive(input: {
    id: string
    title?: string
    datasetId?: number
    datasetLabel?: string
  }) {
    if (state.value.conversationId !== input.id) {
      state.value = {
        ...EMPTY,
        conversationId: input.id,
        title: input.title || '新对话',
        datasetId: input.datasetId,
        datasetLabel: input.datasetLabel,
      }
      lastNavigate.value = null
    } else {
      state.value = {
        ...state.value,
        title: input.title || state.value.title,
        datasetId: input.datasetId ?? state.value.datasetId,
        datasetLabel: input.datasetLabel ?? state.value.datasetLabel,
      }
    }
    persist()
  }

  function setNavigate(target: AgentNavigateTarget) {
    lastNavigate.value = target
  }

  function setFollow(value: boolean) {
    follow.value = value
    try {
      sessionStorage.setItem(FOLLOW_KEY, String(value))
    } catch {
      // 同 persist
    }
  }

  function clear() {
    state.value = { ...EMPTY }
    lastNavigate.value = null
    try {
      sessionStorage.removeItem(SESSION_KEY)
    } catch {
      // 同 persist
    }
  }

  return {
    state,
    follow,
    lastNavigate,
    running,
    hasConversation,
    update,
    setActive,
    setNavigate,
    setFollow,
    clear,
  }
})
