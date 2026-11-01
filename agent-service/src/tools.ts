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

export interface NavigateTarget {
  route: string
  label: string
  query?: Record<string, string>
}

const NAVIGABLE_ROUTES = new Set([
  '/metadata/datasources',
  '/metadata/catalog',
  '/insight/ask',
])

async function callBackend<T>(context: RunContext, path: string,
                              init: RequestInit = {}): Promise<T> {
  const response = await fetch(config.backendUrl + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Agent-Token': config.agentToken,
      // 工具端点的调用者不是 Node 服务，而是发起这次对话的真实用户。
      // 这些头由 Java 代理注入 userId 后随会话带下来，Java 侧再按 userId 重新解析权限。
      'X-Agent-User-Id': context.userId ?? '',
      'X-Agent-Username': context.username,
      'X-Agent-Workspace-Id': context.workspaceId,
      'X-Agent-Admin': String(Boolean(context.platformAdmin)),
      ...(init.headers ?? {}),
    },
    signal: AbortSignal.timeout(180_000),
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

function toolResult(summary: string, data: Record<string, unknown> = {}) {
  return {
    content: text({ summary, ...data }),
    details: { summary, ...data },
  }
}

function toolError(message: string) {
  return {
    content: text(message),
    details: { error: message },
    isError: true,
  }
}

/**
 * Agent 工具只调用平台内部工具端点，不直接碰数据库/业务数据源。
 * 权限、脱敏、审计和 workspace 校验都在 Java 侧完成。
 *
 * `onNavigate` 不是后端调用：它把“该看哪个页面”作为 SSE 事件推给前端，
 * 由前端路由切换。工具本身仍然只做确定性动作。
 */
export function buildTools(context: RunContext,
                           onNavigate?: (target: NavigateTarget) => void): AgentTool<any>[] {
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
          context,
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
        return toolError(`列出数据集失败：${messageOf(error)}`)
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
        const answer = await callBackend<any>(context, '/api/v1/internal/agent/ask', {
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
        return toolError(`数据查询失败：${messageOf(error)}`)
      }
    },
  }

  const createLocalDataSource: AgentTool<any> = {
    name: 'create_local_datasource',
    label: '创建本地数据源',
    description: '创建一个 LOCAL 类型的数据源，指向后端机器上的本地/NFS Parquet 目录。'
      + '只在用户明确给出目录路径和名称后调用；不要猜测路径。',
    parameters: Type.Object({
      name: Type.String({ description: '数据源名称，空间内唯一' }),
      rootPath: Type.String({ description: '后端进程能访问的绝对目录，例如 /data/lakehouse 或 /mnt/lake' }),
      description: Type.Optional(Type.String({ description: '可选描述' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { name: string; rootPath: string; description?: string }
      try {
        const datasource = await callBackend<any>(context,
          '/api/v1/internal/agent/onboarding/local-datasource', {
            method: 'POST',
            body: JSON.stringify(params),
          })
        return toolResult(`已创建本地数据源「${datasource.name}」`, {
          datasource: {
            id: datasource.id,
            name: datasource.name,
            type: datasource.type,
            status: datasource.status,
            rootPath: datasource.databaseName,
          },
        })
      } catch (error) {
        return toolError(`创建本地数据源失败：${messageOf(error)}`)
      }
    },
  }

  const updateLocalDataSource: AgentTool<any> = {
    name: 'update_local_datasource',
    label: '修改本地数据源',
    description: '修改已创建的 LOCAL 数据源的名称、根目录或描述，用于测试失败后修正路径。',
    parameters: Type.Object({
      dataSourceId: Type.String({ description: '数据源 ID' }),
      name: Type.Optional(Type.String({ description: '新名称；不填保持原值' })),
      rootPath: Type.Optional(Type.String({ description: '新的绝对目录；不填保持原值' })),
      description: Type.Optional(Type.String({ description: '新描述' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as {
        dataSourceId: string
        name?: string
        rootPath?: string
        description?: string
      }
      try {
        const datasource = await callBackend<any>(context,
          `/api/v1/internal/agent/onboarding/local-datasource/${encodeURIComponent(params.dataSourceId)}`, {
            method: 'PUT',
            body: JSON.stringify({
              name: params.name,
              rootPath: params.rootPath,
              description: params.description,
            }),
          })
        return toolResult(`已更新本地数据源「${datasource.name}」`, {
          datasource: {
            id: datasource.id,
            name: datasource.name,
            status: datasource.status,
            rootPath: datasource.databaseName,
          },
        })
      } catch (error) {
        return toolError(`修改本地数据源失败：${messageOf(error)}`)
      }
    },
  }

  const testDataSource: AgentTool<any> = {
    name: 'test_datasource',
    label: '测试数据源连接',
    description: '对数据源执行连通性测试。LOCAL 类型会检查目录是否存在且是目录；成功后才进入可用状态。',
    parameters: Type.Object({
      dataSourceId: Type.String({ description: '数据源 ID' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { dataSourceId: string }
      try {
        const result = await callBackend<any>(context,
          `/api/v1/internal/agent/onboarding/local-datasource/${encodeURIComponent(params.dataSourceId)}/test`, {
            method: 'POST',
          })
        return {
          content: text({
            success: result.success,
            message: result.message,
            latencyMillis: result.latencyMillis,
            serverVersion: result.serverVersion,
          }),
          details: {
            summary: result.success
              ? `连接成功（${result.latencyMillis} ms）`
              : `连接失败：${result.message}`,
            result,
          },
          isError: !result.success,
        }
      } catch (error) {
        return toolError(`测试数据源连接失败：${messageOf(error)}`)
      }
    },
  }

  const listCatalogSources: AgentTool<any> = {
    name: 'list_catalog_sources',
    label: '列出目录来源',
    description: '列出当前空间已有的数据目录来源，用于判断名称是否重复或复用已有来源。',
    parameters: Type.Object({}),
    execute: async () => {
      try {
        const sources = await callBackend<any[]>(context,
          '/api/v1/internal/agent/onboarding/catalog-sources')
        return toolResult(`返回 ${sources.length} 个目录来源`, {
          sources: sources.map((source) => ({
            id: source.id,
            name: source.name,
            sourceType: source.sourceType,
            dataSourceId: source.dataSourceId,
            lastRefreshedAt: source.lastRefreshedAt,
          })),
        })
      } catch (error) {
        return toolError(`列出目录来源失败：${messageOf(error)}`)
      }
    },
  }

  const createParquetCatalog: AgentTool<any> = {
    name: 'create_parquet_catalog_source',
    label: '创建 Parquet 目录来源',
    description: '为 LOCAL/S3/MinIO/OSS 数据源创建 PARQUET_DIRECTORY 目录来源。'
      + 'rootPath 可留空，表示直接使用数据源根目录。',
    parameters: Type.Object({
      name: Type.String({ description: '目录来源名称，空间内唯一' }),
      dataSourceId: Type.String({ description: 'LOCAL 数据源 ID' }),
      rootPath: Type.Optional(Type.String({ description: '可选子目录；留空使用数据源根目录' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { name: string; dataSourceId: string; rootPath?: string }
      try {
        const source = await callBackend<any>(context,
          '/api/v1/internal/agent/onboarding/parquet-catalog', {
            method: 'POST',
            body: JSON.stringify(params),
          })
        return toolResult(`已创建目录来源「${source.name}」`, {
          source: {
            id: source.id,
            name: source.name,
            sourceType: source.sourceType,
            dataSourceId: source.dataSourceId,
            configJson: source.configJson,
          },
        })
      } catch (error) {
        return toolError(`创建目录来源失败：${messageOf(error)}`)
      }
    },
  }

  const refreshCatalogSource: AgentTool<any> = {
    name: 'refresh_catalog_source',
    label: '采集目录',
    description: '对 Parquet 目录来源执行一次采集，读取 Parquet footer/schema/分区并写入平台元数据。',
    parameters: Type.Object({
      sourceId: Type.String({ description: '目录来源 ID' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { sourceId: string }
      try {
        const result = await callBackend<any>(context,
          `/api/v1/internal/agent/onboarding/parquet-catalog/${encodeURIComponent(params.sourceId)}/refresh`, {
            method: 'POST',
          })
        return toolResult(
          `采集完成：${result.datasets} 个数据集 / ${result.columns} 个字段`,
          {
            result: {
              runId: result.runId,
              status: result.status,
              namespaces: result.namespaces,
              datasets: result.datasets,
              columns: result.columns,
              durationMillis: result.durationMillis,
              warnings: result.warnings ?? [],
            },
          },
        )
      } catch (error) {
        return toolError(`采集目录失败：${messageOf(error)}`)
      }
    },
  }

  const navigateWorkspace: AgentTool<any> = {
    name: 'navigate_workspace',
    label: '切换工作页面',
    description: '把用户的工作页面切换到指定路由，用于让用户看到数据源/数据目录等真实页面。'
      + '只允许 /metadata/datasources、/metadata/catalog、/insight/ask。',
    parameters: Type.Object({
      route: Type.String({ description: '目标路由' }),
      label: Type.String({ description: '给用户看的页面名称，例如“数据源”或“数据目录”' }),
      query: Type.Optional(Type.Object({
        sourceId: Type.Optional(Type.String({ description: '数据目录来源 ID，用于自动选中来源' })),
        highlight: Type.Optional(Type.String({ description: '数据源 ID，用于高亮刚创建的数据源' })),
      })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as {
        route: string
        label: string
        query?: { sourceId?: string; highlight?: string }
      }
      if (!NAVIGABLE_ROUTES.has(params.route)) {
        return toolError(`不允许切换到路由：${params.route}`)
      }
      const query: Record<string, string> = {}
      if (params.query?.sourceId) {
        query.sourceId = params.query.sourceId
      }
      if (params.query?.highlight) {
        query.highlight = params.query.highlight
      }
      onNavigate?.({ route: params.route, label: params.label, query })
      return toolResult(`已切换到${params.label}`, {
        navigated: true,
        route: params.route,
        label: params.label,
        query,
      })
    },
  }

  return [
    listDatasets,
    askData,
    createLocalDataSource,
    updateLocalDataSource,
    testDataSource,
    listCatalogSources,
    createParquetCatalog,
    refreshCatalogSource,
    navigateWorkspace,
  ]
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
