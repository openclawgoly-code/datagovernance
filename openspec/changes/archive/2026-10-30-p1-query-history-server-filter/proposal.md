## Why

P0 查询历史只对当前页做客户端过滤，用户找一条跨页记录必须逐页翻找，也无法按用户定位问题。历史数据增长后，这个交互会直接失效。

本变更把关键词、成功/失败和用户筛选下沉到服务端，并保留现有分页与详情。

## What Changes

- `GET /api/v1/insight/queries` 增加可选参数：
  - `keyword`：匹配问题或生成 SQL
  - `succeeded`：`true|false`
  - `username`：精确匹配用户名
- 筛选条件与分页组合，返回 `PageResult` 不变
- 查询历史页：服务端筛选栏、300ms 关键词防抖、筛选变更重置到第 1 页
- 筛选状态同步到 URL，刷新/分享保持条件
- 移除“仅过滤当前页”的提示与客户端过滤

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `insight-query-history`: 历史列表从当前页客户端过滤改为服务端筛选，并要求筛选与分页/URL 一致。

## Impact

- 前端：`views/insight/QueryHistoryView.vue`、`api/insight.ts`、`types/insight.ts`
- 后端：`InsightController`、`InsightQueryService.history`
- 数据库：无新表；`ig_query_log` 现有索引可支撑时间/用户过滤，关键词为 `LIKE` 扫描
- 依赖：`query-history-page`
- 非目标：不做全文索引、不做历史导出、不改变历史可见范围、不做结果快照
