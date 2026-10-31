# dg-insight-agent

数据治理平台的 **Pi agent 对话服务**。独立 Node 进程，直接嵌 `@earendil-works/pi-agent-core`，
通过 HTTP 工具调用复用平台治理能力。

## 架构

```text
Browser
  → Java dg-app /api/v1/insight/agent/*      鉴权、workspace、会话归属校验
      → agent-service :8090                   Pi agent / SSE / 会话持久化
          → Java /api/v1/internal/agent/*     工具端点（服务令牌）
              → InsightQueryService → SuperSonic NL2SQL / 敏感脱敏 / 审计
```

- **Agent 服务不持有平台 JWT，也不直连数据库**；
- 工具端点由 Java 侧校验 `X-Agent-Token`、注入 `WorkspaceContext`，复用平台问数、脱敏、审计；
- 会话存储当前是每个会话一个 JSON 文件（`data/agent/`），多实例部署时替换 `ConversationStore`。

## 运行

```bash
cd agent-service
npm install
DG_BACKEND_URL=http://127.0.0.1:18080 \
DG_AGENT_TOKEN=dev-agent-token \
DEEPSEEK_MODEL=deepseek-flash \
npm start
```

Java 侧需要同一令牌：

```bash
DG_AGENT_TOKEN=dev-agent-token
```

可选环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_PORT` | `8090` | 服务端口，只监听 127.0.0.1 |
| `DG_BACKEND_URL` | `http://127.0.0.1:18080` | Java 后端 |
| `DG_AGENT_TOKEN` | `dev-agent-token` | 与 Java `dg.insight.agent-token` 一致 |
| `DEEPSEEK_MODEL` | `deepseek-flash` | Pi agent 模型 |
| `AGENT_DATA_DIR` | `./data/agent` | 会话文件目录 |

依赖 `DEEPSEEK_API_KEY`（pi-ai 的 deepseek provider 会从环境读取）。

## 端点

内部（只给 Java 调）：

```text
GET  /health
GET  /conversations?workspaceId=&username=
POST /conversations
GET  /conversations/:id
POST /conversations/:id/messages
GET  /conversations/:id/events?after=   SSE
POST /conversations/:id/abort
```

Java 暴露给前端的同构路径是 `/api/v1/insight/agent/*`，并校验会话属于当前用户/空间。

## 工具

- `list_datasets`：列出当前空间的数据集/主题域（Java → SuperSonic 数据集树）
- `ask_data`：对当前数据集做自然语言查询（Java → SuperSonic NL2SQL，结果经平台脱敏/审计）

## 已知边界

- 会话存储是文件，不是共享数据库；多副本部署前需要替换为 PostgreSQL/Redis 等共享存储。
- SSE 只做单进程事件广播；跨副本需要消息总线。
- Agent 的工具端点只暴露只读问数/目录能力，未开放写入、SQL 控制台或外部系统。
