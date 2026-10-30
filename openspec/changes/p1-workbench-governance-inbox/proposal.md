## Why

P0 工作台把失败执行、未处理告警和待复核敏感标签拆成三张卡，只能看数量和前几条，无法统一排序、筛选、处理或暂时移出视野。

P1 把这些现有对象聚合为一个治理收件箱，同时保留来源系统的事实与状态。

## What Changes

- 新增 `/workbench/inbox` 治理收件箱页面
- 新增后端聚合端点：
  - `GET /api/v1/governance/inbox?page=&size=&sourceType=&state=&severity=&keyword=`
  - `POST /api/v1/governance/inbox/{sourceType}/{sourceId}/dismiss`
  - `POST /api/v1/governance/inbox/{sourceType}/{sourceId}/reopen`
- 新增 `gv_inbox_state` 表，只保存“收件箱已移除/已恢复”状态，不复制来源对象
- 新增权限 `governance:inbox:read`、`governance:inbox:manage`；内置角色授权
- 工作台“需要处理”区块增加“打开收件箱”入口
- 收件箱只展示当前用户有来源读权限的条目：
  - 失败/超时执行 → `runtime:execution:read`
  - 未处理告警 → `governance:alert:read`
  - 待复核敏感标签 → `metadata:sensitivity:read`

## Capabilities

### New Capabilities

- `workbench-governance-inbox`: 跨失败执行、告警和敏感标签的统一治理收件箱、筛选与移除/恢复。

### Modified Capabilities

无。

## Impact

- 前端：新增 `views/workbench/InboxView.vue`、路由、`api/governance.ts`、`api/insight.ts` 类似类型；修改工作台入口
- 后端：新增 `InboxController`/`InboxService`、`gv_inbox_state` migration、审计映射
- 数据库：V26 新增状态表、权限种子和角色授权；不复制来源事实
- 依赖：`workbench-overview`、`sensitivity-workbench`、`query-history-page`
- 非目标：不做工单流转、SLA、负责人分派、通知订阅、来源状态自动变更、跨空间收件箱
