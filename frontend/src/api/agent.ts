import { http } from './request'
import { useAuthStore } from '@/stores/auth'
import type {
  AgentConversation,
  AgentConversationSummary,
  AgentStreamEvent,
} from '@/types/agent'

/** Pi agent 服务（经 Java 代理鉴权）。 */
export const agentApi = {
  list(): Promise<AgentConversationSummary[]> {
    return http.get<AgentConversationSummary[]>('/insight/agent/conversations')
  },

  create(payload: {
    title?: string
    datasetId?: number
    datasetLabel?: string
  } = {}): Promise<AgentConversation> {
    return http.post<AgentConversation>('/insight/agent/conversations', payload)
  },

  get(id: string): Promise<AgentConversation> {
    return http.get<AgentConversation>(`/insight/agent/conversations/${id}`)
  },

  send(id: string, payload: {
    text: string
    datasetId?: number
    datasetLabel?: string
  }): Promise<{ turnId: string }> {
    return http.post<{ turnId: string }>(
      `/insight/agent/conversations/${id}/messages`,
      payload,
    )
  },

  abort(id: string): Promise<{ aborted: boolean }> {
    return http.post<{ aborted: boolean }>(`/insight/agent/conversations/${id}/abort`)
  },
}

/**
 * 用 fetch 读 SSE，而不是 EventSource：EventSource 不能带 Authorization 头，
 * 平台 JWT 不能放进 URL。这里手动解析 `id/event/data` 帧。
 */
export async function streamAgentEvents(
  id: string,
  after: number,
  onEvent: (event: AgentStreamEvent) => void,
  signal: AbortSignal,
) {
  const auth = useAuthStore()
  const response = await fetch(
    `/api/v1/insight/agent/conversations/${encodeURIComponent(id)}/events?after=${after}`,
    {
      headers: {
        Accept: 'text/event-stream',
        ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
        ...(auth.workspaceId ? { 'X-Workspace-Id': auth.workspaceId } : {}),
      },
      signal,
    },
  )
  if (!response.ok || !response.body) {
    throw new Error(`Agent 事件流连接失败（HTTP ${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseFrame(frame)
      if (event) {
        onEvent(event)
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

function parseFrame(frame: string): AgentStreamEvent | null {
  let seq = 0
  let type = ''
  const data: string[] = []
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.replace(/\r$/, '')
    if (line.startsWith('id:')) {
      seq = Number(line.slice(3).trim())
    } else if (line.startsWith('event:')) {
      type = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trim())
    }
  }
  if (!type || data.length === 0) {
    return null
  }
  try {
    const payload = JSON.parse(data.join('\n')) as Record<string, unknown>
    return { ...payload, seq: Number(payload.seq ?? seq), type } as AgentStreamEvent
  } catch {
    return null
  }
}
