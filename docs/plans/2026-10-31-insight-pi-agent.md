# 智能问数 Pi Agent（方案 B）

- 日期：2026-10-31
- 状态：已实现（独立 Node sidecar + Java 工具代理）
- 关联：`/insight/ask`、`Insight Query Gateway`

## 1. 目标

把 `/insight/ask` 从“单轮 SuperSonic NL2SQL”升级为 validation 项目同类的对话 Agent：

- 多轮会话、事件流；
- 工具调用过程可见；
- 对话基座使用 `@earendil-works/pi-agent-core`（Pi agent），不引入 OpenClaw 网关；
- 平台治理不下沉：workspace、权限、脱敏、审计仍由 Java 后端负责。

## 2. 架构

```text
Browser
  → Java /api/v1/insight/agent/*          鉴权 / workspace / 会话归属
      → agent-service :8090              Pi agent / SSE / JSON 会话
          → Java /api/v1/internal/agent/* 服务令牌工具端点
              → InsightQueryService        SuperSonic NL2SQL / 脱敏 / 审计
```

- `agent-service` 只监听 `127.0.0.1`，不持有平台 JWT；
- 内部工具端点由 `dg.insight.agent-token` 保护；
- 工具分两类：问数只读能力（`list_datasets` / `ask_data`），以及本地/NFS Parquet 接入能力；
- 写工具按会话发起人的真实 `userId` 重新解析权限，不使用共享的 Agent 超级账号；
- 会话存储当前为文件（`agent-service/data/agent/`），多副本前替换为共享存储。

## 3. 实施内容

- `agent-service/`：Pi agent、SSE、会话存储、工具定义、健康检查；
- Java `AgentProxyController`：鉴权、注入 workspace/user、SSE 原样转发；
- Java `AgentToolController`：服务令牌校验、`WorkspaceContext` 还原、工具端点；
- 前端 `/insight/ask`：左侧会话列表 + 右侧多轮对话 + 工具步骤 + 结果表/图表；
- `EventSource` 改为 `fetch` 流读取，因为 EventSource 不能携带 Authorization 头。

## 4. 引导式本地/NFS 接入

新增一条 Agent 工作流，用户只需要说“帮我接入本地 Parquet 数据源”：

1. Agent 询问名称与后端绝对路径；
2. `create_local_datasource` 创建 LOCAL 数据源；
3. `test_datasource` 测试连接，失败时 `update_local_datasource` 修正后重试；
4. `navigate_workspace` 切到 `/metadata/datasources?highlight=<id>`，高亮新数据源；
5. `create_parquet_catalog_source` 创建 PARQUET_DIRECTORY 来源；
6. `refresh_catalog_source` 采集；
7. `navigate_workspace` 切到 `/metadata/catalog?sourceId=<id>`，自动选中新来源；
8. Agent 汇总数据集/字段数量，并说明只采集元数据、不提供 SQL 查询。

页面切换由 SSE `navigate` 事件驱动。`AskView` 在 KeepAlive 中保持存活，
SSE 不因切页断开；`AgentDock` 在业务页面右下角持续显示当前步骤、工具状态和结果，
用户也可以关闭“自动切换页面”，改为手动打开 Agent 指定的页面。

Java 侧新增内部端点：

```text
POST /api/v1/internal/agent/onboarding/local-datasource
PUT  /api/v1/internal/agent/onboarding/local-datasource/{id}
POST /api/v1/internal/agent/onboarding/local-datasource/{id}/test
GET  /api/v1/internal/agent/onboarding/catalog-sources
POST /api/v1/internal/agent/onboarding/parquet-catalog
POST /api/v1/internal/agent/onboarding/parquet-catalog/{id}/refresh
```

这些端点不信任 Node 传来的权限，只把它当作“发起人是谁”，再按 `userId` +
`workspaceId` 调 `WorkspaceService.requireAccess` 和 `PermissionService.resolvePermissions`。

## 5. 验证

- `agent-service`：`npx tsc --noEmit` 通过；
- Java：`mvn -pl backend/dg-app -am test` 通过（552 项）；
- 前端：`pnpm type-check`、`pnpm build` 通过；
- 真实链路实测：
  - 创建会话 → `list_datasets` 工具 → “这个数据域有哪些表”返回 20 个数据集；
  - 创建会话 → `ask_data` 工具 → “按状态统计订单数”返回 3 行结果；
  - SSE 事件流经 Java 代理到达浏览器；
  - 工具调用过程在页面显示为 `list_datasets` / `ask_data` 步骤。
- 引导式接入实测：
  - “帮我接入本地 Parquet 数据源” → 创建 LOCAL → 测试连接 → 切到数据源页高亮 → 创建 PARQUET_DIRECTORY → 采集 30 个数据集/516 个字段 → 切到数据目录页并自动选中；
  - 浏览器地址依次变为 `/metadata/datasources?highlight=...`、`/metadata/catalog?sourceId=...`；
  - 切页后右下角 `AgentDock` 持续显示“Agent 正在执行/已完成”；
  - 浏览器 console errors：0。
