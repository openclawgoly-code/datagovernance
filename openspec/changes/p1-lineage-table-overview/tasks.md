## 1. 后端聚合

- [ ] 1.1 在 `LineageService` 注入 `CatalogDatasetMapper`，更新构造函数与现有单元测试
- [ ] 1.2 实现 `datasetGraph(datasetId, direction, depth)`：列级 BFS、按数据集分组、生成聚合边
- [ ] 1.3 补齐数据集名称/限定名；跳过缺少 `catalogDatasetId` 或数据集已删除的边
- [ ] 1.4 新增 `GET /metadata/lineage/datasets/{id}/graph`，受 `metadata:lineage:read` 保护
- [ ] 1.5 补充单元测试：同表多列聚合、双向、无血缘、缺少数据集归属

## 2. 前端表级模式

- [ ] 2.1 `api/lineage.ts` 增加 `datasetGraph`；`types/lineage.ts` 补充聚合边字段（如需要）
- [ ] 2.2 `/metadata/lineage` 增加字段级/表级切换，URL 同步 `mode` 与 `datasetId`
- [ ] 2.3 表级模式复用 `DatasetSearchSelect`、`LineageGraph`、节点详情与影响清单
- [ ] 2.4 节点详情提供“打开资产详情”和“查看字段级血缘”
- [ ] 2.5 无血缘、部分边未归属、大图收敛提示
- [ ] 2.6 表级模式不显示字段选择器和无意义的节点类型过滤器

## 3. 验证

- [ ] 3.1 用 ODS→DWD→DWS 合成血缘验证表级上下游与边计数
- [ ] 3.2 验证从表级节点跳资产详情、切回字段级并保留数据集
- [ ] 3.3 验证无权限端点 403 与空表级图
- [ ] 3.4 `pnpm type-check`、`pnpm build` 通过
- [ ] 3.5 `mvn -pl backend/dg-app -am test` 通过
