## Why

当前工作台只展示数据源状态，业务分析人员和数据管家登录后看不到“今天该处理什么”：失败执行、未处理告警、待复核敏感标签、最近问数、最近资产都分散在各页面。

本变更把工作台改成按角色和权限组合的 P0 概览页。

## What Changes

- 工作台按权限显示卡片：
  - 数据源健康
  - 失败执行
  - 未处理告警
  - 待复核敏感标签
  - 最近问数
  - 最近资产
- 增加“需要处理”区块和快捷入口
- 不请求无权限模块
- 保持现有空间选择逻辑

## Capabilities

### New Capabilities

- `workbench-overview`: 角色化、权限感知的首页概览。

### Modified Capabilities

无。

## Impact

- 前端：`views/workbench/WorkbenchView.vue`、各 API 客户端
- 后端：只消费现有 executions/alerts/sensitivity/insight/catalog 接口
- 依赖：`frontend-p0-foundation`；建议在资产/敏感/查询历史完成后接入链接
