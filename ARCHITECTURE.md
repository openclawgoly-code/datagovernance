# 数据治理平台 · ARCHITECTURE.md

> 本文件是架构的**元语（meta-language）索引**：先统一“我们说的词是什么意思”，
> 再指向真正的详细契约。它不是 `SPACE-MODEL.md` 的替代品，也不重复 35 项功能的推导。
>
> - 模块边界与功能归属：`SPACE-MODEL.md`
> - 治理对象与规则：`GOVERNANCE-MODEL.md`
> - 集成路线：`INTEGRATION-ROADMAP.md`
> - 前端设计元语：`frontend/DESIGN.md`

## 1. 架构元语

| 元语 | 含义 | 不指什么 |
|---|---|---|
| Space | 架构边界：Platform / Data / Metadata / Control / Runtime / Governance / UI | 不是 UI 上的“空间”菜单 |
| Workspace | 租户；代码与 API 中的 `workspaceId` | 不是架构 Space |
| Metadata | 定义态：数据源、目录、血缘、敏感标签、注册中心 | 不执行查询/任务 |
| Data | 连接器与只读查询契约：`DataAccessGateway` | 不解释业务语义 |
| Control | 任务定义、编译、DAG、Cron、下发 | 不执行物理任务 |
| Runtime | 统一 Execution 事实、执行器、制品、规则解释器 | 不解释业务语义 |
| Governance | 监控、告警、审计、分析报告 | 不向 Control 发 Command |
| Platform | 租户、用户、角色、菜单权限、凭据 | 不管理业务数据 |
| UI | Vue 3 前端；只消费 `/api/v1` | 不直连数据库/SuperSonic |
| Catalog | 规范化数据资产：来源、命名空间、数据集、字段、分区 | 不是 Snapshot 缓存 |
| Lineage | 字段级血缘：节点、作业、边 | 不是执行日志 |
| Sensitivity | 字段敏感标签与规则命中的复核状态 | 不是内容脱敏执行器 |
| AnalysisReport | 对执行/告警/任务的结论，版本不可变 | 不是制品 Artifact |
| Insight | 受治理的 AI 问数入口 | 不是语义层本身 |
| Semantic Model | SuperSonic 的指标/维度/数据集定义 | 不是平台物理表 |
| Query Gateway | 平台 `/api/v1/insight/*`，负责身份、限流、脱敏、审计 | 不是直接 SQL 客户端 |
| Design Token | 前端颜色/间距/字体等稳定变量，`var(--dg-*)` | 不是 Element Plus 内部变量 |

**术语纪律**：新词先进入本表；同一个概念只保留一个英文名。不要把 Workspace 写成 Space，
也不要把 “看板” 写成 “Dashboard” 之外的新词。

## 2. 运行时拓扑

```text
Browser
  → dg-app (Spring Boot, /api/v1, 静态 SPA)
      ├─ PostgreSQL (平台元数据库 / Flyway)
      ├─ dg-metadata → DataAccessGateway → 业务数据源
      └─ Insight Query Gateway
            → SuperSonic (语义层 / Chat BI / DeepSeek)
                → StarRocks (POC 内表;生产 External Catalog)
                    → MinIO / S3 / HDFS (Parquet)
```

- `dg-app` 是唯一同时看见各 Space 的装配层；
- 前端与 `dg-app` 同源部署时，`/` 与 SPA 路由由 `SpaForwardController` 回退到 `index.html`；
- 浏览器不持有 SuperSonic 账号，也不直连 StarRocks/ClickHouse；
- ClickHouse 仅保留为历史 POC 链路，当前平台默认走 StarRocks。

## 3. 模块边界

| 模块 | 职责 | 关键约束 |
|---|---|---|
| `dg-common` | 响应、错误码、WorkspaceContext、加密、ID | 不依赖 Spring Web |
| `dg-platform` | 租户、用户、角色、菜单、凭据 | 凭据唯一解密入口 |
| `dg-data-spi` | 连接器契约、只读 SQL 护栏 | 零驱动依赖 |
| `dg-data-connectors` | JDBC/文件/对象存储实现 | 不解释业务语义 |
| `dg-metadata` | 数据源、目录、血缘、敏感、注册中心 | 不执行任务 |
| `dg-runtime` | Execution、执行器、制品 | 不解释业务语义 |
| `dg-control` | 任务、编译、调度 | 不直接执行 |
| `dg-governance` | 监控、告警、审计、报告 | 不向 Control 发命令 |
| `dg-app` | REST、拦截器、Flyway、SPA | 唯一装配层 |
| `frontend` | Vue 3 + Element Plus + Pinia + Router | 只消费 API |

边界由 Maven 依赖缺失强制，不靠评审自觉。新增跨 Space 调用前先问：
“这是契约，还是我想省一层？”

## 4. 前端架构元语

- **入口**：`frontend/src/main.ts` → Pinia → Router → Element Plus；
- **路由**：静态声明 + `meta.permission`；菜单由后端 `pf_permission` 下发；
- **会话**：`stores/auth.ts`，只持久化 token/workspaceId，权限每次 `/auth/me` 重新拉；
- **请求**：`api/request.ts`，统一 `ApiResponse` 拆包、Bearer、`X-Workspace-Id`、401 清会话；
- **页面**：`views/` 按业务域；共享布局 `layouts/MainLayout.vue`；
- **设计元语**：`styles/tokens.css` + `frontend/DESIGN.md`；
- **AI 问数**：`views/insight/*`，经 `/api/v1/insight/*`，不直连 SuperSonic；
- **图表**：ECharts 按需引入；日期维度折线，其他维度柱状。
- **P0 计划**：`docs/plans/2026-10-30-frontend-p0.md`；实施拆分在 `openspec/changes/`。

### 4.1 API 契约

- 统一信封：`{ success, code, message, data, traceId, timestamp }`；
- 空间：`X-Workspace-Id` 可覆盖 token 内空间，但服务端每次重新校验成员资格；
- 权限：`@RequirePermission("code")`，前端 `meta.permission`/`v-permission` 同步；
- 审计：写操作由 `AuditInterceptor`；问数/导出由服务层写 `ig_query_log` + `gv_audit_record`。

## 5. 关键数据流

### 5.1 问数

```text
用户问题
  → /api/v1/insight/ask
  → 平台身份 + workspace + insight:ask
  → SuperSonic 账号映射（无映射回退服务账号）
  → SuperSonic 语义解析/SQL/执行
  → 平台按 md_column_sensitivity 兜底脱敏
  → ig_query_log + 审计
```

### 5.2 目录/血缘/敏感

```text
数据源 → CatalogProvider → md_catalog_* → 目录页
SQL/作业 → LineageService → md_lineage_* → 影响分析
字段名/注释 → SensitivityService → md_column_sensitivity → 问数脱敏/复核
```

## 6. 变更协议

1. 新术语先加到 §1；
2. 新模块/依赖先更新 §3，并确保 Maven 依赖不产生环；
3. 新前端 token 先改 `tokens.css`，再改 `frontend/DESIGN.md`；
4. 新 API 必须写清信封、权限码、workspace 作用域、审计落点；
5. 外部服务（SuperSonic/StarRocks/LLM）变更必须写进 `INTEGRATION-ROADMAP.md` 或对应 plan；
6. 文档中的“已完成”必须有代码、迁移或验证记录支撑。
