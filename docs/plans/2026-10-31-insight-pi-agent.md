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
- 工具只暴露 `list_datasets`、`ask_data` 两类只读能力；
- 会话存储当前为文件（`agent-service/data/agent/`），多副本前替换为共享存储。

## 3. 实施内容

- `agent-service/`：Pi agent、SSE、会话存储、工具定义、健康检查；
- Java `AgentProxyController`：鉴权、注入 workspace/user、SSE 原样转发；
- Java `AgentToolController`：服务令牌校验、`WorkspaceContext` 还原、工具端点；
- 前端 `/insight/ask`：左侧会话列表 + 右侧多轮对话 + 工具步骤 + 结果表/图表；
- `EventSource` 改为 `fetch` 流读取，因为 EventSource 不能携带 Authorization 头。

## 4. 验证

- `agent-service`：`npx tsc --noEmit` 通过；
- Java：`mvn -pl backend/dg-app -am test` 通过（552 项）；
- 前端：`pnpm type-check`、`pnpm build` 通过；
- 真实链路实测：
  - 创建会话 → `list_datasets` 工具 → “这个数据域有哪些表”返回 20 个数据集；
  - 创建会话 → `ask_data` 工具 → “按状态统计订单数”返回 3 行结果；
  - SSE 事件流经 Java 代理到达浏览器；
  - 工具调用过程在页面显示为 `list_datasets` / `ask_data` 步骤。
