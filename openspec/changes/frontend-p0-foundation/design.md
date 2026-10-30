## Context

当前前端已经具备 Vue 3、Vite、Element Plus、Pinia、Vue Router、ECharts，并且 `frontend/DESIGN.md` 与 `frontend/src/styles/tokens.css` 已落地设计元语。

P0 的页面依赖同一批后端能力：Catalog、Lineage、Sensitivity、Insight、Dashboard。各页面如果直接写 `el-table`、状态色、空态和路由跳转，会产生三套不一致的体验。

## Goals / Non-Goals

**Goals:**

- 建立 P0 的统一路由、权限和菜单入口；
- 建立可复用的展示组件和 API 类型；
- 让后续五个页面只关注业务内容，不重复造基础设施。

**Non-Goals:**

- 不引入新的 UI 框架；
- 不重构现有页面为统一框架；
- 不新增术语、工单、质量等 P1 后端能力；
- 不修改 Query Gateway 的服务端语义。

## Decisions

### 1. 路由静态声明，菜单后端下发

路由放在 `frontend/src/router/index.ts`，每条路由声明 `meta.permission`；菜单仍由 `pf_permission` 下发。
原因：路由是前端渲染契约，菜单是权限投影；两者混在一起会让后端故障直接导致 404。

### 2. 共享组件放 `frontend/src/components/insight/` 与 `common/`

- `PageHeader`、`FilterBar`、`EmptyState` 放 `components/common/`
- `EntityLink`、`SensitivityBadge`、`MetadataPanel`、`DetailTabs`、`QuerySqlBlock` 放 `components/insight/`

组件只接收数据与回调，不直接请求 API；页面负责加载和组合。

### 3. 资产详情采用路由页

`/metadata/assets/:id` 而不是继续扩展 Catalog 抽屉。
原因：详情需要承载字段、血缘、敏感、分区多个 Tab，URL 可分享、可刷新；抽屉只保留快速预览。

### 4. 图表复用 ECharts

血缘图使用已安装的 `echarts/core` graph series，不新增 Vue Flow。
原因：P0 节点量小，ECharts 已用于问数图表；减少依赖和打包体积。

### 5. API 客户端按域拆分

- `api/catalog-assets.ts`
- `api/lineage.ts`
- `api/sensitivity.ts`
- `api/insight.ts`

每个客户端只包装现有接口，不在前端拼 SQL 或做权限判断。

## Risks / Trade-offs

- [共享组件过早抽象] → 只抽 P0 实际重复的 3 个以上场景，未用到的先不抽。
- [菜单种子与页面路由不一致] → 每个菜单项必须有对应路由，CI/验收时逐项检查。
- [ECharts 图在大量节点时卡顿] → P0 限制默认 2 层、超过 100 节点提示收敛。
- [现有 Catalog 抽屉与详情页重复] → 抽屉只保留摘要和“打开详情”，不再复制完整 Tab。

## Migration Plan

1. 先合入 V25 菜单种子和空路由；
2. 合入共享组件与 API 类型；
3. 后续 change 逐个接入页面；
4. 回滚时只回滚前端路由/组件，不影响后端数据。

## Open Questions

- 是否需要把 `PageHeader` 同时替换现有页面？P0 不做，避免大范围回归。
- 查询历史是否需要导出？P0 不做，导出仍由问数结果页负责。
