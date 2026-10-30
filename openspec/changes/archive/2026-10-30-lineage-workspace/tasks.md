## 1. API 与数据模型

- [x] 1.1 新建 `api/lineage.ts`，封装 upstream/downstream/impact/node
- [x] 1.2 增加 `LineageNode`、`LineageEdge`、`ImpactItem` 前端类型
- [x] 1.3 实现路径到节点/边的去重归一化

## 2. 选择器与控制条

- [x] 2.1 数据集/字段搜索选择器
- [x] 2.2 方向切换：上游/下游/双向
- [x] 2.3 深度 1–3 与节点类型过滤
- [x] 2.4 URL `?columnId=` 同步

## 3. 图与详情

- [x] 3.1 ECharts graph 渲染节点和边
- [x] 3.2 点击节点显示右侧 MetadataPanel
- [x] 3.3 节点跳转资产详情
- [x] 3.4 图缩放、重置、节点高亮

## 4. 影响与空态

- [x] 4.1 影响分析表格
- [x] 4.2 复制影响清单
- [x] 4.3 空态/加载/错误状态
- [x] 4.4 大图收敛提示

## 5. 验证

- [x] 5.1 手工验证上游/下游/双向
- [x] 5.2 100 节点内交互可用
- [x] 5.3 `pnpm type-check`、`pnpm build` 通过
