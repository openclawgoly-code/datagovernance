import express from 'express'
import cors from 'cors'
import { EventEmitter } from 'node:events'
import { randomUUID } from 'node:crypto'
import { config } from './config.js'
import { ConversationStore } from './store.js'
import { agentRuntime, runTurn } from './agent.js'
import type { Conversation, UiTurn } from './types.js'

const app = express()
const store = new ConversationStore()
const bus = new EventEmitter()
bus.setMaxListeners(0)

app.use(cors({
  origin: [
    'http://localhost:5174',
    'http://127.0.0.1:5174',
    'http://localhost:18080',
    'http://127.0.0.1:18080',
  ],
}))
app.use(express.json({ limit: '1mb' }))

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    model: agentRuntime.modelId,
    dataDir: config.dataDir,
  })
})

app.get('/conversations', (req, res) => {
  const workspaceId = String(req.query.workspaceId ?? '')
  const username = String(req.query.username ?? '')
  if (!workspaceId || !username) {
    res.status(400).json({ error: 'workspaceId 和 username 必填' })
    return
  }
  res.json(store.list(workspaceId, username).map(summary))
})

app.post('/conversations', (req, res) => {
  const { workspaceId, username, datasetId, datasetLabel } = req.body ?? {}
  if (!workspaceId || !username) {
    res.status(400).json({ error: 'workspaceId 和 username 必填' })
    return
  }
  const conversation = store.create({
    workspaceId: String(workspaceId),
    username: String(username),
    datasetId: optionalNumber(datasetId),
    datasetLabel: datasetLabel ? String(datasetLabel) : undefined,
  })
  res.status(201).json(detail(conversation))
})

app.get('/conversations/:id', (req, res) => {
  const conversation = store.get(req.params.id)
  if (!conversation) {
    res.status(404).json({ error: '会话不存在' })
    return
  }
  res.json(detail(conversation))
})

app.post('/conversations/:id/messages', async (req, res) => {
  const conversation = store.get(req.params.id)
  if (!conversation) {
    res.status(404).json({ error: '会话不存在' })
    return
  }
  if (conversation.running) {
    res.status(409).json({ error: '当前会话仍在运行，请先停止或等待完成' })
    return
  }
  const text = String(req.body?.text ?? '').trim()
  if (!text) {
    res.status(400).json({ error: '消息不能为空' })
    return
  }
  if (text.length > 4000) {
    res.status(400).json({ error: '消息不能超过 4000 字' })
    return
  }
  const datasetId = optionalNumber(req.body?.datasetId)
  if (datasetId !== undefined) {
    conversation.datasetId = datasetId
    conversation.datasetLabel = req.body?.datasetLabel
      ? String(req.body.datasetLabel) : conversation.datasetLabel
  }
  const turn: UiTurn = {
    id: `turn_${randomUUID().replaceAll('-', '').slice(0, 20)}`,
    question: text,
    datasetId: datasetId ?? conversation.datasetId,
    status: 'running',
    text: '',
    thinking: '',
    tools: [],
    createdAt: new Date().toISOString(),
  }
  conversation.turns.push(turn)
  conversation.running = true
  store.save(conversation)

  void runTurn(conversation, turn, text, {
    workspaceId: conversation.workspaceId,
    username: conversation.username,
    datasetId: conversation.datasetId,
    datasetLabel: conversation.datasetLabel,
  }, store, bus).catch((error) => {
    conversation.running = false
    turn.status = 'error'
    turn.error = error instanceof Error ? error.message : String(error)
    turn.finishedAt = new Date().toISOString()
    store.save(conversation)
    const event = store.appendEvent(conversation, 'done', turn.id, {
      status: 'error',
      text: turn.text,
      error: turn.error,
    })
    bus.emit(conversation.id, event)
  })

  res.status(202).json({ turnId: turn.id })
})

app.post('/conversations/:id/abort', (req, res) => {
  const conversation = store.get(req.params.id)
  if (!conversation) {
    res.status(404).json({ error: '会话不存在' })
    return
  }
  agentRuntime.abort(conversation.id)
  res.json({ aborted: true })
})

app.get('/conversations/:id/events', (req, res) => {
  const conversation = store.get(req.params.id)
  if (!conversation) {
    res.status(404).json({ error: '会话不存在' })
    return
  }
  const after = Number(req.query.after ?? 0)
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
  res.write('retry: 3000\n\n')

  const writeEvent = (event: { seq: number; type: string; turnId?: string; payload: unknown }) => {
    res.write(`id: ${event.seq}\n`)
    res.write(`event: ${event.type}\n`)
    res.write(`data: ${JSON.stringify({ ...event, ...((event.payload as object) ?? {}) })}\n\n`)
  }

  for (const event of conversation.events) {
    if (event.seq > after) {
      writeEvent(event)
    }
  }

  const listener = (event: { seq: number; type: string; turnId?: string; payload: unknown }) => {
    writeEvent(event)
  }
  bus.on(conversation.id, listener)
  const keepAlive = setInterval(() => res.write(': keep-alive\n\n'), 15_000)
  req.on('close', () => {
    clearInterval(keepAlive)
    bus.off(conversation.id, listener)
  })
})

app.listen(config.port, '127.0.0.1', () => {
  console.log(`[dg-insight-agent] listening on http://127.0.0.1:${config.port}`)
  console.log(`[dg-insight-agent] backend=${config.backendUrl} model=${config.deepseekModel}`)
})

function optionalNumber(value: unknown): number | undefined {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : undefined
}

function summary(conversation: Conversation) {
  return {
    id: conversation.id,
    workspaceId: conversation.workspaceId,
    username: conversation.username,
    title: conversation.title,
    datasetId: conversation.datasetId,
    datasetLabel: conversation.datasetLabel,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
    running: conversation.running,
    turnCount: conversation.turns.length,
  }
}

function detail(conversation: Conversation) {
  return {
    ...summary(conversation),
    turns: conversation.turns,
    events: conversation.events,
  }
}
