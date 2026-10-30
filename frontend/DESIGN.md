# 数据治理平台 · 前端 DESIGN.md

> 本文是 `frontend/` 的设计源文档。它定义 **设计元语（design meta-language）**：
> 颜色、字体、间距、组件与交互的稳定词汇。页面实现可以变化，但这些词不能悄悄变化。
>
> 参考 `~/projects/followup/followup-nx/apps/web-frontend/DESIGN.md` 的「Clinical Calm」
> 组织方式，但本产品是数据治理 + AI 问数控制台，不照搬医院随访的品牌与业务规则。

## 1. 设计方向

**Governed Calm / 受治理的冷静控制台。**

- 深板岩侧边栏，冷灰画布，白色工作表面；
- Element Plus 蓝作为主操作色，语义色只用于状态；
- 数据密度高，但不拥挤；表格、SQL、图表、状态标签是主要视觉对象；
- 不做紫色 SaaS 渐变、玻璃拟态、营销式大卡片、无意义的动效；
- 业务人员看「问数/看板」，工程人员看「数据源/任务/血缘/敏感标签」，两类页面共享同一套元语。

## 2. 目标范围

实现目标：

- `frontend/`
- Vue 3 + TypeScript + Vite
- Element Plus
- Pinia + Vue Router
- ECharts（问数图表）

不要：

- 在 `backend/` 里写前端样式；
- 绕过 Element Plus 重新造一套基础组件；
- 把 SuperSonic 的 UI 直接嵌成主界面（只作为可选过渡）。

## 3. 设计元语（Meta-language）

这些词是评审和 agent 提示里的共同语言：

| 元语 | 含义 | 实现落点 |
|---|---|---|
| Canvas | 页面最底层背景 | `--dg-canvas` / `body` |
| Surface | 承载业务内容的白色面 | `--dg-surface` / `el-card` |
| Ink | 主文字与高强调标题 | `--dg-text` |
| Muted | 元数据、辅助说明 | `--dg-text-muted` |
| Primary | 提交、保存、创建等主操作 | `--dg-primary` / `el-button--primary` |
| Semantic | 成功/警告/危险/信息 | `--dg-success/warning/danger/info` |
| Divider | 面板、表格、输入边框 | `--dg-border` |
| Rhythm | 页面留白与块间距 | `--dg-space-*` |
| Density | 表格/表单的紧凑程度 | `size="small"` + 统一行高 |
| Elevation | 层级阴影 | Element Plus 默认，不额外堆阴影 |
| Radius | 圆角 | `--dg-radius` / `--dg-radius-lg` |

新词先加到本表，再写代码；不要用“那个灰蓝色的东西”这类不可检索的描述。

## 4. 设计 Tokens

唯一来源：`frontend/src/styles/tokens.css`。

### 4.1 颜色

| Token | 值 | 用途 |
|---|---:|---|
| `--dg-canvas` | `#f5f7fa` | 页面画布 |
| `--dg-surface` | `#ffffff` | 卡片、表格、表单容器 |
| `--dg-surface-muted` | `#fafafa` | 嵌套弱背景 |
| `--dg-sidebar` | `#304156` | 左侧导航 |
| `--dg-sidebar-active` | `#263445` | 导航选中 |
| `--dg-sidebar-text` | `#bfcbd9` | 导航文字 |
| `--dg-sidebar-text-active` | `#ffffff` | 导航选中文字 |
| `--dg-text` | `#303133` | 主文字 |
| `--dg-text-secondary` | `#606266` | 次要文字 |
| `--dg-text-muted` | `#909399` | 元数据/说明 |
| `--dg-border` | `#e4e7ed` | 面板/表格分隔 |
| `--dg-border-strong` | `#dcdfe6` | 输入/强调边框 |
| `--dg-primary` | `#409eff` | 主操作 |
| `--dg-success` | `#67c23a` | 成功/可用 |
| `--dg-warning` | `#e6a23c` | 待处理/风险 |
| `--dg-danger` | `#f56c6c` | 删除/失败 |
| `--dg-info` | `#909399` | 中性信息 |

规则：

- 不在页面里直接写 `#409eff`、`#f5f7fa`、`#909399`；
- 状态只用语义色，不用 `red-500` 之类的任意色名；
- 主按钮是 `--dg-primary`，不要把所有按钮都染成主色；
- 卡片保持白色；弱背景只用于嵌套说明，不用于整页。

### 4.2 字体

- Sans：`--dg-font-sans`，CJK 优先；
- Mono：`--dg-font-mono`，只用于 SQL、ID、代码；
- 页面标题：`18–20px / 600`；
- 区块标题：`14–16px / 600`；
- 正文：`13–14px / 400`；
- 元数据：`12px / 400`，`--dg-text-muted`；
- 数字指标可用等宽数字（`font-variant-numeric: tabular-nums`）。

### 4.3 间距与布局

| Token | 值 | 用途 |
|---|---:|---|
| `--dg-page-padding` | `16px` | `.page-container` 内边距 |
| `--dg-sidebar-width` | `220px` | 侧边栏 |
| `--dg-header-height` | `60px` | 顶栏 |
| `--dg-space-sm` | `8px` | 控件组间距 |
| `--dg-space-md` | `12px` | 卡片内块间距 |
| `--dg-space-lg` | `16px` | 页面块间距 |
| `--dg-space-xl` | `24px` | 大区块 |

- 页面统一使用 `.page-container`，不要在视图里重复写 `padding: 16px`；
- 页面主块之间用 `16px`，卡片内部用 `12px`；
- 复杂页面先分「页头 → 概览 → 主表 → 详情/抽屉」。

## 5. 布局骨架

```text
┌─────────────────────────────────────────────┐
│ Sidebar 220px │ Topbar 60px                  │
│               ├─────────────────────────────┤
│               │ Main: .page-container        │
│               │   page header                │
│               │   stats / filters            │
│               │   card/table                 │
│               │   detail drawer              │
└─────────────────────────────────────────────┘
```

- 侧边栏由后端菜单树驱动，不硬编码完整导航；
- 顶栏显示面包屑、空间选择、用户菜单；
- 主内容 `overflow: auto`，表格横向滚动留在卡片内部。

## 6. 组件规则

### 6.1 页面与卡片

- 每个页面以 `.page-container` 开始；
- 主内容用 `el-card shadow="never"`，不堆叠多层阴影；
- 卡片头用简洁标题 + 右侧操作，不使用大段说明；
- 复杂表单优先页面/抽屉；短表单可用 `el-dialog`，但不要所有 CRUD 都弹窗。

### 6.2 表格

- 默认 `size="small"`，紧凑但可读；
- 主实体列在前，状态列用 `el-tag`，操作列右对齐；
- 时间列区分「创建时间 / 更新时间 / 业务时间」，不要只写“时间”；
- 空数据用 `el-empty` 或简短空状态，不用插画；
- 长文本 `show-overflow-tooltip`，不要撑破布局。

### 6.3 表单

- 标签在上（`label-position="top"`），与 Element Plus 默认一致；
- 必填、校验错误在字段下方，不只靠 toast；
- 主操作放右侧或底部，取消在左、提交在右；
- 保存中禁用提交，防重复；
- 凭据/密钥字段使用 `type="password"`，不回显明文。

### 6.4 状态与反馈

- 成功：绿色 `el-tag` / `ElMessage.success`；
- 警告：橙色 `el-tag`；危险：红色；
- 中性/停用：灰色；
- 加载：表格 `v-loading`；按钮 `:loading`；
- 错误必须可定位：表单字段错误 + 页面/接口消息，不能只在 toast 里一闪而过。

### 6.5 SQL / 代码块

- 使用 `<pre>` + `--dg-font-mono`；
- 背景 `--dg-surface-muted`，圆角 `--dg-radius`；
- 长 SQL 横向滚动，不强行换行；
- 只读展示，不提供可编辑 SQL 入口（除专用 SQL 控制台）。

### 6.6 AI 问数

- 输入区：数据集选择 + 多行问题 + 主按钮；
- 结果区顺序：摘要 → 图表 → 结果表 → SQL；
- 必须显示：`state`、耗时、是否截断、脱敏列；
- 有敏感列时用警告条明确“哪些列被遮蔽”；
- 图表：日期维度折线，其他维度柱状；没有数值列不出图；
- 导出按钮只在有结果时出现，并受 `insight:export` 权限控制；
- 看板：保存问题定义，不保存结果快照；刷新时重新问数。

### 6.7 看板

- 左侧看板列表，右侧详情/问题项；
- 版本、刷新、ACL 是明确动作，不藏在右键菜单；
- 自动刷新默认关闭，开启时显示 60s 节奏；
- 单题失败显示错误，不阻塞其他题。

## 7. 导航与权限

- 菜单树由后端 `pf_permission` 下发；
- 路由 `meta.permission` 决定能否直接访问 URL；
- 无权限跳 `/403`，未登录跳 `/login`；
- 不把敏感内容渲染在权限检查之前；
- 空间（Workspace）是租户，切换空间必须换后端令牌。

## 8. 交互与动效

- 动效只用于状态提示：展开、加载、成功；
- 不做页面级编排动画；
- hover 用背景/边框变化，不用大幅缩放；
- 弹窗只用于短表单、确认、轻量预览；
- 删除、导出、发布等不可逆动作要二次确认；
- 修改列表数据后重新拉取列表，不依赖组件残留状态。

## 9. 响应式

- 桌面优先，侧边栏固定；
- 窄屏折叠为抽屉/紧凑菜单；
- 表格允许横向滚动；
- 操作栏可换行，不溢出；
- 图表容器高度固定，宽度跟随卡片。

## 10. Agent 提示词

```text
只改 frontend/。使用 Vue 3 + TypeScript + Element Plus。
遵守 frontend/DESIGN.md 的 Governed Calm 与 design tokens；
使用 var(--dg-*) 和 .page-container，不写死颜色。
页面结构：page header → filters/stats → card/table → detail。
表格紧凑、状态用 el-tag、错误不只靠 toast。
AI 问数结果顺序：摘要 → 图表 → 表格 → SQL；显示脱敏列。
不要绕过 RBAC 菜单；不要直连 SuperSonic；不要在浏览器里放服务账号口令。
改完运行 pnpm type-check && pnpm build。
```

## 11. Do / Don't

Do：

- 用 `var(--dg-*)`；
- 用 `el-card shadow="never"`；
- 用 `el-table size="small"`；
- 用语义色表达状态；
- 用 `.page-container`；
- 用 `formatDateTime` 统一时间格式；
- 用后端菜单/权限驱动导航；
- 改完跑 `pnpm type-check` 与 `pnpm build`。

Don't：

- 不写死 `#409eff` / `#f5f7fa`；
- 不用紫色渐变、玻璃拟态、营销卡片；
- 不把完整详情塞在长表格下面；
- 不只靠 toast 报错；
- 不把凭据/密钥写进前端代码或日志；
- 不让浏览器直连 SuperSonic；
- 不绕过 `meta.permission` 直接进页面。

## 12. 变更协议

- 新颜色/间距/圆角：先改 `tokens.css`，再改本文件；
- 新共享组件：先在本文件写规则，再实现；
- 新页面：必须说明使用的元语与布局模式；
- 任何视觉重构：保留现有信息密度与 Element Plus 语义，不要为了“好看”降低可读性。
