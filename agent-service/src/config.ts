import { join } from 'node:path'

export const config = {
  port: Number(process.env.AGENT_PORT ?? 8090),
  backendUrl: (process.env.DG_BACKEND_URL ?? 'http://127.0.0.1:18080').replace(/\/+$/, ''),
  agentToken: process.env.DG_AGENT_TOKEN ?? 'dev-agent-token',
  deepseekModel: process.env.DEEPSEEK_MODEL ?? 'deepseek-flash',
  dataDir: process.env.AGENT_DATA_DIR ?? join(process.cwd(), 'data', 'agent'),
}
