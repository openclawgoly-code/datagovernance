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

问数：

- `list_datasets`：列出当前空间的数据集/主题域（Java → SuperSonic 数据集树）
- `ask_data`：对当前数据集做自然语言查询（Java → SuperSonic NL2SQL，结果经平台脱敏/审计）

本地/NFS Parquet 接入：

- `create_local_datasource`：创建 LOCAL 数据源
- `update_local_datasource`：修正名称/根目录/描述
- `test_datasource`：测试连通性（目录必须存在且是目录）
- `list_catalog_sources`：列出目录来源，避免重名
- `create_parquet_catalog_source`：创建 PARQUET_DIRECTORY 目录来源
- `refresh_catalog_source`：采集 Parquet footer/schema/分区
- `navigate_workspace`：发 SSE `navigate` 事件，让前端切到数据源/数据目录页面

写操作不是共享的 Agent 超级账号：Java 工具端点会按会话发起人的 `userId`
重新解析平台权限，用户没有 `metadata:datasource:create` / `metadata:asset:manage`
等权限时调用会直接被拒绝。

## 引导式工作流

用户说“帮我接入本地 Parquet 数据源”时，Agent 会按固定顺序执行：

1. 收集名称与后端绝对路径；
2. 创建 LOCAL 数据源；
3. 测试连接（失败先修正，不继续）；
4. 切到「数据源」页面并高亮新数据源；
5. 创建 PARQUET_DIRECTORY 目录来源；
6. 采集；
7. 切到「数据目录」页面并自动选中新来源；
8. 汇总数据集/字段数量，并说明只采集元数据、不提供 SQL 查询。

AskView 在 KeepAlive 中保持 SSE 不断；导航到其他页面后，右下角的
`AgentDock` 会继续显示当前步骤、工具状态和最终结果。

## 已知边界

- 会话存储是文件，不是共享数据库；多副本部署前需要替换为 PostgreSQL/Redis 等共享存储。
- SSE 只做单进程事件广播；跨副本需要消息总线。
- 写工具只覆盖本地/NFS Parquet 接入；未开放任意 SQL 控制台、删除数据源或外部系统写入。
- `rootPath` 目前只校验绝对路径且非根目录；生产应再叠加部署级目录白名单。
