## Why

P0 敏感工作台只能逐条复核。数据管家在一次扫描后通常面对几十到上百条 `SUGGESTED` 标签，逐条打开抽屉效率过低。

本变更增加批量复核，保留单条复核作为精细操作。

## What Changes

- 新增批量复核端点：
  - `POST /api/v1/metadata/sensitivity/columns/batch-review`
  - 入参：`ids`、`status=CONFIRMED|REJECTED`、可选 `level`
  - 返回：请求数、成功数、失败数、逐条失败原因
- 敏感标签表格增加多选与批量操作条
  - 批量确认、批量拒绝、批量调级
  - 部分成功时提示成功/失败数量
- 批量操作复用 `metadata:sensitivity:review` 权限和现有审计链路
- 限制单次最多 200 条

## Capabilities

### New Capabilities

- `sensitivity-batch-review`: 敏感标签的批量确认、拒绝与调级。

### Modified Capabilities

无。

## Impact

- 前端：`views/metadata/SensitivityView.vue`、`api/sensitivity.ts`、`types/sensitivity.ts`
- 后端：`SensitivityController`、`SensitivityService`、`AuditInterceptor` 的动作映射
- 数据库：无新表；复用 `md_column_sensitivity`
- 依赖：`sensitivity-workbench`
- 非目标：不做扫描历史、规则编辑、后台批量任务、批量撤销、跨空间操作
