## Context

平台已有技术目录、字段、血缘和敏感标签，但没有“业务上这个词是什么意思”的共享定义。业务域由 `p2-asset-ownership-certification` 引入，本变更复用该 domain。

术语需要独立于资产采集事实：字段被重命名/重采集时，术语定义不应被覆盖；绑定关系可以单独维护。

## Goals / Non-Goals

**Goals:**

- 维护 workspace 级业务术语；
- 支持定义、同义词、业务域、负责人和发布状态；
- 术语可绑定到数据集或字段；
- 资产详情展示已发布术语；
- 术语管理可审计。

**Non-Goals:**

- 不做 AI 自动生成/抽取；
- 不接外部术语库（Collibra/DataHub 等）；
- 不做术语版本历史；
- 不做跨 workspace 共享；
- 不自动把术语同步到 SuperSonic 语义模型（P3 可做）；
- 不替代资产 owner/domain（由 ownership change 管理）。

## Decisions

### 1. 术语与绑定分表

```text
md_glossary_term
  id, workspace_id, term, definition, synonyms_json,
  domain_id, status, owner_username,
  published_by, published_at,
  created_at, created_by, updated_at, updated_by
  unique(workspace_id, lower(term))

md_glossary_binding
  id, workspace_id, term_id,
  dataset_id, column_id,
  note, created_at, created_by
  unique(term_id, dataset_id) where column_id is null
  unique(term_id, column_id) where column_id is not null
```

- `dataset_id` 必有；`column_id` 可空。
- `column_id` 非空时必须属于 `dataset_id`，由服务端校验。
- 删除/下线资产时不级联删除术语，只在绑定查询时过滤不可读资产；P3 再做孤儿清理。

### 2. 状态与发布

```text
DRAFT ──publish──> PUBLISHED ──deprecate──> DEPRECATED
  ^                     │
  └──── edit ───────────┘
```

- 只有 `PUBLISHED` 术语出现在资产详情；
- `DEPRECATED` 保留在术语管理页，默认筛选不显示；
- 发布写 `publishedBy/publishedAt`；
- 术语修改不自动回退状态（保持当前状态，记录 updatedBy/At）。

### 3. 术语唯一性与同义词

- workspace 内 term 不区分大小写唯一，避免“GMV”和“gmv”两份定义；
- synonyms 存 JSON 数组，仅用于搜索和展示，不作为唯一键；
- 关键词搜索匹配 term、definition、synonyms 的序列化文本，P2 用 `LIKE` 扫描。

### 4. API

```text
GET  /metadata/glossary/terms?keyword&domainId&status&page&size   glossary:read
POST /metadata/glossary/terms                                    glossary:manage
PUT  /metadata/glossary/terms/{id}                               glossary:manage
POST /metadata/glossary/terms/{id}/publish                       glossary:manage
POST /metadata/glossary/terms/{id}/bindings                      glossary:manage
DELETE /metadata/glossary/terms/{id}/bindings/{bindingId}        glossary:manage
GET  /metadata/catalog/datasets/{id}/glossary                     asset:read
```

绑定请求：

```json
{ "datasetId": "catds_...", "columnId": "catcol_...", "note": "订单金额口径" }
```

### 5. 前端

- 新增 `/metadata/glossary`，菜单挂在数据源管理下；
- 术语列表：关键词、domain、status 筛选，分页；
- 新建/编辑抽屉：term、definition、synonyms、domain、owner；
- 绑定抽屉：可搜索数据集/字段，展示已绑定资产并支持解绑；
- 资产详情增加“业务术语”面板（只显示 PUBLISHED）；
- 资产字段抽屉显示该字段绑定术语；
- 复用 `DatasetSearchSelect`、`FilterBar`、`MetadataPanel`。

### 6. 审计

审计拦截器增加 `/glossary` → `GLOSSARY/GOVERNANCE`；POST/PUT/DELETE 分别记 CREATE/UPDATE/DELETE。

## Risks / Trade-offs

- [术语重复/近义] → term 唯一 + synonyms 搜索；不自动合并。
- [绑定到已删除字段] → 详情查询过滤不可读资产；管理页标记失效绑定。
- [LIKE 搜索性能] → P2 规模可接受；P3 引入 trigram/全文索引。
- [术语与 SuperSonic 脱节] → 本变更只做平台词汇；同步到语义模型放 P3。
- [状态误用] → 只有 PUBLISHED 进资产详情，DRAFT 不对外。

## Migration Plan

1. 先完成 domain 表（ownership change）；
2. V28 建术语/绑定表、种权限；
3. 发布后端 API；
4. 发布术语页和资产详情面板；回滚隐藏菜单即可。

## Open Questions

- 一个术语是否允许归属多个 domain（P2 单 domain）；
- 是否需要术语审批流（P2 由 glossary:manage 直接发布）。
