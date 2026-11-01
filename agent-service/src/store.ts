import { mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { config } from './config.js'
import type { Conversation, StoredEvent, UiTurn } from './types.js'

/**
 * File-backed conversation store.
 *
 * MVP 故意不引入 SQLite/Postgres：Agent 服务是独立进程，先把会话事实落成
 * 每个会话一个 JSON 文件，方便本地起停和排障。多实例部署时需要换成共享存储，
 * 这个边界集中在 ConversationStore 里，替换时不影响 Agent 逻辑。
 */
export class ConversationStore {
  private readonly cache = new Map<string, Conversation>()

  constructor(private readonly dataDir = config.dataDir) {
    mkdirSync(this.dataDir, { recursive: true })
  }

  create(input: {
    workspaceId: string
    userId?: string
    platformAdmin?: boolean
    username: string
    title?: string
    datasetId?: number
    datasetLabel?: string
  }): Conversation {
    const now = new Date().toISOString()
    const conversation: Conversation = {
      id: `conv_${randomUUID().replaceAll('-', '').slice(0, 24)}`,
      workspaceId: input.workspaceId,
      userId: input.userId,
      platformAdmin: input.platformAdmin,
      username: input.username,
      title: input.title?.trim() || '新对话',
      datasetId: input.datasetId,
      datasetLabel: input.datasetLabel,
      createdAt: now,
      updatedAt: now,
      messages: [],
      turns: [],
      events: [],
      seq: 0,
      running: false,
    }
    this.cache.set(conversation.id, conversation)
    this.save(conversation)
    return conversation
  }

  get(id: string): Conversation | undefined {
    const cached = this.cache.get(id)
    if (cached) {
      return cached
    }
    try {
      const parsed = JSON.parse(readFileSync(this.file(id), 'utf8')) as Conversation
      parsed.messages ??= []
      parsed.turns ??= []
      parsed.events ??= []
      parsed.seq ??= parsed.events.length
      // 进程重启后没有正在运行的 run；不要从磁盘继承 true。
      parsed.running = false
      this.cache.set(parsed.id, parsed)
      return parsed
    } catch {
      return undefined
    }
  }

  list(workspaceId: string, username: string): Conversation[] {
    let files: string[] = []
    try {
      files = readdirSync(this.dataDir).filter((name) => name.endsWith('.json'))
    } catch {
      return []
    }
    const conversations: Conversation[] = []
    for (const file of files) {
      try {
        const parsed = JSON.parse(readFileSync(join(this.dataDir, file), 'utf8')) as Conversation
        if (parsed.workspaceId === workspaceId && parsed.username === username) {
          conversations.push({ ...parsed, running: false })
        }
      } catch {
        // 单个会话文件损坏不影响列表；真正的错误会在 get 时暴露。
      }
    }
    return conversations.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
  }

  save(conversation: Conversation) {
    conversation.updatedAt = new Date().toISOString()
    const target = this.file(conversation.id)
    const temp = `${target}.tmp`
    writeFileSync(temp, JSON.stringify(conversation, null, 2), 'utf8')
    renameSync(temp, target)
  }

  appendEvent(
    conversation: Conversation,
    type: string,
    turnId: string | undefined,
    payload: Record<string, unknown>,
    persist = true,
  ): StoredEvent {
    conversation.seq += 1
    const event: StoredEvent = {
      seq: conversation.seq,
      type,
      turnId,
      payload,
    }
    conversation.events.push(event)
    // 每个会话保留最近 4000 条，防止长时间对话把文件撑爆。
    if (conversation.events.length > 4000) {
      conversation.events.splice(0, conversation.events.length - 4000)
    }
    if (persist) {
      this.save(conversation)
    }
    return event
  }

  updateTurn(conversation: Conversation, turnId: string, patch: Partial<UiTurn>) {
    const turn = conversation.turns.find((item) => item.id === turnId)
    if (turn) {
      Object.assign(turn, patch)
    }
  }

  private file(id: string): string {
    return join(this.dataDir, `${id}.json`)
  }
}
