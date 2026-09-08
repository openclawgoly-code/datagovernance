# 数据治理平台

按 **Space Model** 推导出模块边界的数据治理平台。**P1–P5 全部完成**,
35 项功能中 34 项已落地,序号 34(高质量数据集制备)按需求确认独立立项,
本仓库锁定它与平台之间的四条契约。

> **术语警示**:本平台功能菜单中的「**空间**」是**租户**(代码里一律叫 `Workspace`),
> 而架构文档中的「**Space**」是**边界**概念。二者同名不同物,阅读与沟通时务必区分。
> 完整术语表见 [`SPACE-MODEL.md`](./SPACE-MODEL.md)。

## 这个仓库和"按菜单拆模块"有什么不同

原始需求是一份 35 项功能清单,分数据集成 / 数据开发 / 运维监控 / 数据服务四个子系统。
直接按菜单拆模块会得到错误的边界。三个可从需求文本本身证明的反例:

1. **「基础配置」菜单横跨三个 Space**。序号 28-33 同在一个菜单下,但 28/29/30(空间、
   角色、用户)属 Platform,31/32(Flink 执行器、平台级 JAR)属 **Runtime** —— 它们是
   执行器池与制品仓库,不是配置项 —— 33(告警渠道)属 **Governance**。按菜单建模会产出
   一个杂物袋模块,还会把 Runtime 资源管理错放进配置层。
   代码中 `pf_permission.owner_space` 列就是这条结论的落地。

2. **五处「执行记录」必须收敛为一个 Execution 模型**。序号 10/15/19/21/23 分属五个模块,
   但 24(任务监控)、25(告警规则)、27(审计日志)三条需求各自独立地要求跨子系统聚合。
   若每个模块各建一张执行记录表,这三项都做不出来。

3. **规则管理(17)是双栖对象**。清洗与转换规则若硬编码进同步算子,规则管理页面就形同
   虚设。必须是 Rule DSL(Metadata 拥有)+ 运行时解释器(Runtime 拥有)。

完整推导见 [`SPACE-MODEL.md`](./SPACE-MODEL.md),35 项功能的归属见
[`docs/function-space-matrix.md`](./docs/function-space-matrix.md)。

## 模块结构 —— 模块边界即 Space 边界

```
backend/
  dg-common           跨 Space 共享内核:响应封装 / 错误码 / 租户上下文 / 状态机 / 加密
  dg-platform         Platform/Tenancy Space —— 空间(租户)、用户、角色、菜单权限、凭据
  dg-data-spi         Data Space 对外契约 —— 纯接口 + 值对象,零驱动依赖
  dg-data-connectors  Data Space 实现 —— 10 种连接器、类型映射、异常翻译
  dg-metadata         Metadata Space —— 数据源定义、生命周期、目录快照、版本、注册中心
  dg-runtime          Runtime Space —— 统一 Execution 事实、执行器、制品、规则解释器
  dg-control          Control Space —— 任务定义、编译、DAG、Cron 调度、下发
  dg-governance       Governance Space —— 监控、告警、审计、告警渠道
  dg-app              装配层 —— REST / 安全过滤链 / Flyway / OpenAPI
frontend/             UI Space —— Vue 3 + TypeScript + Element Plus
```

**边界是编译期强制的,不是靠自觉。** 每一条约束都是 pom 里"某个依赖不存在"这个事实:

| 缺失的依赖 | 它保证的约束 |
|---|---|
| `dg-metadata` 看不见 `dg-data-connectors` | Metadata 不得执行任何东西 |
| `dg-runtime` 看不见 `dg-metadata` | Runtime 不得解释业务语义 |
| `dg-runtime` 看不见 `dg-control` | 同步依赖子图无环(H.2.1) |
| `dg-governance` 看不见 `dg-control` | 治理不向任何 Space 发 Command |

最后一条尤其容易被侵蚀:一旦 Governance 能调 Control,「失败三次就自动暂停这个
任务」必然会在三个月内被加进来,而那一行代码会让依赖图变成环
(control → runtime → 事件 → governance → control)。需要干预时它只发告警,
由人来决定。

Metadata 与 Data 唯一的接触面是 `DataAccessGateway`,且只暴露 Query 语义 ——
网关上没有任何写入、建表或提交任务的方法。一旦那里出现 `writeData()`,
Metadata 就获得了执行能力,边界即失守。

## 技术栈

| 层 | 主选(已落地) | 信创替代方向 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 | 毕昇 JDK / 东方通 TongWeb |
| 元数据库 | PostgreSQL 16 + Flyway | 达梦 DM8 / 人大金仓 KingbaseES |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | 同(纯前端无替代压力) |
| 运行时(P2+) | Apache Flink | 同 |

完整选型与替代矩阵见 `SPACE-MODEL.md` 第 L 章。

## 本地运行

### 先决条件
JDK 21、Maven 3.9+、Node 22 + pnpm、一个 PostgreSQL 16 实例。

### 1. 准备数据库

```bash
createdb datagovernance
psql -c "CREATE USER datagov WITH PASSWORD 'datagov'; GRANT ALL ON DATABASE datagovernance TO datagov;"
```

### 2. 配置必需的密钥

应用**不内置任何默认密钥** —— 未配置时直接启动失败,而不是退化成一个内置值加密生产凭据。

```bash
export DG_SECRET_KEY="$(openssl rand -base64 32)"       # 凭据/密钥的 AES-GCM 主密钥
export DG_JWT_SIGNING_KEY="$(openssl rand -base64 48)"  # JWT 签名密钥
# 可选:不设则首次启动生成随机管理员口令并以 WARN 打印一次
export DG_ADMIN_PASSWORD="换成你自己的口令"
```

> `DG_SECRET_KEY` 一旦用于加密过数据就不能更换,否则已存凭据无法解密。
> 密文带 `v1:` 版本前缀,将来换算法(如信创场景改用 SM4-GCM)可平滑共存。

### 3. 启动后端

```bash
mvn -pl backend/dg-app -am spring-boot:run
```

Flyway 会自动建表并灌入权限与内置角色种子。首次启动还会创建默认空间与管理员账号。

- API 文档:http://localhost:8080/swagger-ui.html

### 4. 启动前端

```bash
cd frontend && pnpm install && pnpm run dev
```

## 测试

```bash
./scripts/start-test-postgres.sh            # 起一个本地 PostgreSQL(可反复执行)
mvn test                                    # 全部后端测试
mvn -pl backend/dg-data-connectors test     # 连接器(含真实数据库端到端)
cd frontend && pnpm run build               # 前端类型检查与构建
```

连接器的端到端测试打一个**真实的 PostgreSQL** —— 只有真实的 `DatabaseMetaData`
才能验证结构探测与类型映射,mock 做不到。没起数据库时这些用例**跳过而非失败**,
所以改一行类型映射不必先准备环境。

另有一套 Testcontainers 版本(`PostgreSqlConnectorContainerIT`)供 CI 使用,
在没有 Docker 的环境下同样自动跳过。

### 端到端验收(P1–P5)

单元测试之外,有五份打**真实运行中的应用**的端到端验收脚本,逐条核对各阶段的
完成判据。它们可反复执行,连接参数全部从环境变量读:

| 脚本 | 断言数 | 覆盖 |
|---|---|---|
| `verify-p1.py` | 47 | 数据源、连通性、目录、租户隔离、多节点 MPP、空间启停 |
| `verify-p2.py` | 102 | 编译诊断、Cron、真实搬 2500 行、整库迁移、文件/接口解析、规则 |
| `verify-p3.py` | 77 | 实时保活状态机、离线开发跑真 SQL、工作流条件求值与级联取消 |
| `verify-p4.py` | 60 | 五个监控口径、告警抑制窗口、Webhook 真推送、审计不可变 |
| `verify-p5.py` | 50 | Intelligence 四条契约、脱敏在落地前生效 |
| `verify-p6-datasets.py` | 26 | 公开数据集:分区表结构探测、异构类型保真、整库迁移对照、GBK 中文文件解析 |
| `verify-ui.mjs` | 82 | 真实浏览器驱动的全部页面 |

它们验证的不是"接口通了",而是那些容易在重构中悄悄失效的约束。几个例子:

- 数据源响应体里不存在任何口令字段;跨空间取数据返回 404 而不泄露资源是否存在
- 取消一个正在跑的 20 万行同步,**目标库真的停止增长**
- 条件不成立时下游节点没有执行,而工作流仍然成功 —— 「没跑」不是「失败」
- 抑制窗口内的第二条告警**仍被记录**,但 Webhook 桩收到 0 条新消息
- 搬两行带身份证号的记录后,**目标库里查不到任何一个完整的身份证号**,而源表仍是明文

后三条各自抓到过一个真实缺陷:进度回调整行写回抹掉了取消状态、
被取消的工作流永远停在「取消中」、拼错的配置键被静默忽略导致脱敏失效。

### 公开数据集验收(P6)

前五份脚本的数据是它们自己造的:表是现建的,列是挑好的,编码一律 UTF-8,
没有分区、没有自定义类型、没有中文列名。那样的数据能证明"功能通不通",
证明不了"遇到真实的库会不会塌"。P6 用公开数据集补上这一段:

```bash
./scripts/seed-public-datasets.sh            # 下载 + 灌库 + 备文件素材
./scripts/seed-public-datasets.sh ftp start  # 起本地只读 FTP(文件解析要用)
DG_ADMIN_PASSWORD='换成你的口令' python3 scripts/verify-p6-datasets.py
```

| 数据集 | 来源 | 验证什么 |
|---|---|---|
| Pagila | PostgreSQL 官方示例库的社区移植 | 15 张表藏在 55 个分区里;ENUM / DOMAIN / `text[]` / `tsvector` / `vector` / 生成列 |
| Chinook | 官方同时提供 PG / MySQL / Oracle / SQLServer 四套脚本 | 整库迁移唯一有**参照答案**的形态 —— 迁完拿官方版逐表对行数与内容 |
| 健康样本 | Synthea 官方样本;取不到时本地生成同结构替身 | 中文表头 + GBK 编码 + 合法校验位的合成身份证号,走 FTP 做文件解析与脱敏 |

素材全部落在 `.seed-cache/`(已 gitignore)。身份证号是本地合成的,校验位算对
但号段与生日随机组合,不对应任何真人;哪一份是官方样本、哪一份是替身,
写在生成出来的 `PROVENANCE.txt` 里 —— 两者混在一起而无从分辨,会让后续
所有基于它的结论都失去依据。

**首次运行抓到四个缺陷**(断言现为红色,尚未修):

1. **分区污染结构树,父表反而不见了。** `AbstractJdbcConnector` 的
   `BROWSABLE_TABLE_TYPES` 不含 `PARTITIONED TABLE`,而 pgjdbc 把分区父表报成
   这个类型、把 55 个子分区报成普通 `TABLE` —— 结果恰好反过来:用户看不到
   `payment`,却要在 55 个月度碎片里找路。
2. **FTP 流被关两次就抛异常。** try-with-resources 同时持有 `BufferedReader`
   和底层流,关闭时 `FilterInputStream.close()` 被调用两次,第二次在已断开的
   连接上调 `completePendingCommand()`。表现最坏:200 行全部写进目标表了,
   执行状态却是 `FAILED` —— 值班的人重跑一次就是双写。
3. **`path` 指向文件本身时拼出不存在的路径。** FTP 对一个文件执行 `LIST`
   会返回该文件自己那一条,`FileParseRunner.resolveFiles` 把它当成目录清单,
   拼出 `/health/x.csv/x.csv`。编译器的配置说明写的是「path 文件或目录路径」。
4. **`FileParseCompiler` 没有接 `warnUnknownKeys`。** P5 给离线同步补的那道防线
   没有覆盖文件解析这条路径 —— 同一个拼错键名的错误在这里依然静默通过,
   而这恰恰是脱敏最要紧的一条链路。

### P1 验收(示例)

其余阶段同理,把脚本名换掉即可:

```bash
./scripts/start-test-postgres.sh                # 1. 起测试数据库
mvn -q install -DskipTests                      # 2. 构建
DG_DB_URL=jdbc:postgresql://127.0.0.1:55432/datagovernance \
DG_DB_USER=postgres DG_DB_PASSWORD=postgres \
DG_SECRET_KEY="$(openssl rand -base64 32)" \
DG_JWT_SIGNING_KEY="$(openssl rand -base64 48)" \
DG_ADMIN_PASSWORD='换成你的口令' \
  java -jar backend/dg-app/target/dg-app-0.1.0-SNAPSHOT.jar &   # 3. 启动

DG_ADMIN_PASSWORD='换成你的口令' python3 scripts/verify-p1.py    # 4. 验收
```

它验证的不只是"接口通了",还包括几条容易在重构中悄悄失效的约束:数据源响应体里
不存在任何口令字段、手工测试失败回到 `DRAFT` 而非 `UNREACHABLE`、未验证的数据源
不允许浏览结构、跨空间取数据返回 404 而不泄露资源是否存在。

### 前端验收

另有一份用真实浏览器(Playwright)驱动的 UI 验收:

```bash
cd frontend
DG_API_TARGET=http://127.0.0.1:8080 pnpm run preview &     # 起前端并代理到后端
cd .. && DG_PLAYWRIGHT_ROOT=$(npm root -g) \
  DG_ADMIN_PASSWORD='换成你的口令' node scripts/verify-ui.mjs
```

它覆盖登录跳转、后端下发的菜单渲染、数据源列表与目录树、结构浏览抽屉的逐层下钻,
以及一条重要的架构约束在界面上的体现:**表单结构由 `capabilities` 决定而非类型判断**
—— 选 RestAPI 出现「接口地址」、选 MySQL 出现「主机/端口/库名」并自动填 3306。

## 演进路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1** | Metadata Kernel + Data 连接器 + Platform 租户身份 + UI 骨架 | ✅ 序号 1-8、28-30 |
| **P2** | Control(编译+调度)+ Runtime Gateway + 集成任务 | ✅ 序号 9-17 |
| **P3** | 实时/离线开发 + 工作流编排 + 执行器 + 制品仓库 | ✅ 序号 18-23、31、32 |
| **P4** | Governance:监控 / 告警 / 审计 / 告警渠道 | ✅ 序号 24-27、33 |
| **P5** | Intelligence 契约 + 敏感数据脱敏 | ✅ 序号 34(契约)、35(合规底座) |

最核心的技术资产按顺序形成:**Metadata Kernel → Workflow DSL → Workflow Compiler →
Runtime Contract**。这四项决定系统能否持续扩展。

### 两处诚实的缺口

平台不假装自己能做没有接入的事。以下两类作业在当前部署下**明确失败并说明缺什么**,
而不是返回"成功、处理 0 行":

- **实时开发作业**需要 Flink 集群 —— 失败码是 `RTM_EXECUTOR_UNAVAILABLE`
  (环境缺件),不是内部错误。值班的人据此判断该找运维还是找开发。
- **Python 训练作业**需要 K8s。

假成功会让编译、下发、执行记录、状态机整条链路都"通过",而实际什么都没发生 ——
对一个靠"跑没跑"来判断任务好坏的平台,那比缺一个功能更糟。接入时各新增一个
`ExecutionEngine` 实现即可,上层的编译、状态机与监控都不必改。

序号 34「高质量数据集制备」按需求确认**独立立项**(工程量与序号 1-33 之和相当)。
本仓库锁定它与平台之间的四条契约并写成可执行的代码:取数不得直连业务数据源
(编译期拦截)、算力走 Control 复用统一 Execution、产物注册进 Metadata Registry、
字段到医学概念的映射写入关系边。
