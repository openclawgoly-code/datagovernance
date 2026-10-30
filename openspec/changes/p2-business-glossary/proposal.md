## Why

同一指标在目录、问数、血缘和报表里可能叫不同名字；业务分析人员看到字段名也不知道业务定义。P0/P1 只有技术元数据和敏感标签，没有共享业务词汇。

本变更建立 workspace 级业务术语表，并可绑定到资产/字段。

## What Changes

- 新增 `md_glossary_term`：
  - term、definition、synonyms、domain、status、owner
- 新增 `md_glossary_binding`：
  - term 绑定 dataset 或 column
- 新增端点：
  - `GET/POST/PUT /api/v1/metadata/glossary/terms`
  - `POST /api/v1/metadata/glossary/terms/{id}/publish`
  - `POST/DELETE /api/v1/metadata/glossary/terms/{id}/bindings`
  - `GET /api/v1/metadata/catalog/datasets/{id}/glossary`
- 新增 `/metadata/glossary` 页面与菜单
- 资产详情增加“业务术语”面板，展示已发布术语
- 新增 `metadata:glossary:read/manage` 权限
- 术语创建、发布、绑定、解绑写审计

## Capabilities

### New Capabilities

- `business-glossary`: 业务术语、定义、同义词、业务域和资产绑定。

### Modified Capabilities

无。

## Impact

- 前端：新增 `views/metadata/GlossaryView.vue`、资产详情术语面板、路由/菜单、API/类型
- 后端：新增 Glossary entity/mapper/service/controller、V28 迁移
- 依赖：`p2-asset-ownership-certification`（业务域）、`asset-catalog-detail`
- 非目标：AI 自动生成定义、外部术语库同步、术语版本历史、跨 workspace 术语、自动同义词抽取
