## Context

`ig_query_log` 保存问题、生成 SQL、用户名、成功标记、耗时和时间。P0 接口只接收 `page/size`，前端对当前页记录做关键词与状态过滤，并明确提示“只过滤当前页”。

当记录超过一页时，用户无法找到目标记录。P1 需要让筛选与分页在同一层工作。

## Goals / Non-Goals

**Goals:**

- 关键词、成功/失败、用户名筛选由服务端执行；
- 筛选与分页组合，返回总数正确；
- 筛选条件可分享/刷新保持；
- 保留现有 `insight:read` 权限与详情抽屉。

**Non-Goals:**

- 不做全文检索或新增搜索引擎；
- 不做跨用户权限提升；
- 不做导出；
- 不做结果快照；
- 不改变重新执行逻辑。

## Decisions

### 1. 扩展现有端点，不新建历史 API

`GET /api/v1/insight/queries` 新增可选参数：

```text
page, size
keyword    optional   question 或 generated_sql 包含
succeeded  optional   true/false
username   optional   精确匹配
```

所有条件 AND 组合，`page/size` 行为不变。

### 2. 关键词条件必须分组

`keyword` 同时匹配 `question` 与 `generated_sql`。MyBatis-Plus 必须写成：

```text
workspace_id = ?
AND (question LIKE ? OR generated_sql LIKE ?)
AND succeeded = ?
AND username = ?
```

否则 OR 会绕过 workspace 条件。关键词去空白、限制 200 字符，空串视为不筛选。

### 3. 前端切换为服务端筛选

- `status` 使用 `'' | 'SUCCESS' | 'FAILED'`，映射为 `succeeded=true/false`；
- 用户名使用精确输入，不引入平台用户搜索（P1 不依赖 `platform:user:read`）；
- 关键词 300ms 防抖；
- 任何筛选变化将 `page` 重置为 1；
- 分页动作保留当前筛选条件。

### 4. URL 保存筛选状态

URL 形状：

```text
/insight/queries?keyword=销售&status=FAILED&username=alice&page=2&size=20
```

进入页面时从 URL 初始化筛选；变更后用 `router.replace` 更新。空值不写入 URL。

### 5. 性能取舍

P1 使用 `LIKE '%kw%'`，不新增索引。当前日志规模用现有 `workspace_id, created_at` 索引即可；数据量上来后 P2 再引入 trigram 或搜索引擎，不在本变更假装做了全文优化。

## Risks / Trade-offs

- [关键词全表扫描] → 限制 workspace 与 200 字符，P2 再做索引/全文。
- [用户筛选需要知道用户名] → P1 用精确用户名输入；P2 再接可搜索用户选择器。
- [URL 与筛选状态不同步] → 单一 `syncRoute()`，筛选变更统一重置页码。
- [旧客户端仍传无参数] → 参数全部可选，保持兼容。

## Migration Plan

无数据库迁移。后端先发布可选参数，前端再移除客户端过滤；回滚前端仍可调用旧接口。

## Open Questions

- P2 是否加入时间范围筛选与导出；
- 是否需要“我的问数 / 全部问数”快捷切换。
