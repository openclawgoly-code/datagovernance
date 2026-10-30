## 1. 路由与入口

- [x] 1.1 新增 `AssetDetailView.vue` 与 `/metadata/assets/:id` 路由
- [x] 1.2 Catalog 列表行点击/抽屉增加“打开详情”
- [x] 1.3 处理 404、无权限和返回目录状态

## 2. 页面骨架

- [x] 2.1 实现 Header、摘要指标和快速动作
- [x] 2.2 实现 `DetailTabs` 与 `?tab=` 同步
- [x] 2.3 首屏并行加载 dataset + columns

## 3. Tabs

- [x] 3.1 概览 Tab：描述、来源、格式、位置、schema hash、属性 JSON
- [x] 3.2 字段 Tab：字段表格 + 字段抽屉
- [x] 3.3 敏感 Tab：标签表格 + 复核入口
- [x] 3.4 血缘 Tab：字段选择器 + 上下游预览
- [x] 3.5 分区 Tab：分区表格 + 非 Parquet 空状态

## 4. 验证

- [x] 4.1 URL 可分享、刷新保持 Tab
- [x] 4.2 字段抽屉不重复请求整页数据
- [x] 4.3 `pnpm type-check`、`pnpm build` 通过
