import { Agent } from '@earendil-works/pi-agent-core'
import type { AgentEvent } from '@earendil-works/pi-agent-core'
import { createModels } from '@earendil-works/pi-ai'
import { deepseekProvider } from '@earendil-works/pi-ai/providers/deepseek'
import type { EventEmitter } from 'node:events'
import { config } from './config.js'
import { ConversationStore } from './store.js'
import { buildTools } from './tools.js'
import type { Conversation, RunContext, UiToolStep, UiTurn } from './types.js'

const models = createModels()
models.setProvider(deepseekProvider())

const activeRuns = new Map<string, Agent>()

export const agentRuntime = {
  modelId: config.deepseekModel,
  abort(conversationId: string) {
    activeRuns.get(conversationId)?.abort()
  },
}

interface Bus {
  emit: EventEmitter['emit']
}

export async function runTurn(
  conversation: Conversation,
  turn: UiTurn,
  text: string,
  context: RunContext,
  store: ConversationStore,
  bus: Bus,
) {
  const model = models.getModel('deepseek', config.deepseekModel)
  if (!model) {
    throw new Error(`Pi agent 模型不存在: deepseek/${config.deepseekModel}`)
  }

  let finished = false
  const emit = (type: string, payload: Record<string, unknown>, persist = true) => {
    const event = store.appendEvent(conversation, type, turn.id, payload, persist)
    bus.emit(conversation.id, event)
    return event
  }

  const tools = buildTools(context, (target) => {
    emit('navigate', {
      route: target.route,
      label: target.label,
      query: target.query ?? {},
    })
  })
  const agent = new Agent({
    initialState: {
      systemPrompt: systemPrompt(context),
      model,
      tools,
      messages: conversation.messages as any[],
    },
    streamFn: models.streamSimple.bind(models),
    sessionId: conversation.id,
  })
  activeRuns.set(conversation.id, agent)

  agent.subscribe((event: AgentEvent) => {
    switch (event.type) {
      case 'agent_start':
        emit('turn_start', { question: turn.question })
        break
      case 'message_update': {
        const update = event.assistantMessageEvent
        if (update.type === 'text_delta') {
          turn.text += update.delta
          emit('text_delta', { delta: update.delta }, false)
        } else if (update.type === 'thinking_delta') {
          turn.thinking += update.delta
          emit('thinking_delta', { delta: update.delta }, false)
        }
        break
      }
      case 'message_end': {
        if (event.message.role === 'assistant' && !turn.text) {
          turn.text = contentText(event.message.content)
        }
        break
      }
      case 'tool_execution_start': {
        const step: UiToolStep = {
          id: event.toolCallId,
          name: event.toolName,
          status: 'running',
          args: event.args,
          startedAt: new Date().toISOString(),
        }
        turn.tools.push(step)
        emit('tool_start', { toolCallId: event.toolCallId, name: event.toolName, args: event.args })
        break
      }
      case 'tool_execution_end': {
        const step = turn.tools.find((item) => item.id === event.toolCallId)
        if (step) {
          step.status = event.isError ? 'error' : 'ok'
          step.finishedAt = new Date().toISOString()
          step.summary = toolSummary(event.result)
        }
        const details = event.result?.details as { answer?: unknown; datasets?: any[] } | undefined
        if (event.toolName === 'ask_data' && details?.answer) {
          turn.answer = details.answer
        }
        if (event.toolName === 'list_datasets' && Array.isArray(details?.datasets)) {
          const datasets = details.datasets
          turn.answer = {
            state: 'METADATA',
            columns: [
              { name: '数据域', bizName: 'domain', type: 'STRING' },
              { name: '数据集', bizName: 'dataset', type: 'STRING' },
              { name: '数据集 ID', bizName: 'datasetId', type: 'LONG' },
            ],
            rows: datasets.map((item: any) => ({
              domain: item.domain ?? '',
              dataset: item.name ?? '',
              datasetId: item.id,
            })),
            rowCount: datasets.length,
            truncated: false,
            elapsedMs: 0,
            maskedColumns: [],
            grantIds: [],
            summary: `返回 ${datasets.length} 个数据集`,
            sql: null,
          }
        }
        emit('tool_end', {
          toolCallId: event.toolCallId,
          name: event.toolName,
          isError: event.isError,
          summary: step?.summary,
          answer: turn.answer,
        })
        break
      }
      case 'agent_end': {
        finished = true
        const last = [...event.messages].reverse()
          .find((message) => message.role === 'assistant') as any
        if (last && !turn.text) {
          turn.text = contentText(last.content)
        }
        const stopReason = last?.stopReason
        turn.status = stopReason === 'aborted' ? 'aborted'
          : stopReason === 'error' ? 'error' : 'done'
        turn.finishedAt = new Date().toISOString()
        if (turn.status === 'error') {
          turn.error = last?.errorMessage || 'Agent 运行失败'
        }
        conversation.running = false
        conversation.messages = filterMessages(agent.state.messages)
        store.save(conversation)
        emit('done', {
          status: turn.status,
          text: turn.text,
          answer: turn.answer,
          error: turn.error,
        })
        break
      }
      default:
        break
    }
  })

  conversation.running = true
  if (conversation.title === '新对话') {
    conversation.title = text.length > 40 ? `${text.slice(0, 40)}…` : text
  }
  store.save(conversation)

  try {
    await agent.prompt(text)
  } catch (error) {
    turn.status = 'error'
    turn.error = error instanceof Error ? error.message : String(error)
    turn.finishedAt = new Date().toISOString()
    conversation.running = false
    store.save(conversation)
    if (!finished) {
      emit('error', { message: turn.error })
      emit('done', { status: 'error', text: turn.text, error: turn.error })
    }
  } finally {
    activeRuns.delete(conversation.id)
    if (conversation.running) {
      conversation.running = false
      store.save(conversation)
    }
  }
}

function systemPrompt(context: RunContext): string {
  return [
    '你是数据治理平台的问数 Agent，帮助用户理解数据资产并完成数据分析。',
    `当前空间: ${context.workspaceId}；当前用户: ${context.username}。`,
    context.datasetId
      ? `当前选中的数据集: ${context.datasetLabel ?? context.datasetId}（ID: ${context.datasetId}）。`
      : '当前没有明确选中的数据集。',
    '',
    '规则：',
    '1. 用户问“有哪些数据集/表/数据域”时，必须调用 list_datasets，不要凭记忆编造。问“这个数据域”时把当前数据集标签中“/”前的部分作为 domain 传入。',
    '2. 用户问统计、趋势、排名、金额、数量等数据问题时，必须调用 ask_data；把用户问题改写成完整明确的中文问题。',
    '3. 只能使用工具返回的事实。工具失败时明确说明失败原因，不要编造结果。',
    '4. 回答使用中文，先给结论，再给必要的要点；不要输出 Markdown 表格、原始行数据或工具调用参数。结构化结果由前端表格展示。',
    '5. 如果用户的问题与当前数据集无关，先说明当前数据集范围，再决定是否调用工具。',
    '',
    '本地/NFS Parquet 数据源接入流程（用户提出这类需求时严格执行，不要跳步）：',
    '6. 先确认两件事：数据源名称、后端机器上的绝对目录（rootPath）。缺少任何一项就先问用户，不要猜路径；提醒用户 rootPath 是后端进程所在机器的路径，不是浏览器所在电脑的路径。',
    '7. 调用 create_local_datasource 创建 LOCAL 数据源。',
    '8. 调用 test_datasource；失败时解释失败原因，必要时调用 update_local_datasource 修正 rootPath 后重试。测试不通过就不要继续建目录来源。',
    '9. 测试成功后，调用 navigate_workspace 打开 /metadata/datasources，query.highlight 填数据源 ID，让用户看到刚建的数据源。',
    '10. 调用 create_parquet_catalog_source 创建 PARQUET_DIRECTORY 目录来源；名称默认用“数据源名-catalog”，rootPath 留空表示使用数据源根目录。若提示重名，先 list_catalog_sources 查看，再换一个名称。',
    '11. 调用 refresh_catalog_source 采集目录。',
    '12. 调用 navigate_workspace 打开 /metadata/catalog，query.sourceId 填目录来源 ID，让页面自动选中并展示刚采集的数据集。',
    '13. 最后汇总数据集/字段数量，并说明：这里只采集元数据、不复制数据；要查询这批 Parquet，需要先接入 StarRocks/Doris/Trino/Spark 等查询引擎，再在平台建对应数据集。',
    '14. 所有面向用户的文字（包括工具调用之间的过渡句、最终回答）必须使用中文，不要输出英文过程句。',
  ].join('\n')
}

function filterMessages(messages: any[]): unknown[] {
  return messages.filter((message) => message?.role !== 'system')
}

function contentText(content: any): string {
  if (!Array.isArray(content)) {
    return ''
  }
  return content
    .filter((block) => block?.type === 'text')
    .map((block) => block.text ?? '')
    .join('')
}

function toolSummary(result: any): string {
  if (result?.details?.summary) {
    return String(result.details.summary)
  }
  if (result?.details?.error) {
    return String(result.details.error)
  }
  if (result?.details?.answer?.summary) {
    return String(result.details.answer.summary)
  }
  if (Array.isArray(result?.details?.datasets)) {
    return `返回 ${result.details.datasets.length} 个数据集`
  }
  return result?.isError ? '工具执行失败' : '工具执行完成'
}
