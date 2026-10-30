## Why

后端已经能按字段名/注释扫描敏感标签并支持人工复核，但前端没有工作台。数据管家只能通过 API 操作，无法形成“扫描 → 复核 → 标签生效”的闭环。

本变更提供 P0 的敏感规则、字段标签和复核界面。

## What Changes

- 新增 `/metadata/sensitivity` 页面与 `metadata:sensitivity:read` 权限
- 规则 Tab（只读）
- 字段标签 Tab：级别/状态/数据集筛选
- 复核抽屉：确认/拒绝/调整级别
- 发起扫描（全工作区/指定数据集）
- 复核后刷新并提示审计

## Capabilities

### New Capabilities

- `sensitivity-workbench`: 敏感规则、字段标签、扫描与人工复核。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/SensitivityView.vue`、`api/sensitivity.ts`
- 后端：消费现有 `/api/v1/metadata/sensitivity/*`
- 依赖：`frontend-p0-foundation`
- 不新增扫描历史接口；P0 不展示历史
