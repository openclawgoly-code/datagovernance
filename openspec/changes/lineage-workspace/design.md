## Context

后端血缘模型是三张表（节点、作业、边）加 BFS 查询；API 已经按列给出 upstream/downstream/impact。前端需要把路径转成可读图，并让用户从节点跳进资产详情。

当前没有图组件依赖，ECharts 已用于问数图表。

## Goals / Non-Goals

**Goals:**

- 从字段出发看上下游；
- 支持方向、深度、节点类型过滤；
- 节点详情与资产详情互通；
- 影响列表可复制。

**Non-Goals:**

- 不做表级血缘（后端无端点）；
- 不做人工补录边 UI；
- 不做图布局持久化；
- 不做跨空间全局血缘搜索。

## Decisions

### 1. ECharts graph，而不是 Vue Flow

P0 节点量有限，ECharts 已存在；graph series 支持节点/边、点击事件和缩放。
Vue Flow 更适合可编辑画布，但引入新依赖和拖拽状态，P0 不需要。

### 2. 后端返回路径，前端归一化为节点/边

`upstream`/`downstream` 响应可能包含重复节点和边。前端按 `nodeId` 和 `from→to` 去重，保留最小层级。

### 3. 方向与深度是查询参数，不是图内过滤

用户切换方向/深度时重新请求，避免把全图一次性拉回前端。
节点类型过滤在前端执行。

### 4. 大图收敛

超过 100 节点或深度 > 2 时提示“只展示 2 层/前 100 节点”，避免浏览器卡死。

## Risks / Trade-offs

- [后端路径字段结构不统一] → 在 API 客户端做适配层，组件只认 `LineageNode/LineageEdge`。
- [ECharts graph 节点重叠] → 使用 force/circular 布局，提供缩放和重置。
- [影响列表与图不一致] → 影响列表始终以 impact API 为准，图只做导航。

## Migration Plan

新增页面与菜单，不影响现有目录；回滚只需移除路由和菜单种子。

## Open Questions

- 是否需要导出影响清单 CSV？P0 只支持复制文本。
