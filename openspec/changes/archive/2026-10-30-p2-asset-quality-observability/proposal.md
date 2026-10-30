## Why

当前目录只能看到行数、大小、schema hash 等采集事实，无法回答“这张表能不能用、数据是否及时、字段是否为空/重复”。P0 明确把质量/可观测留给后续阶段。

本变更提供资产级质量规则、手动质量运行、结果历史和质量分数。

## What Changes

- 新增质量规则表 `md_quality_rule`
- 新增质量运行表 `md_quality_run`
- 内置规则类型：
  - ROW_COUNT_RANGE
  - NOT_NULL
  - UNIQUE
  - FRESHNESS
  - SCHEMA_DRIFT
- 新增端点：
  - `GET/POST/PUT/DELETE /api/v1/metadata/quality/rules`
  - `POST /api/v1/metadata/quality/rules/{id}/run`
  - `POST /api/v1/metadata/quality/datasets/{id}/run`
  - `GET /api/v1/metadata/quality/datasets/{id}/summary`
  - `GET /api/v1/metadata/quality/runs`
- 资产详情增加“质量”Tab；新增 `/metadata/quality` 总览页
- 质量分数按最新运行和严重度计算；无运行显示未评估
- 新增 `metadata:quality:read/manage` 权限
- 规则运行/变更写审计

## Capabilities

### New Capabilities

- `asset-quality-observability`: 资产质量规则、运行、历史和质量分数。

### Modified Capabilities

无。

## Impact

- 前端：资产详情、质量总览页、菜单、API/类型
- 后端：新增 Quality entity/mapper/service/controller、V29 迁移；复用 DataAccessGateway
- 依赖：`asset-catalog-detail`、`p2-asset-ownership-certification`（owner/domain 展示）
- 非目标：内容采样/画像、自定义 SQL 规则、自动排程、异常检测、把质量结果接入告警（P3）、SLA
