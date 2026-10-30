## 1. 后端筛选

- [x] 1.1 `InsightController.queries` 增加 `keyword`、`succeeded`、`username` 可选参数
- [x] 1.2 `InsightQueryService.history` 增加筛选参数，workspace 条件始终保留
- [x] 1.3 关键词使用分组条件 `(question LIKE ? OR generated_sql LIKE ?)`，空串忽略，超长 400
- [x] 1.4 状态与用户名条件按 AND 组合，分页 `total` 反映筛选后总数
- [x] 1.5 验证组合筛选、空值、超长关键词与 workspace 隔离（服务端 API）

## 2. 前端服务端筛选

- [x] 2.1 `api/insight.ts` 的 `queries` 参数补充 `keyword/succeeded/username`
- [x] 2.2 查询历史筛选栏改为服务端查询：关键词 300ms 防抖，状态/用户名立即触发
- [x] 2.3 筛选变化重置 `page=1`，翻页/页大小保留筛选
- [x] 2.4 移除“仅过滤当前页”提示和客户端过滤逻辑
- [x] 2.5 筛选与分页同步到 URL，进入页面时从 URL 初始化
- [x] 2.6 空结果、加载中、错误态与清除筛选

## 3. 验证

- [x] 3.1 跨页关键词能找到第 2 页记录
- [x] 3.2 失败筛选 + 翻页后仍只有失败记录
- [x] 3.3 用户名精确筛选正确
- [x] 3.4 刷新/分享 URL 恢复关键词、状态、页码
- [x] 3.5 无匹配、超长关键词、空关键词行为正确
- [x] 3.6 `pnpm type-check`、`pnpm build` 通过
- [x] 3.7 `mvn -pl backend/dg-app -am test` 通过
