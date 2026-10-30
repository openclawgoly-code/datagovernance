## Why

平台已经记录每次问数的问题、SQL、状态、耗时和脱敏列，但前端没有查询历史入口。业务分析人员无法回看自己问过什么，数据管家也无法排查低质量回答。

本变更提供只读查询历史，并支持在权限允许时重新执行。

## What Changes

- 新增 `/insight/queries` 页面与 `insight:read` 权限
- 历史表格：问题、用户、状态、行数、耗时、脱敏列、时间
- 详情抽屉：完整 SQL、数据集、脱敏列、错误信息
- 有 `insight:ask` 时“重新执行”
- 客户端状态/关键词过滤，服务端分页

## Capabilities

### New Capabilities

- `insight-query-history`: 问数历史浏览、详情与重新执行。

### Modified Capabilities

无。

## Impact

- 前端：`views/insight/QueryHistoryView.vue`、`api/insight.ts`
- 后端：消费现有 `/api/v1/insight/queries` 与 `/ask`
- 依赖：`frontend-p0-foundation`
