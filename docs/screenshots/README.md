# 界面截图

由 `scripts/capture-ui-flow.mjs` 从**真实运行的前后端**捕获,不是设计稿。
它们嵌在 `docs/user-manual.html` 里(以 data URI 形式,因为手册要能单文件分发)。

重新生成:

```bash
./scripts/start-test-postgres.sh
./scripts/seed-public-datasets.sh                 # Pagila / Chinook / 健康样本
./scripts/start-test-mysql.sh                     # 信创迁移那条线要用
mvn -pl backend/dg-app -am spring-boot:run &
cd frontend && pnpm run preview &

DG_ADMIN_PASSWORD='…' python3 scripts/seed-demo-data.py    # 铺一层像样的演示数据
DG_ADMIN_PASSWORD='…' DG_PLAYWRIGHT_ROOT=…/node_modules \
  SHOT_DIR=docs/screenshots node scripts/capture-ui-flow.mjs
```

`seed-demo-data.py` 是必要的一步:空列表页截出来没有意义。它建六个数据源、
四个任务、三条清洗规则与告警规则,并真的跑几次任务产生执行记录。
