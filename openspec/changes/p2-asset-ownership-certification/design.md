## Context

`md_catalog_dataset` 是采集事实表，刷新目录时会更新 schema、行数、大小等字段。如果把 owner/domain/认证直接放在这张表上，下一次采集或未来的 upsert 很容易把人工治理结论覆盖掉。

Catalog 当前没有 Domain/Owner/Certification 模型，资产详情只能显示“未配置”。

## Goals / Non-Goals

**Goals:**

- 业务域、负责人、管家、认证状态可维护；
- 采集刷新不覆盖人工治理结论；
- 资产详情可编辑，目录列表可筛选和识别认证状态；
- 负责人/管家必须是当前 workspace 的有效成员；
- 治理变更可审计。

**Non-Goals:**

- 不做业务术语表（独立 change）；
- 不做质量分数/质量规则（独立 change）；
- 不做访问申请（独立 change）；
- 不自动从 LDAP/OA 同步负责人；
- 不支持多负责人/多管家；
- 不改变数据源或 Catalog 采集链路。

## Decisions

### 1. 独立治理表，而不是扩 Catalog 事实表

新增：

```text
md_business_domain
  id, workspace_id, code, name, description, owner_username,
  created_at, created_by, updated_at, updated_by
  unique(workspace_id, code)

md_asset_governance
  id, workspace_id, dataset_id,
  domain_id, owner_username, steward_username,
  certification_status, certified_by, certified_at,
  certification_note, review_due_at,
  created_at, created_by, updated_at, updated_by
  unique(workspace_id, dataset_id)
```

`dataset_id` 外键指向 `md_catalog_dataset`。采集刷新只更新 Catalog 事实，不触碰治理表。数据集被逻辑删除时治理行保留，详情不可见。

### 2. 认证状态机保持简单

```text
NONE ──certify──> CERTIFIED ──deprecate──> DEPRECATED
  ^                    │                         │
  └──── reset ─────────┴─────────────────────────┘
```

- `CERTIFIED` 写入 `certified_by=当前用户`、`certified_at=now`；
- `review_due_at` 可选，只做展示和提醒，不阻断查询；
- `DEPRECATED` 必须填写 `certification_note`（迁移说明）；
- 状态变更均写审计。

### 3. 负责人与管家必须属于当前空间

写入前用 `WorkspaceService.listMemberIds` 与 `UserService` 校验：

- 用户存在；
- 状态 ACTIVE；
- 是当前 workspace 成员。

历史数据中用户被移出空间时，详情仍显示原 owner，但 UI 标注“已不在当前空间”，允许管理员重新指派。

### 4. 治理摘要只做 additive 字段

`DatasetView` 增加：

```text
domainId, domainName, ownerUsername, certificationStatus
```

Catalog 列表接口增加可选过滤：

```text
domainId, owner, certificationStatus
```

实现可用 `md_asset_governance` 与 `md_business_domain` 左连接；未配置的资产返回 `certificationStatus=NONE`。现有调用方不传新参数时行为不变。

### 5. API 与权限

```text
GET  /metadata/catalog/datasets/{id}/governance   metadata:asset:read
PUT  /metadata/catalog/datasets/{id}/governance   metadata:asset:govern
GET  /metadata/domains                            metadata:asset:read
POST /metadata/domains                            metadata:asset:govern
PUT  /metadata/domains/{id}                       metadata:asset:govern
```

新增权限 `metadata:asset:govern`，V27 授权：

- admin、developer：read + govern
- viewer：read only

### 6. 前端

- 资产详情概览底部增加“治理”面板：业务域、owner、steward、认证徽标、复审日期；
- “编辑治理”抽屉：domain select、owner/steward 成员选择、认证状态、说明、复审日期；
- Catalog 列表增加 owner/认证列，筛选栏增加 domain/owner/certification；
- 认证徽标：
  - CERTIFIED → success
  - DEPRECATED → warning/danger
  - NONE → info

复用 `PageHeader`、`FilterBar`、`MetadataPanel`、`StatusTag`，新增的成员选择器优先复用现有用户/空间成员接口。

## Risks / Trade-offs

- [成员被移出空间] → 保留原值并标注，允许重新指派；不自动清空。
- [Domain 命名冲突] → workspace 内 code 唯一，name 可改；删除 domain 先要求无资产引用。
- [采集逻辑误写治理表] → 治理表独立，代码审查和测试明确不触碰。
- [认证被当成质量证明] → UI 说明“认证 ≠ 质量通过”，质量分数由独立 change 提供。
- [列表过滤性能] → 1000 表规模下 join + 索引足够；P3 再做搜索索引。

## Migration Plan

1. V27 建表、种权限并授权内置角色；
2. 后端发布 read/write 端点与 additive 列表字段；
3. 前端发布治理面板、编辑抽屉和筛选；
4. 回滚前端隐藏治理编辑，后端表可保留。

## Open Questions

- 是否需要认证到期自动提醒（可复用告警渠道）；
- 是否允许一个资产有多个 owner（P2 单 owner）。
