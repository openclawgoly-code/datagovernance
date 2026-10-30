## 1. 数据模型与权限

- [ ] 1.1 V28：`md_glossary_term`、`md_glossary_binding`、唯一索引和 domain 外键
- [ ] 1.2 种入 `metadata:glossary:read/manage`，授权 admin/developer manage，viewer read
- [ ] 1.3 新增 Glossary entity/mapper/DTO
- [ ] 1.4 新增菜单 `menu:metadata:glossary` 与角色授权
- [ ] 1.5 AuditInterceptor 增加 `/glossary` → `GLOSSARY/GOVERNANCE`

## 2. 后端服务

- [ ] 2.1 术语 CRUD、发布/废弃，term 大小写不敏感唯一
- [ ] 2.2 domain/owner 校验；同义词 JSON 读写
- [ ] 2.3 绑定/解绑，校验 column 属于 dataset
- [ ] 2.4 术语分页与关键词/domain/status 筛选
- [ ] 2.5 资产术语查询，只返回 PUBLISHED
- [ ] 2.6 端点与权限：terms CRUD、publish、bindings、asset glossary
- [ ] 2.7 单元测试：重复 term、跨 dataset column、发布过滤、权限

## 3. 前端

- [ ] 3.1 API/类型：术语、绑定、资产术语
- [ ] 3.2 新增 `/metadata/glossary` 页面、路由、菜单
- [ ] 3.3 术语列表筛选/分页、创建/编辑抽屉、发布/废弃
- [ ] 3.4 绑定抽屉：数据集/字段选择、已绑定列表、解绑
- [ ] 3.5 资产详情术语面板与字段抽屉术语展示
- [ ] 3.6 权限控制：只读用户隐藏管理动作，无 read 路由 403
- [ ] 3.7 空态、错误态、失效绑定提示

## 4. 验证

- [ ] 4.1 创建/发布 GMV 术语，资产详情可见
- [ ] 4.2 大小写重复 term 被拒绝
- [ ] 4.3 字段不属于数据集被拒绝
- [ ] 4.4 DRAFT/DEPRECATED 不在资产详情显示
- [ ] 4.5 只读用户无管理按钮；无权限 403
- [ ] 4.6 审计 GLOSSARY/UPDATE
- [ ] 4.7 `pnpm type-check`、`pnpm build` 通过
- [ ] 4.8 `mvn -pl backend/dg-app -am test` 通过
