export interface UiToolStep {
  id: string
  name: string
  status: 'running' | 'ok' | 'error'
  args?: unknown
  summary?: string
  startedAt: string
  finishedAt?: string
}

export interface UiTurn {
  id: string
  question: string
  datasetId?: number
  status: 'running' | 'done' | 'error' | 'aborted'
  text: string
  thinking: string
  tools: UiToolStep[]
  answer?: unknown
  error?: string
  createdAt: string
  finishedAt?: string
}

export interface StoredEvent {
  seq: number
  type: string
  turnId?: string
  payload: Record<string, unknown>
}

export interface Conversation {
  id: string
  workspaceId: string
  userId?: string
  platformAdmin?: boolean
  username: string
  title: string
  datasetId?: number
  datasetLabel?: string
  createdAt: string
  updatedAt: string
  messages: unknown[]
  turns: UiTurn[]
  events: StoredEvent[]
  seq: number
  running: boolean
}

export interface RunContext {
  workspaceId: string
  userId?: string
  platformAdmin?: boolean
  username: string
  datasetId?: number
  datasetLabel?: string
}
