import { Type } from '@earendil-works/pi-ai'
import type { AgentTool } from '@earendil-works/pi-agent-core'
import { config } from './config.js'
import type { RunContext } from './types.js'

interface ApiEnvelope<T> {
  success: boolean
  code?: string
  message?: string
  data?: T
}

async function callBackend<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(config.backendUrl + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Agent-Token': config.agentToken,
      ...(init.headers ?? {}),
    },
    signal: AbortSignal.timeout(120_000),
  })
  const body = (await response.json()) as ApiEnvelope<T>
  if (!response.ok || !body.success) {
    throw new Error(body.message || `平台接口返回 ${response.status}`)
  }
  return body.data as T
}

function text(value: unknown) {
  return [{ type: 'text' as const, text: typeof value === 'string' ? value : JSON.stringify(value) }]
}

/**
 * Agent 工具只调用平台内部工具端点，不直接碰数据库/业务数据源。
 * 权限、脱敏、审计和 workspace 校验都在 Java 侧完成。
 */
export function buildTools(context: RunContext): AgentTool<any>[] {
  const listDatasets: AgentTool<any> = {
    name: 'list_datasets',
    label: '列出数据集',
    description: '列出当前空间可用的数据集/主题域。回答“有哪些表/数据集/数据域”时使用。',
    parameters: Type.Object({
      keyword: Type.Optional(Type.String({ description: '可选关键词，模糊过滤数据集名称或数据域' })),
      domain: Type.Optional(Type.String({ description: '可选精确数据域名；问“这个数据域”时传入' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { keyword?: string; domain?: string }
      try {
        const query = new URLSearchParams({
          workspaceId: context.workspaceId,
          username: context.username,
        })
        if (params.keyword?.trim()) {
          query.set('keyword', params.keyword.trim())
        }
        const datasets = await callBackend<Array<{ id: number; name: string; domain: string }>>(
          `/api/v1/internal/agent/datasets?${query.toString()}`,
        )
        let filtered = datasets
        if (params.domain?.trim()) {
          filtered = datasets.filter((item) => item.domain === params.domain!.trim())
        } else if (params.keyword?.trim()) {
          filtered = datasets.filter((item) =>
            `${item.domain} ${item.name}`.toLowerCase().includes(params.keyword!.trim().toLowerCase()))
        }
        return {
          content: text({ count: filtered.length, datasets: filtered }),
          details: { datasets: filtered },
        }
      } catch (error) {
        return {
          content: text(`列出数据集失败：${messageOf(error)}`),
          details: { error: messageOf(error) },
          isError: true,
        }
      }
    },
  }

  const askData: AgentTool<any> = {
    name: 'ask_data',
    label: '数据查询',
    description: '对当前数据集发起自然语言数据查询（SuperSonic NL2SQL）。回答统计、趋势、排名、金额等问题时使用。',
    parameters: Type.Object({
      question: Type.String({ description: '完整、明确的数据问题' }),
      dataSetId: Type.Optional(Type.Number({ description: '可选数据集 ID；不填使用当前选中的数据集' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { question: string; dataSetId?: number }
      try {
        const payload = {
          workspaceId: context.workspaceId,
          username: context.username,
          question: params.question,
          dataSetId: params.dataSetId ?? context.datasetId,
        }
        const answer = await callBackend<any>('/api/v1/internal/agent/ask', {
          method: 'POST',
          body: JSON.stringify(payload),
        })
        const rows = Array.isArray(answer?.rows) ? answer.rows.slice(0, 20) : []
        return {
          content: text({
            summary: answer?.summary ?? '查询完成',
            rowCount: answer?.rowCount ?? rows.length,
            columns: answer?.columns ?? [],
            rows,
          }),
          details: { answer },
        }
      } catch (error) {
        return {
          content: text(`数据查询失败：${messageOf(error)}`),
          details: { error: messageOf(error) },
          isError: true,
        }
      }
    },
  }

  return [listDatasets, askData]
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
