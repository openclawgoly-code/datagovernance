import type { InsightAnswer } from './insight'

export interface AgentToolStep {
  id: string
  name: string
  status: 'running' | 'ok' | 'error'
  args?: unknown
  summary?: string
  startedAt: string
  finishedAt?: string
}

export interface AgentTurn {
  id: string
  question: string
  datasetId?: number
  status: 'running' | 'done' | 'error' | 'aborted'
  text: string
  thinking: string
  tools: AgentToolStep[]
  answer?: InsightAnswer
  error?: string
  createdAt: string
  finishedAt?: string
}

export interface AgentConversationSummary {
  id: string
  workspaceId: string
  username: string
  title: string
  datasetId?: number
  datasetLabel?: string
  createdAt: string
  updatedAt: string
  running: boolean
  turnCount: number
}

export interface AgentConversation extends AgentConversationSummary {
  turns: AgentTurn[]
  events?: Array<{ seq: number; type: string; turnId?: string }>
}

export interface AgentStreamEvent {
  seq: number
  type: string
  turnId?: string
  [key: string]: unknown
}
